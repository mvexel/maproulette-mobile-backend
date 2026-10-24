# Fork changes compared to upstream

This fork (`mvexel/maproulette-mobile-backend`, branch `feat/mobile-oauth`) is
based on upstream
[`maproulette/maproulette-backend`](https://github.com/maproulette/maproulette-backend)
at commit
[`d9989212`](https://github.com/maproulette/maproulette-backend/commit/d998921292e400c5ebd2473024be7f6276057f2a)
(2026-10-10, "Restrict info about who mapped each task to challenge owners", v4.12.0). The fork was first imported
from an archive of `b9b2e69b`; that commit is recorded as an ancestor, so later
upstream syncs are ordinary merges of `upstream/main`.

### Evolution numbering

Fork migrations are never renumbered, because deployed databases have already
applied them. An upstream migration whose number the fork already uses is
copied to the next free number, with a comment naming the upstream file. Who
owns each number above upstream's 128 is in
[`conf/evolutions/ledger.txt`](../conf/evolutions/ledger.txt), and
`EvolutionLedgerSpec` checks it. How to claim a number and how to sync upstream:
[upstream-sync.md](upstream-sync.md).

| Upstream file | Fork file | Content |
| ------------- | --------- | ------- |
| `129.sql` (synced at `bbdc2d64`) | `133.sql` | `grants_role_valid` check constraint |

The mobile OAuth and field write-control features are opt-in. With
`mobileOAuth.enabled = false` and `mobileOAuth.writeControlEnabled = false`
(their defaults in `conf/application.conf`), web sessions and
existing routes behave as upstream, apart from the general fixes listed under
[Changed](#changed). No upstream file was removed. To compare the fork with its
base commit:

```sh
git -C <upstream checkout> archive d9989212 | tar -x -C /tmp/up
diff -r /tmp/up . --exclude=.git
```

Background and design: [mobile-oauth.md](mobile-oauth.md) (configuration and
security boundaries), [mobile-staging-deploy.md](mobile-staging-deploy.md)
(development deployment), the
[field deployment runbook](https://github.com/mvexel/infra/blob/main/docs/maproulette-field-deploy.md)
(stage/prod deployment), and [upstream-issues.md](upstream-issues.md)
(upstream behavior we found and did not change). The client side is the
[MapRoulette mobile SDK](https://github.com/mvexel/maproulette-mobile-sdk).

## Added

### Mobile OAuth provider (`/oauth/mobile/*`)

The fork adds an authorization-code flow with S256 PKCE for approved native apps,
with no secrets in those apps. A user signs in through OSM. The backend is a
separate confidential OSM OAuth client: it stores an OSM client secret and
exchanges the authorization code server-side. It requests `read_prefs` for
ordinary sign-in, or `read_prefs write_api` for an `osm:tagfix` grant. The backend
then issues its own short-lived access tokens and rotating refresh tokens,
scoped per grant.

| What                                                                                                                                                                   | Where                                                                                                                                                          |
| ---------------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Routes: `authorize`, `callback`, `consent`, `token`, `revoke`, `me`                                                                                                    | `conf/routes:5-10` → `app/controllers/MobileOAuthController.scala` (`authorize` :124, `callback` :170, `consent` :297, `token` :353, `revoke` :372, `me` :394) |
| Grant, token and code lifecycle (hashing, rotation, replay revocation)                                                                                                 | `app/org/maproulette/auth/mobile/MobileOAuthService.scala:36`                                                                                                  |
| Storage                                                                                                                                                                | `app/org/maproulette/auth/mobile/MobileOAuthRepository.scala`, models in `MobileOAuthModels.scala`                                                             |
| Scopes `tasks:read`, `tasks:write`, `tasks:edit`, `osm:tagfix` and `mobile:admin` (strict parsing; `osm:tagfix` and `tasks:edit` require `tasks:write` and exclude each other; `mobile:admin` stands alone), config clients | `app/org/maproulette/auth/mobile/MobileOAuthSettings.scala:21,30,86`                                                                                           |
| Clients read from `mobile_oauth_clients` with a 10 s cache, seeded from config (see [Mobile admin](#mobile-admin))                                                     | `app/org/maproulette/auth/mobile/MobileClientRegistry.scala:14,130`                                                                                            |
| OSM identity lookup, and provisioning of a MapRoulette user on first sign-in                                                                                           | `MobileOSMIdentity.scala:11`, `MobileUserProvisioner.scala:13`                                                                                                 |
| Configuration block (off by default) and its dispatcher                                                                                                                | `conf/application.conf` (`mobileOAuth { … }`, `mobile-oauth-dispatcher`)                                                                                       |
| Staging client registrations (`maproulette-android-example`, `maproulette-ios-example`, `maproulette-josm`)                                                                                | `conf/mobile-staging.conf`                                                                                                                                     |
| Tables `mobile_oauth_interactions`, `_families`, `_codes`, `_access_tokens`, `_refresh_tokens`                                                                         | `conf/evolutions/default/129.sql`                                                                                                                              |
| Dependency `scala-oauth2-core` 1.6.0                                                                                                                                   | `build.sbt`                                                                                                                                                    |

### Bearer gate for mobile grants

`MobileBearerFilter` runs after the existing filters. A request with
`Authorization: Bearer …` is checked against an exact route allowlist for the
grant's scopes. If it passes, the filter attaches the user to the request, and
`SessionManager` uses that user instead of the session or API key. Mobile
credentials never fall back to the other authentication paths. Requests
without a bearer token pass through unchanged.

| What                                                                                                                                                                                                | Where                                                                              |
| --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------- |
| Filter registration                                                                                                                                                                                 | `app/org/maproulette/filters/Filters.scala`                                        |
| Read allowlist (`tasks:read`): challenge search, challenge, tags, tasks, task, `choice/check`, task and marker boxes, `me`                                                                          | `app/org/maproulette/auth/mobile/MobileBearerFilter.scala:55` (`MobileReadRoutes`) |
| Write allowlist (`tasks:write`): `GET` `start` and `release`; `POST` `skip` and `choice`; `PUT` status `1`, `2`, `5`, `6`. No query string and no body, except the choice JSON (at most 2048 bytes) | `MobileBearerFilter.scala:84` (`MobileWriteRoutes`)                                |
| Editor allowlist (`tasks:edit`): status `1`, `2`, `5`, `6` with `requestReview=true`, `tags` and a JSON body; `POST` comment; `GET` `refreshLock`; `PUT` `changeset`; the task bundle routes except review. Listed query keys only, bodies at most 16 KiB. See [Desktop editor login](#desktop-editor-login-josm) | `MobileBearerFilter.scala` (`MobileEditorRoutes`) |
| Admin allowlist (`mobile:admin` only): see [Mobile admin](#mobile-admin)                                                                                                                            | `MobileBearerFilter.scala:25` (`MobileAdminRoutes`)                                |
| Filter logic and error responses (`insufficient_scope`, `invalid_request`, `admin_required`)                                                                                                        | `MobileBearerFilter.scala:115,162`                                                 |
| The bearer user takes precedence in `userAwareRequest`                                                                                                                                              | `app/org/maproulette/session/SessionManager.scala:239`                             |

### Desktop editor login (JOSM)

Lets desktop editors such as the JOSM MapRoulette plugin use the mobile OAuth
provider (epic mvexel/MapRoulette-JOSM#54, issue #55). Items 1 and 2 are
upstream-bound, as part of the provider; items 3 and 4 are fork-only. Each item
is its own commit.

| What | Where | Kind |
| ---- | ----- | ---- |
| 1. Loopback callbacks: `http://127.0.0.1:<port>/…` or `http://[::1]:<port>/…`, exact match, `localhost` refused (RFC 8252 §7.3, §8.3). The consent page's `form-action` names the loopback origin | `MobileOAuthSettings.validRedirect`, `MobileOAuthController.consentPage`, `mobile-oauth.md` | upstream-bound |
| 2. `tasks:edit` scope, `MobileEditorRoutes`, consent text, docs | `MobileOAuthSettings.scala` (`MobileScopes`), `MobileBearerFilter.scala`, `MobileOAuthController.scala`, `mobile-oauth.md` | upstream-bound |
| 3. Admin API validation and docs for loopback callbacks and `tasks:edit`; preview mode treats the editor routes as task writes | `MobileAdminController.scala`, `mobile-admin-api.md`, `MobileDrafts.scala` | fork-only |
| 4. `maproulette-josm` staging client (`tasks:read tasks:write tasks:edit`, callback `http://127.0.0.1:8111/oauth_authorization`) | `conf/mobile-staging.conf` | fork-only |
| 5. Task bundle routes for `tasks:edit` (create, read, update, unbundle, delete, lockBundle, bundle unlock, bundle status, bundle comments; `taskIds` at most 50), consent text, docs | `MobileBearerFilter.scala` (`MobileEditorRoutes`), `MobileOAuthController.scala`, `mobile-oauth.md` | upstream-bound |
| 6. Preview mode and the choice status guard for bundles: draft bundles and draft `taskIds` members read as unknown, bundle create of draft tasks answers 404 for app grants, bundle status on a bundle holding a choice task is `403 choice_task_status` | `MobileDrafts.scala`, `MobileBearerFilter.scala` (`draftGate`, `choiceGate`), `TaskBundleController.createTaskBundle` | fork-only |

### Mobile admin

Backend for the admin web app: approved clients in the database, the super-user-only
`mobile:admin` scope, client management, an audit log and CORS for the admin origin. Details in
[mobile-admin-api.md](mobile-admin-api.md) and
[mobile-oauth.md](mobile-oauth.md#admin-scope-mobileadmin).

| What                                                                                                                                                                                               | Where                                                                                                                           |
| -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------- |
| Tables `mobile_oauth_clients` and `mobile_admin_audit`                                                                                                                                             | `conf/evolutions/default/131.sql`                                                                                               |
| Seeding from `mobileOAuth.clients`: insert new, follow config for never-edited rows, keep admin edits, disable dropped config rows                                                                 | `MobileClientRegistry.scala:98` (`MobileClientRows.seed`)                                                                       |
| A disabled client can't authorize, exchange, refresh or authenticate; revocation still works                                                                                                       | `MobileClientRegistry.scala:14` (`get` vs `known`), used in `MobileOAuthService.scala:36` and `MobileOAuthController.scala:372` |
| `mobile:admin` granted only to super-users: at the OSM callback (`access_denied` back to the app), on consent, code exchange and refresh                                                           | `MobileOAuthController.scala:82,211,312`, `MobileOAuthService.scala:54,214,228`                                                 |
| Super-user check (`Permission.isSuperUser`) on every admin request                                                                                                                                 | `app/org/maproulette/auth/mobile/MobileAdmin.scala:35` (`MobileAdminGate`), `MobileBearerFilter.scala:162`                      |
| Admin allowlist: `/api/v2/mobile-admin/*`, `me`, `POST /challenge`, `PUT /challenge/:id`, `PUT /challenge/:id/addFileTasks?lineByLine=true&report=true`, challenge, challenge tasks and task reads | `MobileBearerFilter.scala:25` (`MobileAdminRoutes`)                                                                             |
| Authenticated super-user admin bearer grants can prepare challenges and import tasks while the field write switch is off; legacy challenge writes remain blocked                                   | `MobileBearerFilter.scala` (`MobileFieldRoutes`, `MobileAdminRoutes`)                                                           |
| Optional `liveMissingQuestions` choice templates filter absent-tag questions at check time; selected answers are guarded again immediately before an OSM edit                                      | `ChoiceWork.scala`, `MobileChoiceService.scala`                                                                                 |
| Campaign results export `GET /api/v2/mobile-admin/challenges/:id/results?format=csv\|geojson`; choice submissions store their answers (`mobile_choice_submissions.answers`) | `conf/routes` → `app/controllers/MobileResultsController.scala`, `app/org/maproulette/provider/choice/ChoiceResults.scala`, `conf/evolutions/default/134.sql` |
| Organizer role (D8): scope `mobile:organize` (granted to clients that may hold `mobile:admin`), organizer list `mobile_memberships`, capability check `MobileAdminCheck.can` (projects with Write or better, not Home), organizer routes (stock routes GET only; writes refused while the write switch is off), Organizers routes for super-users (grant and list change in one transaction; 409 for a last project admin). **Deviation:** adding and removing an organizer's project role writes upstream's `grants` rows (grantee type 5 user, object type 0 project, role 1 or 2) by SQL instead of through `UserService.addUserToProject`/`removeUserFromProject`, so the role, the membership and the audit entry commit together; it then clears upstream's user and project caches. An upstream sync that changes the `grants` table, its `name` description or those caches must update `MobileOrganizers.scala`; `MobileOrganizerSpec` checks that `Permission.effectiveRole` and `hasObjectWriteAccess` see the role after add and lose it after removal | `conf/evolutions/default/145.sql`, `MobileAdmin.scala` (`MobileCapability`, `MobileAdminGate`), `MobileOrganizers.scala`, `MobileBearerFilter.scala` (`MobileOrganizeRoutes`), `app/controllers/MobileAdminAccess.scala`, `MobileOrganizerController.scala` |
| Organizer requests (ORG-ONB): `mobileOAuth.organizers.requests` (`MR_ORGANIZER_REQUESTS`, `closed` by default, or `review`); scope `mobile:organizer-request` reaches only `GET`/`POST /api/v2/mobile-admin/organizer-request`; super-users list, approve (adds the organizer) or decline with `GET …/organizer-requests` and `POST …/organizer-requests/:id/approve` or `/decline`; table `mobile_organizer_requests` | `conf/evolutions/default/151.sql`, `app/org/maproulette/auth/mobile/MobileOrganizerRequests.scala`, `app/controllers/MobileOrganizerRequestController.scala`, `MobileBearerFilter.scala`, `conf/application.conf` (`mobileOAuth.organizers.requests`), `conf/routes` |
| Campaign wizard: question-set library, Overpass counts and open features (cache, rate limits, timeout; endpoints from `OVERPASS_URLS`, next one on connect failure), one-call create of a draft choice campaign from a set and an area, and publish; `mobile_campaign_sources` | `conf/mobile/question-sets.json`, `conf/evolutions/default/146.sql`, `QuestionSets.scala`, `WizardOverpass.scala`, `CampaignWizard.scala`, `app/controllers/MobileWizardController.scala`, `MobileBearerFilter.scala` (`MobileOrganizeRoutes`), `conf/application.conf` (`mobileWizard`) |
| Campaign wizard reads the nightly OSM extract (`wizard_osm_features`, `wizard_osm_extract`) where a fresh one covers the area, Overpass elsewhere; the load script filters the extract with osmium by the question sets and swaps the table in one transaction | `conf/evolutions/default/149.sql`, `WizardSource.scala`, `WizardOverpass.scala`, `CampaignWizard.scala`, `scripts/wizard-extract/`, `conf/application.conf` (`mobileWizard.extractMaxAge`) |
| Campaigns list, project list/create and project on/off for the admin app (`/api/v2/mobile-admin/campaigns`, `/projects`, `/projects/:id/enabled`); no evolution | `conf/routes` → `app/controllers/MobileCampaignController.scala`, `app/org/maproulette/auth/mobile/MobileCampaigns.scala` |
| Live progress page (BACKLOG #12): one link-only page per campaign by an unguessable token; `GET`/`PUT /api/v2/mobile-admin/challenges/:id/live`, `POST …/live/rotate`, public `GET /api/v2/live/:token`; table `mobile_live_pages` | `conf/evolutions/default/141.sql`, `app/org/maproulette/provider/choice/LiveProgress.scala`, `app/controllers/MobileLiveController.scala`, `conf/routes` |
| Built-in `not-here` and `moved` reports (status 6, no OSM edit); `moved` carries a spot within 500 m of the task; results export report columns and a `reported_position` point | `ChoiceWork.scala` (`BuiltIn`), `MobileChoiceService.scala` (`Report`, `measured`), `ChoiceResults.scala` |
| Routes `GET`/`POST /api/v2/mobile-admin/clients`, `PATCH …/clients/:id[?revokeGrants=true]`, `GET …/audit`, `GET …/scope-sets` (allowed client scope sets, from `MobileScopes.clientScopeSets`)                                                                                         | `conf/routes:27-31` → `app/controllers/MobileAdminController.scala:103,107,112,126,156`                                               |
| Client writes and audit entries in one transaction; revocation of a client's grant families                                                                                                        | `MobileAdmin.scala:73` (`MobileAdminRepository`)                                                                                |
| Admin writes through stock routes are audited as `stock.<METHOD>`                                                                                                                                  | `MobileBearerFilter.scala:142`                                                                                                  |
| CORS for `mobileOAuth.adminOrigin` (`MR_MOBILE_ADMIN_ORIGIN`)                                                                                                                                      | `app/org/maproulette/auth/mobile/MobileCorsFilter.scala:24,65`, `MobileOAuthSettings.scala:132`                                 |
| Staging admin client `maproulette-mobile-admin` (`https://admin.mr-dev.osm.lol/callback`, `mobile:admin`) and admin origin                                                                         | `conf/mobile-staging.conf:26-37`                                                                                                |
| Per-database write policy, initially disabled on a new database, and audited updates                                                                                                               | `conf/evolutions/default/132.sql`, `MobileWritePolicy.scala`                                                                    |
| Stage service key: on stage only (`MR_DEPLOYMENT=stage` and the stage public origin), a static bearer key whose SHA-256 is `MR_STAGE_SERVICE_KEY_SHA256` acts as a dedicated super-user for challenge import and campaign/project reads; writes audited as `service_key.stock.<METHOD>`; ignored with a warning anywhere else. See [mobile-admin-api.md](mobile-admin-api.md#stage-service-key) | `app/org/maproulette/auth/mobile/MobileServiceKey.scala`, `MobileBearerFilter.scala`, `conf/application.conf` (`mobileOAuth.deployment`, `serviceKey.sha256`) |
| Super-user-only `GET`/`PUT /api/v2/mobile-admin/write-policy`; enabling also requires a configured OSM token encryption key                                                                        | `conf/routes`, `MobileAdminController.scala`                                                                                    |

### Multiple-choice tasks (`cooperativeWork.meta.type = 3`)

A new task kind: one OSM element, several questions, and answers applied as one
changeset by the backend using the user's OSM token. The full spec is in the SDK
repo:
[mobile-choice-challenges.md](https://github.com/mvexel/maproulette-mobile-sdk/blob/main/docs/design/mobile-choice-challenges.md).

| What                                                                                                                              | Where                                                                                                                                                                                                        |
| --------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| Payload model and validation (also run at ingest)                                                                                 | `app/org/maproulette/provider/choice/ChoiceWork.scala:40,53`                                                                                                                                                 |
| `GET /api/v2/task/:id/choice/check`: fresh OSM read and all-or-nothing eligibility                                                | `conf/v2_route/task.api` → `app/org/maproulette/controllers/api/MobileChoiceController.scala:38` → `MobileChoiceService.check` (`provider/choice/MobileChoiceService.scala:188`); rule in `staleness` (:123) |
| `POST /api/v2/task/:id/choice`: lock check, idempotency, re-check, one OSM changeset, status and changeset id in one transaction  | `MobileChoiceController.scala:45` → `MobileChoiceService.submit` (:418)                                                                                                                                      |
| OSM reads and uploads for choice tasks                                                                                            | `app/org/maproulette/provider/choice/ChoiceOsmClient.scala:28`                                                                                                                                               |
| The user's OSM token (`read_prefs write_api`), AES-256-GCM encrypted per grant family, key from `MR_MOBILE_OSM_TOKEN_KEY`         | `app/org/maproulette/auth/mobile/MobileOsmTokenCipher.scala:18`, `conf/application.conf` (`osmTokenKey`)                                                                                                     |
| Tables `mobile_osm_tokens`, `mobile_choice_submissions` and `choice_stale`, plus OSM-token columns on `mobile_oauth_interactions` | `conf/evolutions/default/130.sql`, `136.sql` (adds the `already_tagged` reason)                                                                                                                              |
| Stale tasks are recorded in `choice_stale` (insert-only; no task status is written)                                               | `MobileChoiceService.markStale` (:149)                                                                                                                                                                       |
| A replaced payload clears its `choice_stale` row                                                                                  | `app/org/maproulette/models/dal/TaskDAL.scala:428`                                                                                                                                                           |
| A challenge holds only choice tasks or none                                                                                       | `TaskDAL.checkChoiceConsistency` (:564), called from `extractCooperativeWork` (:482)                                                                                                                         |
| The status write can run extra work inside its transaction (`inTransaction` hook)                                                 | `TaskDAL.setTaskStatus` (:670)                                                                                                                                                                               |
| App grants can't write a choice task's status directly, alone or in a bundle (`403 choice_task_status`); only `POST choice` sets it | `MobileBearerFilter.scala` (`choiceGate`), `MobileDrafts.scala` (`choiceTask`) |

### Guests (deferred sign-up, in progress)

Off by default (`mobileOAuth.guests.enabled`). See [mobile-oauth.md](mobile-oauth.md#guests-deferred-sign-up).

| What | Where |
| --- | --- |
| Client scope `guest` (`MobileScopes.parseClient`); grant scopes never include it | `app/org/maproulette/auth/mobile/MobileOAuthSettings.scala` (`MobileScopes`, `guestsEnabled`), `MobileClientRegistry.scala`, `app/controllers/MobileAdminController.scala` |
| Tables `mobile_guests`, `mobile_guest_tokens`, `mobile_guest_claim_tokens` | `conf/evolutions/default/135.sql` |
| Guest storage: create, mint and authenticate guest tokens, delete with tombstone | `app/org/maproulette/auth/mobile/guest/MobileGuestRepository.scala` |
| Guest registration (per-IP limit), guest token grant, guest authentication | `app/org/maproulette/auth/mobile/guest/MobileGuestService.scala`, `app/controllers/MobileGuestController.scala`, `MobileOAuthController.token` (`guestToken`) |
| Idempotent guest registration (G-IDEM): `POST /oauth/mobile/guest` takes an optional install id; a retry with the same id returns the same unused guest; column `mobile_guests.install_id` | `conf/evolutions/default/150.sql`, `MobileGuestRepository.scala`, `MobileGuestService.scala`, `app/controllers/MobileGuestController.scala` |
| Routes `POST /oauth/mobile/guest`, `GET /api/v2/mobile-guest/me`, `DELETE /api/v2/mobile-guest` | `conf/routes` |
| Guest bearer branch and `MobileGuestRoutes`; `GuestKey` instead of `UserKey`; field-gate entries | `app/org/maproulette/auth/mobile/MobileBearerFilter.scala` |
| Claim email (B4): `PUT /api/v2/mobile-guest/email`, sealed address, claim tokens, rate limit, pluggable mail provider (Postmark, log, none) and templates | `app/org/maproulette/provider/choice/claim/` (`ClaimMail.scala`, `GuestEmail.scala`), `app/controllers/MobileGuestEmailController.scala`, `conf/mobile-email/`, `conf/application.conf` (`mobileOAuth.guests.mail`), `conf/routes`, `MobileGuestRoutes.email` |
| Table `mobile_claims`, column `choice_pending.claim_id` | `conf/evolutions/default/138.sql` |
| Claim (B5): `POST /api/v2/mobile-claim` by link or guest secret, `GET /api/v2/mobile-claim/:id`; phone grant family with a copy of the OSM token | `app/org/maproulette/provider/choice/claim/Claims.scala`, `MobileClaimController` (in `app/controllers/MobileGuestEmailController.scala`), `MobileWriteRoutes`, `MobileReadRoutes`, `conf/routes` |
| Claim preview (B5): `POST /api/v2/mobile-claim/preview`; claim-origin CORS | `app/org/maproulette/provider/choice/claim/ClaimPreview.scala`, `MobileGuestTokenController.preview`, `MobileOAuthSettings.claimOrigin`, `MobileCorsFilter` (`claimEligible`), `conf/routes` |
| Claim publish job (B5): `MobileChoiceService.publishPending` (per-answer guards, no own lock), `waiting` while writes are off, reauth and "now on the map" emails; evolution 139 | `app/org/maproulette/provider/choice/claim/ClaimPublish.scala`, `MobileChoiceService`, `MobileClaimController` (nudge), `conf/application.conf` (`ClaimPublishModule`), `conf/evolutions/default/139.sql` |
| D13: while the field write switch is off, only the `maproulette-claim` client may claim or retry (audited `claim.writes_off`); its grants reach no other write; claims wait while that client is disabled. `POST /api/v2/mobile-claim/:id/retry` | `MobileBearerFilter.scala` (`MobileClaimRoutes`), `MobileCorsFilter.claimEligible`, `ClaimPublish.scala` (`ready`), `Claims.scala` (`retry`), `MobileClaimController.retry`, `conf/routes` |
| Reminder, expiry and purge job (B4), claim-token routes `POST /api/v2/mobile-claim/delete` and `/stop-reminders`, `PUT /api/v2/mobile-guest/reminders` | `app/org/maproulette/provider/choice/claim/GuestJobs.scala`, `app/controllers/MobileGuestEmailController.scala` (`MobileGuestTokenController`), `conf/application.conf` (`GuestJobModule`), `conf/routes`, `MobileGuestRoutes.claimToken`, `MobileFieldRoutes` |
| Table `choice_pending` | `conf/evolutions/default/137.sql` |
| Pending answers: submit (validated like a choice submission, any choice task since G1), withdraw, list; published-challenge rule while writes are off | `app/org/maproulette/provider/choice/MobileChoicePendingService.scala`, `ChoicePendingRepository.scala`, `app/org/maproulette/controllers/api/MobileChoicePendingController.scala` |
| `MobileChoiceService.validateSubmission`: parsing and payload checks shared by submit and pending answers | `app/org/maproulette/provider/choice/MobileChoiceService.scala` |
| `excludePending=true`: leave out tasks held by a pending answer or one on the map (`ChoicePendingStore.holds`, used by every held count) | `SearchParameters.scala` (`excludePending`), `TaskClusterService.scala` (`excludeStaleChoiceTasks`), `ChoicePendingRepository.scala` (`holds`) |
| Campaign results count guest answers the publish guard skipped (`skipped_count`, `skipped_reasons`) | `app/org/maproulette/provider/choice/ChoiceResults.scala` |
| `includePending=true` on `tasks/box`: `pending` and `heldKind` (`answer`/`report`/null) per task | `TaskController.scala` (`withPending`), `ChoicePendingRepository.scala` (`held`, `heldKinds`) |
| `choice/check` accepts guest tokens | `app/org/maproulette/controllers/api/MobileChoiceController.scala` |
| Routes `GET /api/v2/mobile-guest/pending`, `POST` and `DELETE /api/v2/task/:id/choice/pending` | `conf/routes` |
| Review page (H1a): `GET /api/v2/review/:token` by the live page token, `PUT`/`DELETE …/places/:id/skip` and `…/needs-visit` for app grants with `tasks:read` (open while writes are off; claim and admin origin CORS); tables `choice_review_marks`, `choice_review_places`; guest answers of a campaign with a live page stay pending past the guest's expiry and purge | `app/controllers/MobileReviewController.scala`, `app/org/maproulette/provider/choice/ReviewPage.scala`, `MobileBearerFilter.scala` (`MobileReviewRoutes`), `MobileCorsFilter`, `GuestJobs.scala` (`KeptForReview`), `conf/routes`, `conf/evolutions/default/142.sql` |
| Confirm queue (H1b): `POST /api/v2/mobile-admin/challenges/:id/queue` runs a read-only OSM stale check and queues clear guest tag answers as tasks of a disabled tag-fix challenge; `GET …/queue/export` gives them as line-by-line GeoJSON for maproulette.org; tables `choice_queues`, `choice_queue_items`; review place states `stale`, `gone` and derived `disagree`; kept guest answers purge once a place is done, gone or needs a visit | `app/controllers/MobileQueueController.scala`, `app/org/maproulette/provider/choice/ChoiceQueue.scala`, `ReviewPage.scala`, `GuestJobs.scala` (`KeptForReview`), `MobileBearerFilter.scala`, `conf/routes`, `conf/evolutions/default/143.sql` |
| On the map (H1c): hourly read-only OSM check of queued edits (`ChoiceOnMapScheduler`); guest answers OSM now carries end `on_map`, places `done` or `gone`; `GET /api/v2/mobile-guest/me` counts `on_map` as `published` and adds `byChallenge`. H1d: a queued edit whose question OSM no longer matches is stale (`choice_queue_items.stale_at`), left out of exports, its guest answers `skipped_stale` (`key_changed`); a gone place's waiting answers are `skipped_stale` (`element_gone`) | `app/org/maproulette/provider/choice/ChoiceQueue.scala`, `ChoicePendingRepository.scala`, `app/controllers/MobileGuestController.scala`, `conf/application.conf`, `conf/evolutions/default/144.sql`, `147.sql`, `148.sql` (on_map hold backfill) |

### Discovery filters

| What                                                                                                                       | Where                                                                                                                                                                               |
| -------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `cct=<list>`: filter by `challenges.cooperative_type` on task, marker and cluster searches. Generic, and proposed upstream | `app/org/maproulette/session/SearchParameters.scala` (`challengeCooperativeTypes`, `parseCooperativeTypes`), `app/org/maproulette/framework/mixins/SearchParametersMixin.scala:602` |
| `excludeStale=true`: leave out tasks in `choice_stale`, unless already checked (done or held). Fork-only                  | `SearchParameters.scala` (`excludeStale`), `app/org/maproulette/framework/service/TaskClusterService.scala:289`                                                                     |

### Ingest reporting

| What                                                                                                                                                                                        | Where                                                                                                                                                                                                                                             |
| ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `PUT /api/v2/challenge/:id/addFileTasks?lineByLine=true&report=true` returns `{created, updated, rejected:[{line, errors}]}` instead of 204. Rejected lines don't mark the challenge FAILED | `conf/v2_route/challenge.api`, `app/org/maproulette/controllers/api/ChallengeController.scala:1629,1741`; per-line errors collected in `app/org/maproulette/provider/ChallengeProvider.scala` (`createTaskFromJson`, `ChallengeProvider.message`) |

### Deployment, scripts and docs

| What                                                                                                                                                                         | Where                                                                                                                                                                                                                                                |
| ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Compose files and Caddy reverse proxy for the staging deployment                                                                                                             | `compose.mobile.yml`, `compose.production.yml`, `deploy/mobile/Caddyfile`, `.dockerignore`                                                                                                                                                           |
| One reusable field Compose file for separate stage and prod projects; each has its own PostGIS volume, backend image, OSM OAuth credentials and ingress alias                | `compose.field.yml`                                                                                                                                                                                                                                  |
| Field configuration: production OSM, public origin, 5,000-task ingest cap, seed clients for read-only native bootstrap and one admin site, and database-backed write control | `conf/mobile-field.conf`                                                                                                                                                                                                                             |
| `curl` in the image, for health checks                                                                                                                                       | `Dockerfile`                                                                                                                                                                                                                                         |
| CI image build: pushes to `feat/mobile-oauth` publish `ghcr.io/mvexel/maproulette-mobile-backend` (tags: branch, short sha, `latest`); pull requests build without pushing | `.github/workflows/fork-image.yml` |
| End-to-end OAuth smoke test against a synthetic OSM provider                                                                                                                 | `scripts/mobile-oauth-smoke.mjs`, `scripts/mobile-oauth-test-osm.mjs`                                                                                                                                                                                |
| Docs                                                                                                                                                                         | `docs/mobile-oauth.md`, `docs/mobile-admin-api.md`, `docs/mobile-staging-deploy.md`, `docs/upstream-issues.md`, this file; coordinated deployment runbook in the infra repo                                                                          |
| Tests                                                                                                                                                                        | `test/org/maproulette/auth/mobile/*Spec.scala` (including `MobileAdminSpec` and `MobileAdminRepositorySpec`), `test/org/maproulette/provider/choice/*` (including `FakeOsmServer.scala`), `test/org/maproulette/filters/HttpLoggingFilterSpec.scala` |

## Changed

These are changes to existing upstream behavior. They apply to every client,
not only mobile.

| Change                                                                                                                                                                                                         | Why                                                                                                                                                                   | Where                                                                                                  |
| -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------ |
| The cached task now carries the `completedBy`, `mappedOn` and `completedTimeSpent` that the status write just stored                                                                                           | Reads returned the previous values for up to 15 minutes ([upstream-issues §3](upstream-issues.md#3-cached-task-keeps-stale-completedbymappedon-after-a-status-write)) | `app/org/maproulette/models/dal/TaskDAL.scala`, in `setTaskStatus` (cache update after the status row) |
| `submitOsmChange` closes the changeset when building the change fails                                                                                                                                          | The changeset was left open                                                                                                                                           | `app/org/maproulette/provider/osm/ChangesetProvider.scala:132`                                         |
| The request log redacts `Authorization`, `apiKey`, `Cookie`, `Set-Cookie` and `Referer`, and logs `/oauth/mobile/*` requests without their query string                                                        | Tokens, codes and API keys appeared in logs                                                                                                                           | `app/org/maproulette/filters/HttpLoggingFilter.scala:14` (`SafeRequestLog`)                            |
| Choice payloads (type 3) are validated at task ingest. Invalid ones are rejected, and a challenge can't mix choice and other tasks                                                                             | Upstream checks only `meta.version`                                                                                                                                   | `TaskDAL.extractCooperativeWork` (:482), `checkChoiceConsistency` (:564)                               |
| `addFileTasks` gains the optional `report` parameter                                                                                                                                                           | Upstream silently drops rejected lines                                                                                                                                | See [Ingest reporting](#ingest-reporting)                                                              |
| Play's CORS filter is wrapped by `MobileCorsFilter`. Disabled mobile OAuth: unchanged. Enabled: `/oauth/mobile/token` and `/revoke` (fork routes) answer only the admin origin; everything else is as upstream | Token and revoke need no browser origin other than the admin app's; see [mobile-oauth.md](mobile-oauth.md#cors)                                                       | `app/org/maproulette/filters/Filters.scala:16`, `MobileCorsFilter.scala`                               |
| `.gitignore` also ignores Metals and Bloop editor files                                                                                                                                                        | Local editor state                                                                                                                                                    | `.gitignore`                                                                                           |
| Test database: the background connection pool also points at the test database                                                                                                                                 | The pool copied `db.default` before the test overrides were applied, so it connected to the wrong database                                                            | `test/org/maproulette/framework/util/TestDatabase.scala`                                               |
| CI: the test job waits for PostGIS to accept connections and runs the mobile specs against a second disposable database; the java-client integration job signs in with `devMode` instead of the removed `MR_SUPER_KEY` | `.github/workflows/scala.yml` |

## Disabled or restricted

With the default configuration, web sessions and API keys are unrestricted by
the mobile gate. Field deployments set `writeControlEnabled = true`; while
their database policy is off, the filter blocks routes outside the known
public reads, login, and admin-control routes for every credential type. Mobile
bearer grants are narrower than a web session in either configuration:

| Restriction                                                                                                                                                                                                                                                                               | Where                                                                                             |
| ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------- |
| Every route outside the read and write allowlists returns 403 for an app bearer token, including the mobile admin API and all admin, challenge-editing, comment, review, bundle and user-settings routes                                                                                  | `MobileBearerFilter.scala:55,84,162`                                                              |
| An admin (`mobile:admin`) token reaches only `MobileAdminRoutes`: not the app reads or writes, not `whoami`, project, delete or other challenge routes, and `addFileTasks` only with `lineByLine=true&report=true` (no `removeUnmatched`). It needs a current super-user on every request | `MobileBearerFilter.scala:25,162`                                                                 |
| The mobile admin API accepts only admin bearer grants: web sessions and API keys of super-users get 403 `mobile_admin_only`                                                                                                                                                               | `MobileAdminController.scala`                                                                     |
| The admin app can't disable its own client or remove `mobile:admin` from it (`409 self_lockout`)                                                                                                                                                                                          | `MobileAdminController.scala:140`                                                                |
| With mobile OAuth enabled, other origins get no CORS on `/api/v2/mobile-admin/*`, `/oauth/mobile/token` and `/oauth/mobile/revoke`                                                                                                                                                        | `MobileCorsFilter.scala:65`                                                                       |
| Status writes are limited to Fixed (1), Not an issue (2), Already fixed (5) and Too hard (6). Deleted, Disabled, Skipped and the review statuses are refused                                                                                                                              | `MobileWriteRoutes.permits` (`PUT $task/[1256]`)                                                  |
| Status writes and lifecycle `GET`s must have no query string and no body, so mobile can't send `requestReview`, tags or completion responses                                                                                                                                              | `MobileBearerFilter.scala` (bare-route check)                                                     |
| `refreshLock` is not allowed: mobile locks late, just before the status write                                                                                                                                                                                                             | `MobileWriteRoutes.permits`                                                                       |
| `choice/check` and `choice` accept only bearer grants. Web sessions and API keys get 403 `mobile_only`                                                                                                                                                                                    | `MobileChoiceController.scala`                                                                    |
| An `osm:tagfix` grant can't be created without the OSM token key. Without the key, edits return 503 `osm_edits_unavailable` and everything else keeps working                                                                                                                             | `MobileOAuthSettings.scala`, `MobileChoiceService.scala`                                          |
| In a field deployment, an off write policy blocks task writes and OSM choice submissions even for an existing bearer grant, and declines new `tasks:write` grants                                                                                                                         | `MobileBearerFilter.scala` (`MobileFieldRoutes`, `MobileWriteRoutes`), `MobileOAuthService.scala` |
| The field gate allows anonymous challenge and task discovery reads. It excludes `GET /task/:id/choice/check`, which performs a fresh OSM read and may record staleness                                                                                                                    | `MobileFieldRoutes.permitsWhenDisabled`                                                           |
| Each stage/prod admin site changes only its matching backend's policy. The switch starts off; a current super-user with a `mobile:admin` grant is required to change it                                                                                                                   | `MobileAdminController.scala`, `MobileWritePolicy.scala`, `conf/mobile-field.conf`                |
| Preview mode: for an app bearer grant, a draft challenge (challenge or project disabled) and its tasks answer a bare 404, as an unknown id does, except to super-users (organizers once `mobile:organize` exists). Nobody may start, skip, submit a choice for or set the status of a draft's task (403 `challenge_draft`), or bundle, lock or comment on one; release, bundle delete, unbundle and bundle unlock stay open. Web sessions and API keys are unchanged | `MobileDrafts.scala`, `MobileBearerFilter.scala` (`draftGate`) |

## Proposed upstream

[upstream-issues.md](upstream-issues.md) lists upstream behavior that we
documented but did not change: §1–9, covering issues such as Deleted and
Disabled escalation, same-status takeover, status writes without a lock, the
`requestReview=false` bypass, fix/apply trust and ordering, and Swagger gaps.
Its §10 lists the parts of this fork proposed for upstream. Nothing has been
filed yet; filing is Martijn's step.

### Upstream branch

Branch
[`claude/project-thread-rsciiv`](https://github.com/mvexel/maproulette-mobile-backend/tree/claude/project-thread-rsciiv)
(2026-10-18) is cut from upstream `main` at `d9989212` and holds the generic
changes below, one commit each, plus the OAuth provider under neutral names.
It has no guests, choice tasks, admin, deployment config or branding. Its full
test suite (834 tests) and lint pass against PostGIS. To offer it upstream,
push it to a fork of `maproulette/maproulette-backend` and open a PR (or one PR
per commit). The provider's upstream-facing description is
[`docs/oauth-provider.md`](https://github.com/mvexel/maproulette-mobile-backend/blob/claude/project-thread-rsciiv/docs/oauth-provider.md)
on that branch.

| Change | Where |
| ------ | ----- |
| The cached task carries the `completedBy`, `mappedOn` and `completedTimeSpent` the status write stored | `TaskDAL.setTaskStatus`, `TaskServiceSpec` |
| `submitOsmChange` closes the changeset when building the change fails | `ChangesetProvider.scala` |
| The request header trace log redacts `Authorization`, `apiKey`, `Cookie`, `Set-Cookie` and `Referer`; `/oauth/*` requests log method and path only | `HttpLoggingFilter.scala` (`SafeRequestLog`) |
| `TestDatabase` also points the background pool at the test database | `test/.../TestDatabase.scala` |
| CI: PostGIS health check; integration job uses `devMode` instead of `MR_SUPER_KEY`; Metals/Bloop in `.gitignore` | `.github/workflows/scala.yml`, `.gitignore` |
| `cct` search filter on `challenges.cooperative_type` | `SearchParameters.scala`, `SearchParametersMixin.scala` |
| `addFileTasks?lineByLine=true&report=true` returns created/updated counts and rejected lines; a rejected line does not mark the challenge FAILED | `ChallengeController.scala`, `ChallengeProvider.scala`, `conf/v2_route/challenge.api` |
| Machine-readable `error` codes next to `status`/`message`; lock responses include `expiresAt` | `MPExceptions.scala` (`ErrorCodes`, `ErrorCoded`), `MPExceptionUtil.body`, `Locking.resolveLockExpiry`, `TaskController.scala`, `TaskBundleController.scala` |
| `PUT /task/:id/changeset?changesetId=` links a named changeset after ownership and timing checks, behind `maproulette.tasks.changesets.linkEnabled` (default `true`) | `TaskDAL.linkOSMChangeSet`, `TaskController.matchToOSMChangeSet`, `conf/v2_route/task.api`, `Config.scala` |
| OAuth 2.0 provider for phone apps and desktop editors (JOSM), off by default: PKCE, loopback redirects, rotating refresh tokens, scopes `tasks:read`, `tasks:write`, `tasks:edit` | `app/org/maproulette/auth/oauth/`, `OAuthProviderController.scala`, `conf/routes`, `oauthProvider` in `application.conf`, evolution `130.sql`, `docs/oauth-provider.md` |

The provider is renamed for upstream. If upstream accepts it, the fork and its
clients must move to the new names:

| Fork | Upstream branch |
| ---- | --------------- |
| `/oauth/mobile/*` | `/oauth/*` |
| `mobileOAuth { ... }` | `oauthProvider { ... }` |
| `mobile_oauth_*` tables (fork evolution 129) | `oauth_*` tables (evolution 130) |
| `org.maproulette.auth.mobile`, `Mobile*` classes | `org.maproulette.auth.oauth`, `OAuth*` classes |
| `MobileOAuthController` | `OAuthProviderController` |
| cookie `mr_mobile_oauth` | cookie `mr_oauth` (path `/oauth`) |

Left out of the provider: guest sign-in and claim, the admin and `osm:tagfix`
scopes with stored OSM tokens, choice-task routes in the bearer allowlists,
admin-managed clients (the branch reads clients from config only), the
`allowTaskWrites` switch, and `MobileCorsFilter`.

Left out on purpose elsewhere: choice tasks (`meta.type = 3`, proposed in §10
but the core of the apps, with its validator and fork tables), the
`inTransaction` hook in `setTaskStatus` (used only by choice submissions),
`excludeStale`, `excludePending` and `includePending`, the draft gate in
`TaskBundleController`, the `curl` added to the `Dockerfile`, and all other
fork evolutions (130–132, 134–151).

The earlier upstream-based branches from #36 and #37 (`52dbe965`,
`7d04c452`, `a2919099`) are superseded by this branch.
