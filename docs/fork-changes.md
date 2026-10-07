# Fork changes compared to upstream

This fork (`mvexel/maproulette-mobile-backend`, branch `feat/mobile-oauth`) is
based on upstream
[`maproulette/maproulette-backend`](https://github.com/maproulette/maproulette-backend)
at commit
[`b9b2e69b`](https://github.com/maproulette/maproulette-backend/commit/b9b2e69b0115cbfb7a1f997a29dfc3b2ca32512a)
(2026-09-30, "Reject unscoped requests to task cluster endpoint (#1284)").

Every change is additive and opt-in. With `mobileOAuth.enabled = false` (the
default in `conf/application.conf`), web sessions, API keys and all existing
routes behave as upstream, apart from the general fixes listed under
[Changed](#changed). No upstream file was removed.

Relative to the base: 34 files added (16 application, 12 test, 6 deployment and
docs) and 22 changed. To reproduce the comparison:

```sh
git -C <upstream checkout> archive b9b2e69b | tar -x -C /tmp/up
diff -r /tmp/up . --exclude=.git
```

Background and design: [mobile-oauth.md](mobile-oauth.md) (configuration and
security boundaries), [mobile-staging-deploy.md](mobile-staging-deploy.md)
(deployment) and [upstream-issues.md](upstream-issues.md) (upstream behavior we
found and did not change). The client side is the
[MapRoulette mobile SDK](https://github.com/mvexel/maproulette-mobile-sdk).

## Added

### Mobile OAuth provider (`/oauth/mobile/*`)

The fork adds an authorization-code flow with S256 PKCE for approved native apps,
with no client secrets. A user signs in through OSM. The backend issues its own
short-lived access tokens and rotating refresh tokens, scoped per grant.

| What | Where |
| --- | --- |
| Routes: `authorize`, `callback`, `consent`, `token`, `revoke`, `me` | `conf/routes:5-10` → `app/controllers/MobileOAuthController.scala` (`authorize` :120, `callback` :166, `consent` :280, `token` :333, `revoke` :352, `me` :374) |
| Grant, token and code lifecycle (hashing, rotation, replay revocation) | `app/org/maproulette/auth/mobile/MobileOAuthService.scala:36` |
| Storage | `app/org/maproulette/auth/mobile/MobileOAuthRepository.scala`, models in `MobileOAuthModels.scala` |
| Client registry and scopes `tasks:read`, `tasks:write` and `osm:tagfix` (strict parsing; `osm:tagfix` requires `tasks:write`) | `app/org/maproulette/auth/mobile/MobileOAuthSettings.scala:7,18,43` |
| OSM identity lookup, and provisioning of a MapRoulette user on first sign-in | `MobileOSMIdentity.scala:11`, `MobileUserProvisioner.scala:13` |
| Configuration block (off by default) and its dispatcher | `conf/application.conf` (`mobileOAuth { … }`, `mobile-oauth-dispatcher`) |
| Staging client registrations (`maproulette-android-example`, `maproulette-ios-example`) | `conf/mobile-staging.conf` |
| Tables `mobile_oauth_interactions`, `_families`, `_codes`, `_access_tokens`, `_refresh_tokens` | `conf/evolutions/default/129.sql` |
| Dependency `scala-oauth2-core` 1.6.0 | `build.sbt` |

### Bearer gate for mobile grants

`MobileBearerFilter` runs after the existing filters. A request with
`Authorization: Bearer …` is checked against an exact route allowlist for the
grant's scopes. If it passes, the filter attaches the user to the request, and
`SessionManager` uses that user instead of the session or API key. Mobile
credentials never fall back to the other authentication paths. Requests
without a bearer token pass through unchanged.

| What | Where |
| --- | --- |
| Filter registration | `app/org/maproulette/filters/Filters.scala` |
| Read allowlist (`tasks:read`): challenge search, challenge, tags, tasks, task, `choice/check`, task and marker boxes, `me` | `app/org/maproulette/auth/mobile/MobileBearerFilter.scala:21` (`MobileReadRoutes`) |
| Write allowlist (`tasks:write`): `GET` `start` and `release`; `POST` `skip` and `choice`; `PUT` status `1`, `2`, `5`, `6`. No query string and no body, except the choice JSON (at most 2048 bytes) | `MobileBearerFilter.scala:50` (`MobileWriteRoutes`) |
| Filter logic and error responses (`insufficient_scope`, `invalid_request`, `mobile_only`) | `MobileBearerFilter.scala:81` |
| The bearer user takes precedence in `userAwareRequest` | `app/org/maproulette/session/SessionManager.scala:239` |

### Multiple-choice tasks (`cooperativeWork.meta.type = 3`)

A new task kind: one OSM element, several questions, and answers applied as one
changeset by the backend using the user's OSM token. The full spec is in the SDK
repo:
[mobile-choice-challenges.md](https://github.com/mvexel/maproulette-mobile-sdk/blob/main/docs/design/mobile-choice-challenges.md).

| What | Where |
| --- | --- |
| Payload model and validation (also run at ingest) | `app/org/maproulette/provider/choice/ChoiceWork.scala:40,53` |
| `GET /api/v2/task/:id/choice/check`: fresh OSM read and all-or-nothing eligibility | `conf/v2_route/task.api` → `app/org/maproulette/controllers/api/MobileChoiceController.scala:38` → `MobileChoiceService.check` (`provider/choice/MobileChoiceService.scala:188`); rule in `staleness` (:123) |
| `POST /api/v2/task/:id/choice`: lock check, idempotency, re-check, one OSM changeset, status and changeset id in one transaction | `MobileChoiceController.scala:45` → `MobileChoiceService.submit` (:418) |
| OSM reads and uploads for choice tasks | `app/org/maproulette/provider/choice/ChoiceOsmClient.scala:28` |
| The user's OSM token (`read_prefs write_api`), AES-256-GCM encrypted per grant family, key from `MR_MOBILE_OSM_TOKEN_KEY` | `app/org/maproulette/auth/mobile/MobileOsmTokenCipher.scala:18`, `conf/application.conf` (`osmTokenKey`) |
| Tables `mobile_osm_tokens`, `mobile_choice_submissions` and `choice_stale`, plus OSM-token columns on `mobile_oauth_interactions` | `conf/evolutions/default/130.sql` |
| Stale tasks are recorded in `choice_stale` (insert-only; no task status is written) | `MobileChoiceService.markStale` (:149) |
| A replaced payload clears its `choice_stale` row | `app/org/maproulette/models/dal/TaskDAL.scala:428` |
| A challenge holds only choice tasks or none | `TaskDAL.checkChoiceConsistency` (:564), called from `extractCooperativeWork` (:482) |
| The status write can run extra work inside its transaction (`inTransaction` hook) | `TaskDAL.setTaskStatus` (:670) |

### Discovery filters

| What | Where |
| --- | --- |
| `cct=<list>`: filter by `challenges.cooperative_type` on task, marker and cluster searches. Generic, and proposed upstream | `app/org/maproulette/session/SearchParameters.scala` (`challengeCooperativeTypes`, `parseCooperativeTypes`), `app/org/maproulette/framework/mixins/SearchParametersMixin.scala:602` |
| `excludeStale=true`: leave out tasks in `choice_stale`. Fork-only | `SearchParameters.scala` (`excludeStale`), `app/org/maproulette/framework/service/TaskClusterService.scala:289` |

### Ingest reporting

| What | Where |
| --- | --- |
| `PUT /api/v2/challenge/:id/addFileTasks?lineByLine=true&report=true` returns `{created, updated, rejected:[{line, errors}]}` instead of 204. Rejected lines don't mark the challenge FAILED | `conf/v2_route/challenge.api`, `app/org/maproulette/controllers/api/ChallengeController.scala:1629,1741`; per-line errors collected in `app/org/maproulette/provider/ChallengeProvider.scala` (`createTaskFromJson`, `ChallengeProvider.message`) |

### Deployment, scripts and docs

| What | Where |
| --- | --- |
| Compose files and Caddy reverse proxy for the staging deployment | `compose.mobile.yml`, `compose.production.yml`, `deploy/mobile/Caddyfile`, `.dockerignore` |
| `curl` in the image, for health checks | `Dockerfile` |
| End-to-end OAuth smoke test against a synthetic OSM provider | `scripts/mobile-oauth-smoke.mjs`, `scripts/mobile-oauth-test-osm.mjs` |
| Docs | `docs/mobile-oauth.md`, `docs/mobile-staging-deploy.md`, `docs/upstream-issues.md`, this file |
| Tests | `test/org/maproulette/auth/mobile/*Spec.scala`, `test/org/maproulette/provider/choice/*` (including `FakeOsmServer.scala`), `test/org/maproulette/filters/HttpLoggingFilterSpec.scala` |

## Changed

These are changes to existing upstream behavior. They apply to every client,
not only mobile.

| Change | Why | Where |
| --- | --- | --- |
| The cached task now carries the `completedBy`, `mappedOn` and `completedTimeSpent` that the status write just stored | Reads returned the previous values for up to 15 minutes ([upstream-issues §3](upstream-issues.md#3-cached-task-keeps-stale-completedbymappedon-after-a-status-write)) | `app/org/maproulette/models/dal/TaskDAL.scala`, in `setTaskStatus` (cache update after the status row) |
| `submitOsmChange` closes the changeset when building the change fails | The changeset was left open | `app/org/maproulette/provider/osm/ChangesetProvider.scala:132` |
| The request log redacts `Authorization`, `apiKey`, `Cookie`, `Set-Cookie` and `Referer`, and logs `/oauth/mobile/*` requests without their query string | Tokens, codes and API keys appeared in logs | `app/org/maproulette/filters/HttpLoggingFilter.scala:14` (`SafeRequestLog`) |
| Choice payloads (type 3) are validated at task ingest. Invalid ones are rejected, and a challenge can't mix choice and other tasks | Upstream checks only `meta.version` | `TaskDAL.extractCooperativeWork` (:482), `checkChoiceConsistency` (:564) |
| `addFileTasks` gains the optional `report` parameter | Upstream silently drops rejected lines | See [Ingest reporting](#ingest-reporting) |
| Test database: the background connection pool also points at the test database | The pool copied `db.default` before the test overrides were applied, so it connected to the wrong database | `test/org/maproulette/framework/util/TestDatabase.scala` |

## Disabled or restricted

Nothing is disabled for web sessions or API keys. Mobile bearer grants are
deliberately narrower than a web session:

| Restriction | Where |
| --- | --- |
| Every route outside the read and write allowlists returns 403 for a bearer token, including all admin, challenge-editing, comment, review, bundle and user-settings routes | `MobileBearerFilter.scala:21,50,81` |
| Status writes are limited to Fixed (1), Not an issue (2), Already fixed (5) and Too hard (6). Deleted, Disabled, Skipped and the review statuses are refused | `MobileWriteRoutes.permits` (`PUT $task/[1256]`) |
| Status writes and lifecycle `GET`s must have no query string and no body, so mobile can't send `requestReview`, tags or completion responses | `MobileBearerFilter.scala` (bare-route check) |
| `refreshLock` is not allowed: mobile locks late, just before the status write | `MobileWriteRoutes.permits` |
| `choice/check` and `choice` accept only bearer grants. Web sessions and API keys get 403 `mobile_only` | `MobileChoiceController.scala` |
| An `osm:tagfix` grant can't be created without the OSM token key. Without the key, edits return 503 `osm_edits_unavailable` and everything else keeps working | `MobileOAuthSettings.scala`, `MobileChoiceService.scala` |

## Proposed upstream

[upstream-issues.md](upstream-issues.md) lists upstream behavior that we
documented but did not change: §1–9, covering issues such as Deleted and
Disabled escalation, same-status takeover, status writes without a lock, the
`requestReview=false` bypass, fix/apply trust and ordering, and Swagger gaps.
Its §10 lists the parts of this fork proposed for upstream: `meta.type = 3`,
the `cct` filter and `addFileTasks?report=true`. Nothing has been filed yet.
