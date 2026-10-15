# Mobile OAuth

This checkout implements an **opt-in** OAuth provider for approved native apps.
It is disabled by default. The MapRoulette mobile SDK needs this patched
backend, deployed with `mobileOAuth.enabled = true`, for its browser sign-in
flow. The SDK's public reads and per-user personal API-key option work with
the existing MapRoulette API. Configuring an OSM OAuth application or a native
client ID alone does not add these routes to an unpatched backend. Existing
web login and personal `apiKey` clients continue using their existing routes
and credentials.

## Enable a local or staging instance

Apply database evolutions through the normal backend setup. Evolution 129 adds separate interaction, authorization-code, grant-family and token tables; it does not replace existing user credentials. Evolution 130 adds the encrypted OSM token table for `osm:tagfix` grants and the choice-task submission and stale tables. Evolution 131 adds the `mobile_oauth_clients` table (see [Clients](#clients)) and the `mobile_admin_audit` log.

Register a **backend callback URL** with the existing OSM OAuth application, then configure the provider and each approved public client:

```hocon
mobileOAuth {
  enabled = true
  callbackUri = "https://backend.example/oauth/mobile/callback"
  clients = [{
    id = "example-mobile"
    name = "Example Mobile"
    redirectUris = ["org.example.mobile:/oauth/callback"]
    # Optional; defaults to ["tasks:read"]. Add "tasks:write" to let this app
    # request task lifecycle writes, and "osm:tagfix" (needs tasks:write and
    # osmTokenKey) to let it apply choice-task answers to OSM.
    scopes = ["tasks:read", "tasks:write"]
  }]
  # 32 random bytes, base64, e.g. `openssl rand -base64 32`. Only needed for osm:tagfix.
  osmTokenKey = ${?MR_MOBILE_OSM_TOKEN_KEY}
  # Optional: the admin web app's origin, for CORS (see "CORS" below).
  adminOrigin = ${?MR_MOBILE_ADMIN_ORIGIN}
}
```

The OSM callback and the app callback are different URLs. The app callback must match an entry exactly, including its path. HTTPS app links, reverse-domain custom schemes and, for desktop apps, HTTP loopback callbacks on an IP literal with an explicit port (`http://127.0.0.1:8111/oauth_authorization`, RFC 8252 §7.3; `localhost` is refused) are accepted; query strings and fragments are not accepted in registered callbacks. There is no dynamic registration endpoint or public-client secret. Each host app owns its callback registration and operating-system integration.

For loopback development only, `allowInsecureLoopback = true` permits an HTTP backend callback on localhost. Production callbacks require HTTPS. The existing OSM consumer configuration supplies the server-side OSM client credentials. A login asks OSM for `read_prefs` only, unless the app requests `osm:tagfix` (see [Choice tasks](#choice-tasks-osmtagfix)); then it asks for `read_prefs write_api`, and the OSM application must allow `write_api`.

Default lifetimes are 10 minutes for a browser interaction, 2 minutes for an authorization code, 15 minutes for an access token, and 30 days for each refresh token. `accessSeconds` and `refreshSeconds` are configurable within the bounds enforced by `MobileOAuthSettings`.

## Endpoints

| Endpoint | Purpose |
| --- | --- |
| `GET /oauth/mobile/authorize` | Start browser login with `response_type=code`, `client_id`, exact `redirect_uri`, `scope` (`tasks:read`, `tasks:read tasks:write`, `tasks:read tasks:write osm:tagfix`, `tasks:read tasks:write tasks:edit` or `mobile:admin`), `state`, `code_challenge` and `code_challenge_method=S256`. |
| `GET /oauth/mobile/callback` | Server callback from OSM. The browser interaction must match persisted state and its separate HTTP-only cookie. |
| `POST /oauth/mobile/consent` | Browser consent form with a transaction-bound CSRF value. Approval redirects to the app with only a short-lived code and its original state; denial returns `access_denied`. |
| `POST /oauth/mobile/token` | Form-encoded authorization-code exchange or refresh. |
| `POST /oauth/mobile/revoke` | Form-encoded `client_id` and `token`; revokes that token's app grant family. Unknown tokens return success without disclosing whether they exist. |
| `GET /oauth/mobile/me` | Bearer-authenticated identity: `id`, `osmId`, `displayName`, `scope` (the grant's scope string). No API key or OSM credential. |

Authorization-code exchange fields:

```text
grant_type=authorization_code
client_id=example-mobile
redirect_uri=org.example.mobile:/oauth/callback
code=<authorization code>
code_verifier=<original PKCE verifier>
```

Refresh fields are `grant_type=refresh_token`, `client_id` and `refresh_token`. The optional refresh `scope` must equal the grant's scope; a refresh can never widen or narrow it. Token responses contain `access_token`, `token_type=Bearer`, `expires_in`, `refresh_token` and `scope`. Password, client-credentials and implicit grants are unsupported.

Use an established native OAuth client to generate and retain state/PKCE, open the system browser and validate the returned callback. This backend implementation does not itself add native sign-in or secure token storage to the SDK.

## Clients

Approved clients live in the `mobile_oauth_clients` table (evolution 131). Each backend process
seeds it from `mobileOAuth.clients` the first time it needs a client (not at startup), then
reads it with a 10-second cache. A config client whose name is empty or longer than 200
characters, or that has more than 10 redirects, stops startup. The [admin API](mobile-admin-api.md) edits it. Precedence, per client id:

1. A config client without a row is inserted (`created_by` NULL).
2. A row that came from config and was never edited through the admin API (`created_by` and
   `updated_by` NULL) follows config on every start: name, redirects, scopes and `enabled`
   (an optional config key, default `true`).
3. A row created or edited through the admin API is never changed by config again. Seeding
   logs the ids whose config entry was not applied, as `config entry not applied`.
4. A config-seeded, never-edited row whose id is no longer in config is disabled, not deleted,
   just as removing a client from config cut it off before this table existed.

A **disabled** client is treated like an unknown one for authorization, code exchange, refresh
(`401 invalid_client`) and bearer authentication (`401 invalid_token`). Its grant families are
kept unless the admin also revokes them, and revocation (`/oauth/mobile/revoke`) still works for
it. Enabling it again restores its grants.

## Scope and credential lifecycle

Scopes are a space-separated set. Every app grant includes `tasks:read`; `tasks:write` is optional and only granted to clients whose `scopes` configuration lists it. `osm:tagfix` is optional too, valid only together with `tasks:write`, and only granted to clients configured for it; new grants also need a valid `osmTokenKey`. Unknown, duplicate, write-only or `osm:tagfix`-without-`tasks:write` requests fail with `invalid_scope`. `tasks:edit` (see [Editor scope](#editor-scope-tasksedit)) is likewise valid only together with `tasks:write`, never with `osm:tagfix`, and only for clients configured for it. Stored and returned scope strings use the canonical order `tasks:read tasks:write tasks:edit osm:tagfix`. `mobile:admin` stands alone (see [Admin scope](#admin-scope-mobileadmin)); combining it with any other scope is `invalid_scope`.

`tasks:read` permits the SDK's challenge discovery/detail/tags, task listing/detail, spatial queries, read-only marker query, the choice check (`GET /api/v2/task/:id/choice/check`, no query or body) and the new identity endpoint. The HTTP method **and exact route pattern** must be allowlisted in `MobileReadRoutes`. Legacy `whoami`, task-start/release and mutation routes are excluded. The user's existing permissions still apply. The marker query is a read-only PUT; not every GET is read-only.

`tasks:write` additionally permits exactly these task lifecycle routes (`MobileWriteRoutes`), acting as the signed-in MapRoulette user:

| Route | Purpose |
| --- | --- |
| `GET /api/v2/task/:id/start` | Lock the task, immediately before resolving it |
| `GET /api/v2/task/:id/release` | Release the caller's lock |
| `POST /api/v2/task/:id/skip` | Skip: count the skip, release the lock, keep the status |
| `PUT /api/v2/task/:id/(1\|2\|5\|6)` | Fixed, Not an issue, Already fixed, Too hard |
| `POST /api/v2/task/:id/choice` | Resolve a choice task (see [Choice tasks](#choice-tasks-osmtagfix)) |

Mobile clients lock late: they call `start` only when the user commits a
resolution, then write the status (which releases the lock). Skip needs no
lock. `refreshLock` is therefore not allowed for bearer tokens.

These write requests must have **no query string and no body** (`400 invalid_request` otherwise). The one exception is `POST /api/v2/task/:id/choice`: no query string, `Content-Type: application/json`, a `Content-Length` of 1 to 2048 bytes and no `Transfer-Encoding`. That excludes `requestReview` (the user's review setting applies), task `tags` and `completionResponses`. All other mutations, including statuses 0, 3, 4, 7, 8 and 9, comments, tags, bundles, review routes, `refreshLock`, unlock requests and anything that edits OpenStreetMap, return `403 insufficient_scope` for every bearer token. A `tasks:read`-only token on a lifecycle route also gets `403 insufficient_scope`.

### Editor scope (`tasks:edit`)

Desktop editors such as the JOSM MapRoulette plugin hold a task for a whole editing session and record more than a status. `tasks:edit` adds these requests (`MobileEditorRoutes`); anything else stays `403 insufficient_scope`:

| Route | Query keys | Body |
| --- | --- | --- |
| `PUT /api/v2/task/:id/(1\|2\|5\|6)` | `requestReview` (`true` only: `false` would skip a mandatory review, see [upstream-issues.md](upstream-issues.md) §5), `tags` | optional JSON `completionResponses` |
| `POST /api/v2/task/:id/comment` | `actionId` (digits) | required JSON `{"comment": "..."}` |
| `GET /api/v2/task/:id/refreshLock` | none | none |
| `PUT /api/v2/task/:id/changeset` | `changesetId` (digits) | none |
| `POST /api/v2/taskBundle` (create) | none | required JSON `{"name", "primaryId", "taskIds"}` |
| `POST /api/v2/taskBundle/:id` (read), `DELETE /api/v2/taskBundle/:id` | none | none |
| `POST /api/v2/taskBundle/:id/update`, `POST /api/v2/taskBundle/:id/unbundle`, `POST /api/v2/task/bundle/unlock` | `taskIds` (digits, required) | none |
| `PUT /api/v2/task/:id/lockBundle` | `taskIds` (digits, optional) | none |
| `PUT /api/v2/taskBundle/:id/(1\|2\|5\|6)` | `primaryId` (digits, required), `requestReview` (`true` only), `tags` | optional JSON `completionResponses` |
| `POST /api/v2/taskBundle/:id/comment` | `actionId` (digits) | required JSON `{"comment": "..."}` |

Each query key may appear once, except `taskIds`, which repeats once per task (`taskIds=1&taskIds=2`, at most 50, the web UI's bundle limit); each value is at most 1024 characters. Bundle review and meta-review stay closed. A body needs `Content-Type: application/json`, a `Content-Length` of 1 to 16384 bytes and no `Transfer-Encoding`. A request outside these shapes is `400 invalid_request`, before the token is checked. A request in these shapes from a grant without `tasks:edit` is `403 insufficient_scope`; a bare status write is still a `tasks:write` lifecycle write. The write switches (`allowTaskWrites`, the field write policy) and preview mode apply as for other writes. The consent page lists the extra permissions.

Existing `tasks:read` grants keep working unchanged for reads. They are never upgraded: the user signs in again and approves the write permission, which creates a new grant. The consent page names the write permission only when it is requested. If an operator removes `tasks:write` from a client's configuration, that client's existing write grants stop authenticating (`401 invalid_token`) until the user signs in again.

Send the access token only in `Authorization: Bearer ...`. Do not combine it with a personal API key or a legacy authenticated session. Invalid mobile credentials never fall back to either. Disabling the provider restores legacy handling; the new endpoints return 404.

Codes are consumed atomically with token issuance. Refresh tokens rotate atomically; reuse revokes the family, including its current access tokens. Apps must serialize refresh and replace saved credentials atomically. A lost refresh response can require fresh login; do not retry an old refresh token as if rotation were idempotent. Revocation affects this grant family, not another app's grant or the user's personal API key.

Only credential hashes are stored in the new tables. Upstream OSM credentials are never returned to apps. They are kept only for `osm:tagfix` grants, AES-256-GCM encrypted under `osmTokenKey` with the user id as associated data, in `mobile_osm_tokens` keyed by grant family (evolution 130). Refresh rotation keeps that row; revoking the family or replaying a refresh token deletes it, and so does an OSM 401 (the MapRoulette grant stays). `users.oauth_token` is never read or written by the mobile flow. Existing users' web-session tokens and API keys are preserved during mobile login.

## Admin scope (`mobile:admin`)

`mobile:admin` is for the admin web app. It is a scope on its own: an admin grant has no
`tasks:*` scopes, so it never reaches the app read routes (including `choice/check`, which
records stale tasks) or the app write routes. App grants never reach admin routes. Only a client
configured with `scopes = ["mobile:admin"]` can request it.

- **Super-users only.** Only a MapRoulette super-user (`Permission.isSuperUser`) can be granted
  it. A user who isn't one goes back to the app at the OSM callback, before any consent page,
  with `error=access_denied`, `error_description=MapRoulette super-user required` and the app's
  `state`. The check is repeated when consent is approved, at code exchange and at every refresh
  (`invalid_grant`).
- **Every request.** The bearer filter checks super-user status again on every request
  (`403 admin_required`), so a demotion through MapRoulette takes effect at once. MapRoulette
  keeps super-user ids in memory: a change made directly in SQL counts only after a restart.
- **Routes.** Only the `MobileAdminRoutes` allowlist: `/api/v2/mobile-admin/...`,
  `/oauth/mobile/me`, and the exact stock routes the challenge creator needs. See
  [mobile-admin-api.md](mobile-admin-api.md). The consent page says the app administers
  MapRoulette as a super-user, and that every change is recorded in an audit log.

The staging registration is the public web client `maproulette-mobile-admin` with redirect
`https://admin.mr-dev.osm.lol/callback` and `scopes = ["mobile:admin"]`. It is not
`tasks:read mobile:admin`: with `tasks:read`, the admin token would also open the app read
routes, and the reads the creator needs are already in the admin allowlist.

## CORS

Upstream sets `play.filters.cors.allowedOrigins = null` (`conf/application.conf`). In Play, that
means **every origin**, with credentials (`Access-Control-Allow-Credentials: true`), on every
path: Play's CORS filter reflects any `Origin`. The fork keeps that unchanged everywhere except
below.

`MobileCorsFilter` wraps Play's filter. With mobile OAuth enabled and `mobileOAuth.adminOrigin`
(`MR_MOBILE_ADMIN_ORIGIN`) set:

- `/api/v2/mobile-admin/...`, `/oauth/mobile/token` and `/oauth/mobile/revoke` answer CORS for
  the admin origin only, without credentials. Other origins get no CORS headers (and 403 on a
  preflight). This **narrows** token and revoke, which Play's filter used to open to every
  origin. Native apps send no `Origin`, so they are not affected.
- On the stock routes in the admin allowlist (and `/oauth/mobile/me`), the admin origin gets the
  same credential-free answer. Every other origin still goes to Play's filter, as upstream.
- Preflights allow the requested method only if the route allows it, the headers
  `Authorization`, `Content-Type` and `Accept`, and a 10-minute cache.

Without `adminOrigin`, admin paths, token and revoke get no CORS at all. The value must be exactly
`https://host[:port]` in lowercase (or `http://` on loopback with `allowInsecureLoopback`);
anything else stops startup.

With guests enabled, the claim page's origin (`mobileOAuth.guests.mail.claimOrigin`,
`MR_CLAIM_ORIGIN`, default `https://streettally.osm.lol`, same format rules) gets the same
credential-free answer on what the claim page calls: `POST` on `/oauth/mobile/token`,
`/oauth/mobile/revoke`, `/api/v2/mobile-claim`, `/api/v2/mobile-claim/<id>/retry`,
`/api/v2/mobile-claim/preview`, `/delete` and `/stop-reminders`, and `GET` on `/oauth/mobile/me` and `/api/v2/mobile-claim/<id>`. Nothing else.
`/api/v2/mobile-claim...` answers no other origin.

## Choice tasks (`osm:tagfix`)

A choice task has `cooperativeWork.meta = {"version": 2, "type": 3, "choiceVersion": 1}`: one OSM
element, a `match` identity guard, 1 to 8 questions that each guard their keys with `expect`, and
up to 4 outcomes (status 2 or 6, or one `delete: true` for a node). The payload format and every
validation rule are in the SDK's `docs/mobile-choice-challenges.md` §2 (lengths in Unicode code
points, JSON `null` = absent for optional fields, at most 16 KiB). The backend validates it when
tasks are added; invalid lines are rejected. A challenge holds either only choice tasks or none.

**Token key.** `osm:tagfix` needs `mobileOAuth.osmTokenKey` (`MR_MOBILE_OSM_TOKEN_KEY`): standard
base64 of exactly 32 random bytes, for example the output of `openssl rand -base64 32`. Unset,
empty or malformed are treated alike: new sign-ins asking for `osm:tagfix` get `invalid_scope`,
choice edits answer `503 osm_edits_unavailable`, and everything else (startup, existing grants,
reads, lifecycle writes, non-editing choice outcomes) keeps working. A malformed key is logged as
an error, without its value.

**Ingest.** `PUT /api/v2/challenge/:id/addFileTasks?lineByLine=true` as usual. With `&report=true`
it returns `200 {"created": n, "updated": n, "rejected": [{"line": 1, "errors": ["..."]}]}`
instead of 204, and a rejected line does not mark the challenge FAILED. `report=true` without
`lineByLine`, or over the task cap, is a 400. Without `report` nothing changes.

**Status only through `choice`.** App grants (`tasks:write` and `tasks:edit`) can't set a choice
task's status directly: `PUT /api/v2/task/:id/{1,2,5,6}` answers `403 {"error":
"choice_task_status"}`, with or without editor extras. `POST /task/:id/choice` sets it, after the
OSM edit and with submission dedupe. Start, release, skip, comments and `refreshLock` stay open.

**Discovery.** `cct` (comma-separated `challenges.cooperative_type` values) filters `tasks/box`,
`markers/box`, `taskCluster` and `tasksInCluster`; a non-integer gives 400. The separate,
fork-only `excludeStale=true` leaves out tasks recorded in `choice_stale`. Mobile sends
`cct=3&excludeStale=true`.

**Eligibility** is all-or-nothing: the element exists and is visible, `match` holds and **every**
question's `expect` holds. Otherwise the task is stale (`element_gone`, `match_failed` or
`key_changed`) and is recorded in `choice_stale` (insert-only; a re-upload that replaces the
payload deletes the row). No task status is ever written for staleness, because nobody resolved
the task.

**Check.** `GET /api/v2/task/:id/choice/check` (mobile bearer, `tasks:read`, no query or body)
reads the element from OSM without any cache (results reused for 60 s per task) and returns
`{"eligible": true, "deleteAllowed": bool, "elementVersion": n}` or
`{"eligible": false, "reason": "...", "detail": [...]}`. `detail` lists the expected and current
values, for diagnostics only. A task already in `choice_stale` is answered from that row without
reading OSM. An OSM failure (or an HTML 404, which means a proxy or a wrong `MR_OSM_SERVER`) gives
`502 osm_unavailable` and records nothing.

*Deliberate side effect:* this GET inserts into `choice_stale` when it observes a stale element.
It never changes a task's status, lock or history; the row records what OSM showed, is
idempotent, and only hides the task from `excludeStale` discovery.

**Submit.** `POST /api/v2/task/:id/choice` (mobile bearer, `tasks:write`; web sessions and API keys
get `403 mobile_only`). Body: exactly `{"answers": {"<question>": "<option>", ...}}` (1 to 8) or
`{"outcome": "<id>"}` (a declared outcome or a built-in: `too-hard`, `not-here`, `moved`; plus
`"delete": true` only on the delete outcome). Answers and deletes need `osm:tagfix`; outcomes
without deletion do not touch OSM.

`moved` needs `"location": {"lat": n, "lon": n, "source": "tap" | "here"}` (exactly those keys,
rounded to 6 decimals); any other submission with a location is `400`. The spot must be within
500 m of the task's point (`ST_DistanceSphere`; a task without a point measures 0), else
`422 {"error": "moved_too_far", "maxM": 500}`. `not-here` and `moved` are reports: status 6, no
OSM edit, and the stored result carries `report` (`outcome`, `location`, `distanceM`). Exports add
`outcome`, `outcome_label`, `reported_lat`, `reported_lon`, `reported_source`,
`reported_distance_m` and `report_count`; GeoJSON adds a `reported_position` point per moved
task. Challenges can't declare these ids themselves.

1. A completed identical submission (same task, user, payload and body) returns its stored
   result, even after the lock is gone. An `uploaded` one finishes its status write.
2. Otherwise the caller must hold the task lock and the status change must be allowed.
3. Answers or delete: the element is read fresh with the user's OSM token. A stale element is
   recorded, the lock is released and the answer is `409 task_ineligible` with `reason` and
   `detail`; nothing is uploaded and no status is written. A delete also needs a node that no
   way or relation uses (else `409 element_in_use`, not staleness).
4. One changeset (`comment` = the challenge's check-in comment or "MapRoulette task N", `source`
   = check-in source, `created_by=MapRoulette`) gets one `<modify>` with all answered tag changes
   merged into the fetched element at its version, or one `<delete>`. The changeset is closed on
   every branch. An OSM 409 triggers one re-check (stale → `task_ineligible`; otherwise one retry).
5. In one transaction: status Fixed (1) after an upload, else the outcome's status (`gone`
   without deletion → 2, `too-hard`, `not-here` and `moved` → 6); `tasks.changeset_id`; the idempotency row
   (`mobile_choice_submissions`) marked done. The lock is released.

A `500 status_pending` (with `changesetId`) is finished by retrying the same submission, without a
second upload. After an unknown upload outcome (`502`), a retry first asks OSM for the
changeset's `changes_count`. A request working on a submission holds a 120 s lease on its row; a
concurrent identical request gets `409 submission_pending` instead of resuming it. While one
submission for a task is unfinished, others get `409 submission_pending`.

| Code | `error` |
| --- | --- |
| 200 | `{"status": n, "changesetId": n \| null, "applied": {"set": {}, "unset": [], "deleted": bool}}` |
| 400 | `invalid_request` (shape, unknown or duplicate field, both forms, bad id) |
| 401 | `osm_reauth_required` (no usable OSM token, or OSM rejected it: sign in again with `osm:tagfix`; the token row is dropped, the MapRoulette grant stays) |
| 403 | `insufficient_scope` (with `"scope": "osm:tagfix"`), `mobile_only` |
| 404 | `not_found` |
| 409 | `lock_required`, `invalid_transition`, `submission_pending`, `task_ineligible` (with `reason`, `detail`), `element_in_use`, `osm_conflict` |
| 422 | `invalid_submission` (with `detail`), `unsupported_task` |
| 500 | `status_pending` (with `changesetId`), `server_error` |
| 502 | `osm_unavailable` |
| 503 | `osm_edits_unavailable` (no token key configured) |

## Guests (deferred sign-up)

*In progress.* A guest answers choice tasks before having an OSM account; the answers wait on the
backend until the person signs in with OSM and claims them. Design and the full HTTP contract:
`deferred-signup-api.md` in the project's design folder. This section grows with each step.

So far (step B1):

- `mobileOAuth.guests.enabled` (`MR_MOBILE_GUESTS_ENABLED`, default `false`; needs `enabled`).
- Scope `guest` is a **client** capability, never part of a grant: an app client's `scopes` may add
  `"guest"` (`["tasks:read", "tasks:write", "osm:tagfix", "guest"]`); admin clients may not.
  `/oauth/mobile/authorize` and refresh still reject `guest` as `invalid_scope`.
- Evolution 135 adds `mobile_guests`, `mobile_guest_tokens` and `mobile_guest_claim_tokens`. A
  guest is never a `users` row. Secrets and tokens are stored as SHA-256 digests; "delete my data"
  clears the secret, email and tokens and leaves a tombstone.

Step B2, guest registration and guest tokens:

| Endpoint | Purpose |
| --- | --- |
| `POST /oauth/mobile/guest` | Form `client_id` (a client with `guest`), no credential. `201 {"guestId", "guestSecret", "expiresAt"}`. The secret (43 characters) is returned once; the app keeps it in secure storage. `401 invalid_client`, `429 rate_limited` (100 per client IP per hour, `Retry-After`). |
| `POST /oauth/mobile/token` | `grant_type=urn:maproulette:grant-type:guest`, `client_id`, `guest_id`, `guest_secret`. A token response with `scope: "guest"`, `expires_in` as for app tokens, and **no** refresh token: the secret mints the next one. `invalid_grant` (wrong secret, deleted or expired guest), `invalid_client`, or `guest_claimed` once the guest has been claimed. While guests are off the grant is `unsupported_grant_type`. |
| `GET /api/v2/mobile-guest/me` | Guest bearer. `{"guestId", "state", "email": "none\|pending\|verified", "pending", "published", "expiresAt", "claimedAs"}`: `pending` and `published` count the guest's answers in those states (B3); `claimedAs` is `null` until claiming exists. Never the address. |
| `DELETE /api/v2/mobile-guest` | Guest bearer. "Delete my data": `204`. |

Step B3, pending answers. A pending answer is held on the backend and changes nothing in OSM or in
the task's status; claiming publishes it later.

| Endpoint | Purpose |
| --- | --- |
| `POST /api/v2/task/:id/choice/pending` | Guest bearer, body as for `POST /task/:id/choice` (JSON, 1–2048 bytes). Only for challenges with `liveMissingQuestions`. Runs the choice check (same 60 s cache), then stores the answer and replaces the guest's earlier answer for the task. `200 {"taskId", "state": "pending", "answeredAt", "holdUntil", "expiresAt"}`: the hold is 7 days and the guest's expiry moves to at least 30 days after this answer. |
| `DELETE /api/v2/task/:id/choice/pending` | Guest bearer. Withdraws the guest's pending answer: `204`, or `404 not_found`. |
| `GET /api/v2/mobile-guest/pending?limit=&after=` | Guest bearer. Every answer of the guest, newest first: `{"items": [{"taskId", "challengeId", "state", "answeredAt", "holdUntil", "result"}], "next"}`. `limit` 1–100 (default 50); `after` is the previous page's `next`. |

Submit errors: `400 invalid_request`; `403 challenge_not_published` (field writes are off and the
challenge is not enabled with tag `mobile-survey-v1`); `404 not_found` (unknown task, or its challenge or project is disabled: preview mode); `409 task_completed` (status
no longer Created, Skipped or Too hard) or `409 task_ineligible` with `reason` and `detail` as from
`choice/check`, `key_changed` when a chosen question was answered in OSM meanwhile; `422
unsupported_task` (no `liveMissingQuestions`, not a choice task, or bundled) or `422
invalid_submission` (including `"delete": true`); `429 pending_limit` (200 pending answers per
guest); `502 osm_unavailable`; `401 invalid_token` if the guest was deleted, claimed or expired in
the meantime. Evolution 137 adds `choice_pending`; "delete my data" deletes the guest's answers that
are still pending.

Discovery: `excludePending=true` on `tasks/box`, `markers/box` and the cluster routes leaves out
tasks with a pending answer whose hold has not ended (next to `excludeStale`). `challenge/:id/tasks`
is not filtered.

`includePending=true` on `tasks/box` keeps held tasks and adds `"pending": true|false` to each task,
so the app can count a held task as Checked in its tally and leave it out of Nearest. It also adds
`"heldKind"`: `"answer"` when the latest live pending answer is an answer or an outcome that counts
as done, `"report"` when that outcome would set status 6 (too-hard, not-here, moved, or a campaign
outcome with `status: 6`), and `null` when the task isn't held. A `report` hold shows as "Needs
another look", isn't counted as Checked and isn't offered by Nearest, the same rule as the live page
(design/not-here-moved.md §3.3, T-002). Nothing about the guest or the answer itself. Without the
parameter the response is unchanged. `ChoicePendingStore.held` and `heldKinds` are the shared
lookups for any other progress read.

A guest token reaches only `MobileGuestRoutes`: the discovery reads of `MobileReadRoutes`, including
`choice/check` (but not `/oauth/mobile/me`), pending answers and its own routes. It is never a
MapRoulette user: the bearer filter sets `MobileBearerIdentity.GuestKey`, never `UserKey`, so stock
routes see an anonymous request. App grants cannot reach the guest's routes. Registration, the
guest's routes and, for guest tokens only, `choice/check` stay open while the field write switch is
off: they neither write OSM nor change task status.

Step B4, the claim email:

| Endpoint | Purpose |
| --- | --- |
| `PUT /api/v2/mobile-guest/email` | Guest bearer, JSON `{"email"}` (≤ 254 characters, one `@`, no spaces), no query string. Stores the address AES-256-GCM sealed under `osmTokenKey` with the guest id as associated data, creates a claim token (kept only as a digest) and sends the link email. Can be called again to correct the address; each call sends a new link. `200` with the `GET /api/v2/mobile-guest/me` body (`"email": "pending"`). `400 invalid_request`, `409 guest_claimed`, `409 nothing_saved` (no pending answers yet; the app asks after the first saved stop), `429 email_rate_limited` (3 sends per guest per 24 h), `503 mail_unavailable` (no mail provider or token key, or the provider refused). |
| `PUT /api/v2/mobile-guest/reminders` | Guest bearer, JSON `{"enabled": false}` (or `true` to resume), no query string. Sets or clears `reminders_stopped_at`; deletes nothing. `204`. |
| `POST /api/v2/mobile-claim` | App bearer with `tasks:write` and `osm:tagfix` (the claim page's grant, or the phone's own), JSON `{"claimToken"}` or `{"guestId", "guestSecret"}`. While the field write switch is off, only the claim page's client (`maproulette-claim`) may call it (D13); the claim then waits, and each such call is recorded in the admin audit as `claim.writes_off`. That client's grants reach no other write route, and not the choice check. In one transaction: marks the guest claimed by the user, deletes its guest access tokens, records a `mobile_claims` row (`queued`) and points the guest's pending answers at it. By link, it also spends the link and gives the phone a new grant family (the guest's client, scope `tasks:read tasks:write osm:tagfix`, no tokens until the phone exchanges its secret) holding a copy of the request family's OSM token. By secret, the request family is the phone's and the secret is spent. `202 {"claimId", "state", "pending", "user": {"displayName", "osmId"}}`; the same user again gets `200` with the same claim. `400 invalid_request`, `401 osm_reauth_required` (the request family holds no OSM token), `403 insufficient_scope`, `404 not_found` (unknown, superseded or expired link, deleted guest, wrong or spent secret), `409 guest_claimed` with `claimedAs` (link only). Evolution 138 adds `mobile_claims` and `choice_pending.claim_id`. The publish job (below) then applies the answers. |
| `POST /api/v2/mobile-claim/<id>/retry` | App bearer, the claim's own user, `tasks:write` and `osm:tagfix`. Body `{}` retries every failed row, `{"taskIds": [n, ...]}` (1 to 100) only those. Failed rows go back to `pending` with no attempts; a claim that was `failed` or got rows back becomes `queued` (`waiting` while writes are off or the claim client is disabled). Idempotent. When the request's grant family isn't the claim's, its OSM token replaces the claim family's (sign in again after `reauth_required`). `202` with the status object plus `"retried": n`. `400 invalid_request`, `401 osm_reauth_required` (the request family holds no OSM token), `403 insufficient_scope`, `404 not_found`. Allowed while field writes are off, under the same D13 rule as the claim. |
| `GET /api/v2/mobile-claim/<id>` | App bearer, the claim's own user only (anyone else `404`). `{"claimId", "state", "total", "published", "skipped", "superseded", "failed", "items": [{"taskId", "state", "changesetId", "dropped"}]}`. Guest tokens can't reach it. |
| `POST /api/v2/mobile-claim/preview` | No credential, JSON `{"claimToken"}` from a `/claim#t=` link. Read only; does not consume the token. `404 not_found` for an unknown token or one not among the guest's three newest. Otherwise `{"state": "active\|claimed\|expired\|deleted", "expiresAt"}`, and for `active` also `pending`, `challenges` (`id`, `name`, `pending`, `hashtag` from the check-in comment, `wikiUrl` from the info link if https), `tasks` (`taskId`, `challengeId`, `label`, `lat`, `lon`, `answeredAt`, `answers` with `prompt` and `answer` labels), `summary` (per challenge and question: `label`, `options`, and `counts` for every option) and `writesEnabled` (false while task writes or the field write switch are off; the page then says publishing is paused). Allowed while field writes are off. |
| `POST /api/v2/mobile-claim/delete` | No credential (a request with `Authorization` is refused), JSON `{"claimToken"}` from a `/claim/delete#t=` link. Same effect as `DELETE /api/v2/mobile-guest`. `204`, `400 invalid_request`, `404 not_found` (unknown token, or not among the guest's three newest), `409 guest_claimed` (account deletion is MapRoulette's process). Allowed while field writes are off. |
| `POST /api/v2/mobile-claim/stop-reminders` | No credential, JSON `{"claimToken"}` from a `/claim/stop-reminders#t=` link. Stops reminders. `204`, `400 invalid_request`, `404 not_found`. Allowed while field writes are off. |

**Publish job** (`ClaimPublishService`, every 10 s while guests are on, and right after a claim).
For each `queued`, `waiting` or `running` claim it publishes the pending answers oldest first,
with the claim's grant family, one changeset per task, through the same path as
`POST /task/:id/choice` (`MobileChoiceService.publishPending`), keyed by the pending row so a rerun
never uploads twice. It needs no lock of its own; another mapper's lock defers the answer. Each
answer's guard is checked on its own: answers whose guard fails are dropped (`result.dropped`)
and the rest applied; none left, or a failed `match` or gone element, is `skipped_stale`. A task
that left Created, Skipped and Too hard, or whose payload changed, is `superseded`, with
`result.agrees` comparing the answers with the submission that completed it. OSM outages stay
`pending` for up to 5 runs, then `failed`. While task writes or the field write switch are off,
or the `maproulette-claim` client is disabled (D13), the claim is `waiting` (checked every run and before every answer). A refused OSM token stops the
claim (`failed`, remaining answers stay pending) and emails a new link to sign in again. With
nothing pending the claim is `done`; if it ever waited, the "now on the map" email goes out, and
then the guest's address is deleted. Evolution 139 adds `mobile_claims.waited_at`.

Mail settings live under `mobileOAuth.guests.mail`: `provider` (`MR_GUEST_MAIL_PROVIDER`: `none`
by default, `log` for development, which logs only the template name, or `postmark`),
`postmark.serverToken` (`MR_POSTMARK_SERVER_TOKEN`), `from` (`MR_GUEST_MAIL_FROM`, default
`Street Tally <hello@streettally.osm.lol>`) and `claimOrigin` (`MR_CLAIM_ORIGIN`, default
`https://streettally.osm.lol`). Links put the token in the URL fragment
(`<claimOrigin>/claim#t=<token>`, `/claim/delete#t=`, `/claim/stop-reminders#t=`) so it never
reaches web server logs. Postmark open and link tracking are off. The templates are copies of the
project's brand/email set in `conf/mobile-email/`.

An hourly job (`GuestJobService`, started by `GuestJobModule` only while guests are enabled) sends
the other emails and enforces retention:

- **Reminders.** The first a day after the first link email, the second in the last five days before
  `expires_at` (a guest who gives an address late gets only the second, and none in its first day).
  Each at most once (`reminded_1_at`, `reminded_2_at`, set before sending and cleared again if the
  provider refuses), never after "stop reminders", and only while answers are pending. Each
  reminder carries a new claim token, so it counts toward the three-per-day send limit and toward
  the guest's three valid links.
- **Expiry.** For an unclaimed guest past `expires_at`: pending answers become `expired`, guest
  access tokens are deleted, the expiry notice is sent, then the address is deleted. A refused
  notice is retried hourly for a day, then the address is deleted anyway. Claim tokens stay, so an
  old link can say "expired".
- **Purge.** Thirty days after `expires_at` the unclaimed guest row is deleted with its tokens;
  its expired answers stay with `guest_id` NULL for campaign statistics.

## Preview mode (drafts)

A challenge is a draft while it, or its project, is disabled. For app bearer
grants and guest tokens:

- `GET /challenge/:id`, `/challenge/:id/tags`, `/challenge/:id/tasks`,
  `/task/:id` and `/task/:id/choice/check` on a draft answer a bare 404, the same
  as an unknown id, unless the user is a super-user (a guest never is). Organizers
  get the same access once `mobile:organize` lands. A guest's pending answer to a
  draft gets `404 not_found`.
- Task writes on a draft (`start`, `skip`, `choice`, status) are refused for
  everyone: 404 for other users, 403 `challenge_draft` for super-users.
  `release` stays open, so a lock taken before the challenge was disabled can be
  dropped.

Discovery (`tasks/box`, `markers/box`, `extendedFind`) already lists enabled
challenges in enabled projects only. Admin grants and web sessions are unchanged.

## Account identity

The mobile callback verifies the person's numeric OSM user ID and looks up the
existing `users.osm_id` row. On the **same MapRoulette instance and OSM
environment**, mobile and traditional web login therefore reach the same
MapRoulette user account and permissions. A first mobile login creates a user
row if none exists. Mobile login does not replace an existing personal API key
or web-session credential; it issues a separate app grant limited to the
approved scopes.

The isolated `mr-api.osm.lol` staging instance has its own database and uses
development OSM accounts. Its user IDs, projects, and challenges are separate
from `maproulette.org`, even if the same person operates both accounts.

## Validation

The protocol/controller suites use synthetic users, an in-memory/mock store boundary and mocked OSM responses. The repository suite runs real PostgreSQL transactions, including simultaneous code redemption, refresh rotation and replay revocation. Set `MOBILE_OAUTH_TEST_DATABASE_URL`, optionally `MOBILE_OAUTH_TEST_DATABASE_USER` and `MOBILE_OAUTH_TEST_DATABASE_PASSWORD`, to a disposable test database to enable that suite; it creates isolated temporary schemas.

```sh
sbt 'testOnly org.maproulette.auth.mobile.* org.maproulette.filters.HttpLoggingFilterSpec org.maproulette.framework.controller.UserControllerSpec'
```

`MobileAdminRepositorySpec` (clients table, seeding precedence, disabled clients, revocation,
audit) uses the same opt-in test database. `MobileAdminSpec` covers the admin scope gate, the
admin routes and CORS without a database.

These checks do not constitute a real OSM browser-login test. Real login additionally requires configured OSM credentials, the registered backend callback, and an approved app callback installed on a device. No deployment is performed by these tests.

## Local browser acceptance test

`scripts/mobile-oauth-test-osm.mjs` is a loopback-only synthetic OSM provider.
It explicitly labels its test identity and does not contact OpenStreetMap.
Use it only with a separate disposable backend database and ignored local
configuration. Bind the backend to `127.0.0.1:9000`, set the OSM server to
`http://127.0.0.1:9001`, use upstream client ID `mobile-sdk-test-upstream`, and
supply the same generated test secret to the backend consumer configuration
and the script's `MOBILE_TEST_OSM_SECRET` environment variable. Register backend
callback `http://127.0.0.1:9000/oauth/mobile/callback` and native client
`maproulette-android-example` with redirect
`org.maproulette.example:/oauth2redirect`. Enable loopback development explicitly.

With both servers running, `node scripts/mobile-oauth-smoke.mjs` checks the
browser state/cookie/consent exchange, PKCE, minimal identity, forbidden routes,
refresh rotation, replay revocation and explicit revocation. It refuses an
upstream provider outside the synthetic loopback setup. Android USB testing
also needs `adb reverse tcp:9000 tcp:9000` and `adb reverse tcp:9001 tcp:9001`.
Keep secrets in ignored local configuration; never commit real OSM credentials.

## Before public enablement

Each deployment needs a real OSM login and browser callback acceptance check
with its registered OSM application. The isolated staging deployment at
`https://mr-api.osm.lol` passed this check with a development OSM account and
the Android example on 2026-10-05. Establish retention for
expired interactions and token rows before sustained public use. Consumed code
and refresh-token records must remain available while their grant family can
still be used, so replay detection continues to revoke active credentials.
These operational checks are separate from the automated protocol tests.
