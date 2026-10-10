# Mobile admin API

Fork-only routes for the mobile admin web app
([maproulette-mobile-admin](https://github.com/mvexel/maproulette-mobile-admin)). They manage
the approved mobile OAuth clients and show an audit log. They exist only while
`mobileOAuth.enabled = true`; otherwise they return 404. Sign-in and scopes are described in
[mobile-oauth.md](mobile-oauth.md#admin-scope-mobileadmin).

## Access

Every request needs `Authorization: Bearer <access token>` from a `mobile:admin` grant, and the
signed-in user must be a MapRoulette super-user **at the time of the request**. A web session or
API key gets `403 mobile_admin_only`. A demoted user gets `403 admin_required`. Any other bearer
grant gets `403 insufficient_scope`. Responses carry `Cache-Control: no-store`.

An admin grant reaches only these routes:

| Method | Route |
| --- | --- |
| any of GET, POST, PATCH, PUT, DELETE | `/api/v2/mobile-admin/...` (what exists is listed below) |
| GET | `/oauth/mobile/me` |
| POST | `/api/v2/challenge` (create) |
| PUT | `/api/v2/challenge/:id` (update) |
| PUT | `/api/v2/challenge/:id/addFileTasks?lineByLine=true&report=true` (exactly these two parameters, in any order; never `removeUnmatched`) |
| GET | `/api/v2/challenge/:id`, `/api/v2/challenge/:id/tasks`, `/api/v2/task/:id` |

The stock routes behave as upstream, acting as the signed-in super-user. Every non-GET request on
a stock route is recorded in the audit log as `stock.<METHOD>` with its path and response status.
Challenge creation, update, and line-by-line task import are available while a field deployment's
task write policy is off. The policy still blocks task lifecycle and choice submissions, including
production OSM edits. Legacy session and API-key challenge writes remain blocked while it is off.
Choice payloads may opt into `liveMissingQuestions: true`. The choice check then returns
`questionIds` for template questions whose guarded tags are still absent in live OSM. When none
remain, the task becomes ineligible. A selected answer is checked again against fresh OSM before
upload; a changed selected tag returns `409 task_ineligible` without an edit or task status change.

## Clients

A client is `{"id", "name", "redirectUris", "scopes", "enabled"}`.

- `id`: 1 to 100 of `A-Z a-z 0-9 . _ -`.
- `name`: 1 to 200 characters, with no surrounding spaces or control characters.
- `redirectUris`: 1 to 10 distinct URIs. Each is `https://host/...` or a reverse-domain custom
  scheme (`com.example.app:/path`), with no query, fragment or user info.
- `scopes`: one of `["tasks:read"]` (the default), `["tasks:read","tasks:write"]`,
  `["tasks:read","tasks:write","osm:tagfix"]` or `["mobile:admin"]`.
- `enabled`: defaults to `true`.

### `GET /api/v2/mobile-admin/clients`

`200 {"clients": [...]}`, ordered by id. Each client also has `source` (`config` or `admin`),
`createdBy`, `updatedBy` (MapRoulette user ids, `null` for config), `createdAt` and `updatedAt`.

### `POST /api/v2/mobile-admin/clients`

The body is a client (JSON, at most 16 KiB, no other fields). Returns `201` with the client,
`400 invalid_request` with `detail` (a list of messages), or `409 client_exists`.

### `PATCH /api/v2/mobile-admin/clients/:id[?revokeGrants=true]`

The body holds one or more of `name`, `redirectUris`, `scopes` and `enabled`. `id` cannot change.
Returns `200` with the client, `400 invalid_request`, or `404 not_found`.

- **Disabling** (`"enabled": false`) takes effect within 10 seconds on every backend process, and
  at once on the one that handled the request. The client can't authorize, exchange codes, refresh
  or use its access tokens. Its grants are kept, so enabling it again restores them.
- `revokeGrants=true` (only together with `"enabled": false`) also revokes every active grant
  family of the client and deletes their OSM tokens. This is permanent: users sign in again. The
  response then adds `revokedGrantFamilies` (a count). Another backend process whose 10-second
  client cache still has the client enabled can complete a sign-in just after the revocation.
  That grant is not revoked; it is blocked while the client is disabled, and works again if it
  is re-enabled.
- Removing scopes stops the grants that use them, as removing them from config did.
- `409 self_lockout`: the admin app can't disable its own client or remove `mobile:admin` from it.
  Do that from another admin client or with SQL.

A row changed here is never overwritten by `mobileOAuth.clients` again (see
[mobile-oauth.md](mobile-oauth.md#clients)).

## Audit

Every admin write adds a row to `mobile_admin_audit`. `client.create` and `client.update` (with
`before` and `after`) are written in the same transaction as the change. `stock.<METHOD>` rows
for the stock routes above are written after the action finishes (`after` =
`{"status": n}`, or `{"status": 500, "error": "<exception class>"}` when it failed). They are
best effort: if that insert fails, the error is logged and the response is unchanged.

### `GET /api/v2/mobile-admin/audit?limit=50&page=0`

`200 {"items": [{"id", "actorUserId", "action", "target", "before", "after", "createdAt"}],
"page", "limit", "total"}`, newest first. `limit` is 1 to 200; `page` starts at 0.

### `GET /api/v2/mobile-admin/challenges/:id/results?format=csv|geojson`

Campaign results, read-only. Every task of the challenge, ordered by task id, with its status,
who completed it (`completed_by`, OSM name and id) and when (`mapped_on`), its changeset, the
`choice_stale` reason if any, and from the task's latest done mobile choice submission the chosen
answers (`{"<question id>": "<option id>"}`, stored since evolution 134; empty for outcomes and
older submissions) and the applied tag changes. `format` defaults to `csv`; anything other than
`csv` or `geojson` is 400 `invalid_request`. An unknown challenge is 404 `not_found`.

- `csv`: `text/csv`, CRLF lines, one `answer:<question id>` column per question answered anywhere
  in the challenge. `tags_set` is `key=value;…`, `tags_unset` is `key;…`. Text cells that start
  with `= + - @` get a leading `'` so spreadsheets don't run them.
- `geojson`: `application/geo+json`, a FeatureCollection of task points with the same fields as
  properties (`answers` and `tagsSet` as objects).

Both carry `Content-Disposition: attachment; filename="challenge-<id>-results.<format>"`.

## Field write policy

On deployments using `conf/mobile-field.conf`,
`GET /api/v2/mobile-admin/write-policy` returns `{"enabled":false,"managed":true}`
initially. `PUT /api/v2/mobile-admin/write-policy` accepts exactly
`{"enabled":true|false}` from a `mobile:admin` grant held by a current
super-user. It changes the policy in that deployment's database and records
`write_policy.update` in the audit log. Repeating the current value is a no-op.
Enabling requires a valid `MR_MOBILE_OSM_TOKEN_KEY`; otherwise the route
returns 409 `write_prerequisites_missing`. A deployment without the managed
policy reports `managed:false` and refuses PUT with 403
`write_policy_unmanaged`.

While disabled, the backend exposes only selected read, mobile login, and
admin control routes, including for legacy sessions and API keys. Mobile
lifecycle and choice submissions return 403 `mobile_writes_disabled`, even
with an existing write grant. This switch is per database. The production-OSM
field Compose projects start with the policy disabled.

## CORS

With `mobileOAuth.adminOrigin` set (`MR_MOBILE_ADMIN_ORIGIN`, for example
`https://admin.mr-dev.osm.lol`), that origin gets CORS without credentials (bearer tokens only).
It applies on the admin routes above, `/oauth/mobile/token` and `/oauth/mobile/revoke`. Preflights
allow the requested method if the route allows it, and the headers `Authorization`,
`Content-Type` and `Accept`, cached for 10 minutes. On `/api/v2/mobile-admin/...`, token and
revoke, other origins get no CORS headers and their preflights get 403. Everything else is
unchanged. See [mobile-oauth.md](mobile-oauth.md#cors).
