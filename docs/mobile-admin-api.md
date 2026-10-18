# Mobile admin API

Fork-only routes for the mobile admin web app
([maproulette-mobile-admin](https://github.com/mvexel/maproulette-mobile-admin)). They manage
the approved mobile OAuth clients and show an audit log. They exist only while
`mobileOAuth.enabled = true`; otherwise they return 404. Sign-in and scopes are described in
[mobile-oauth.md](mobile-oauth.md#admin-scope-mobileadmin).

## Access

Every request needs `Authorization: Bearer <access token>` from a `mobile:admin` grant, and the
signed-in user must be a MapRoulette super-user **at the time of the request**. Organizers use a
`mobile:organize` grant instead, for part of the API (see [Organizers](#organizers)). A web session or
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

### Organizers

A `mobile:organize` grant ([mobile-oauth.md](mobile-oauth.md#organizer-scope-mobileorganize))
reaches the GET stock routes of the admin allowlist, where upstream's project checks apply, and
these admin routes, for the projects the organizer runs (Write or better, not their Home project):

| Method | Route | Organizer |
| --- | --- | --- |
| GET | `/api/v2/mobile-admin/campaigns`, `/projects` | Their projects' only |
| PUT | `/api/v2/mobile-admin/projects/:id/enabled` | Their projects |
| GET, PUT | `/api/v2/mobile-admin/challenges/:id/live`; POST `.../live/rotate` | Their projects' campaigns |
| GET | `/api/v2/mobile-admin/challenges/:id/results` | Their projects' campaigns |
| POST | `/api/v2/mobile-admin/challenges/:id/queue`; GET `.../queue/export` | Their projects' campaigns |
| GET | `/api/v2/mobile-admin/write-policy` | Read only |

Anything else of the admin API (clients, scope sets, audit, setting the write switch, creating
projects, organizers) is `403 insufficient_scope` at the bearer filter, and so is every stock
write (creating or changing a challenge, adding tasks): organizers write only through routes that
check the project. A route for a project or campaign that isn't theirs is `403
organizer_forbidden`, and an organizer no longer on the list gets `403 organizer_required`.

While the write switch is off (`mobileOAuth.writeControlEnabled` and
the [field write policy](#field-write-policy) off), only super-users' admin grants write: an organizer's
non-GET request is `403 mobile_writes_disabled`, as an app's would be, except creating a
wizard draft (see [Campaign wizard](#campaign-wizard)).

Super-users keep the list:

#### `GET /api/v2/mobile-admin/organizers`

`{"organizers": [{"userId", "osmId", "name", "grantedBy", "createdAt", "projects": [{"id",
"name", "displayName", "role": "admin"|"write"}]}]}`, by name. `projects` are the person's own
project grants of Write or better, without their Home project.

#### `POST /api/v2/mobile-admin/organizers`

`{"osmUsername", "projectId", "role": "write"|"admin"}`. Gives the person that upstream role on
the project (replacing any other role they held there, as upstream's
`PUT /api/v2/user/:osmId/project/:pid/:role`) and puts them on the list, audited as
`organizer.add`, in one transaction. Returns the organizer as listed. Making the project's last
administrator a Write organizer is `409 last_project_admin`, and changes nothing. The username matches without regard to case;
the person must have signed in to MapRoulette once (`404 user_not_found`). An unknown or deleted
project is `404 project_not_found`. Adding someone to another project is the same call.

#### `DELETE /api/v2/mobile-admin/organizers/:userId`

Takes them off the list (`204`, audited as `organizer.remove`; `404` if they weren't on it).
Their approved [organizer requests](#organizer-requests) turn void, and their role on each
project such a request created is removed (unless they are its last administrator). Their other
project roles stay, as upstream roles.

#### `DELETE /api/v2/mobile-admin/organizers/:userId/projects/:projectId`

Removes their upstream role on the project and returns the organizer (audited as
`organizer.project.remove`, in the same transaction). Removing a project's last administrator is
`409 last_project_admin`, and changes nothing.

### Organizer requests

Becoming an organizer without email (ORG-ONB, design/organizer-onboarding.md). Who may ask is one
setting, `mobileOAuth.organizers.requests` (`MR_ORGANIZER_REQUESTS`): `closed` (the default,
also for any other value) or `review` (anyone signed in with OpenStreetMap, not a guest; a
super-user decides each request). Dev and stage set `review`; prod stays `closed` until Martijn
decides who may become an organizer.

Such a person asks with a `mobile:organizer-request` grant
([mobile-oauth.md](mobile-oauth.md#organizer-request-scope-mobileorganizer-request)), which
reaches only the two `organizer-request` routes below; anything else is `403 insufficient_scope`,
and asking with a grant whose person can no longer ask (requests closed since) is `403
organizer_request_not_allowed`; reading their own request still works while the grant lasts. `mobile:organize` still means a listed organizer: once approved,
the admin app signs in again for `mobile:organize`. Asking is allowed while the write switch is
off, like a wizard draft: it writes only this table.

#### `GET /api/v2/mobile-admin/organizer-request`

The grant's own person: `{"name", "osmId", "organizer": bool, "canRequest": bool, "askAgainAt": instant|null,
"request": {"id", "state": "open"|"approved"|"declined", "place", "subject", "group", "createdAt",
"decidedAt", "note", "projectId"}|null}`. `request` is their latest one that isn't void (a request
turns void when its organizer is removed). `note` is the decline note, if any. `askAgainAt` is set
while a decline is less than 30 days old. `name` is their OSM username, for "You'll use this
OpenStreetMap account".

#### `POST /api/v2/mobile-admin/organizer-request`

`{"place", "subject", "group"?, "policyAccepted": true}` (each 1 to 200 characters). `201` with
the request, audited as `organizer.request`. The OSM account's creation date and changeset count
are looked up on OSM once, now, and kept with the request; if OSM can't say, the request is kept
without them (the creation date falls back to the one from sign-in). `409 already_organizer`,
`409 request_open` (one open request per person), `409 too_soon` with `askAgainAt` (30 days after
the latest decline), `409 requests_closed`.

#### `GET /api/v2/mobile-admin/organizer-requests`

Super-users: `{"requests": [{"id", "userId", "osmId", "name", "osmAccount": {"createdAt",
"changesets"}, "place", "subject", "group", "policyAcceptedAt", "createdAt"}]}`, the open ones,
oldest first. The count is the nav's "Organizers (n)".

#### `POST /api/v2/mobile-admin/organizer-requests/:id/approve`

Super-users: `{"projectName"}` (1 to 200 characters, editable before confirming). In one
transaction: creates the project (owned by the approver, who gets Admin on it as upstream gives a
project's creator), gives the requester Write on it, puts them on the organizer list, closes the
request as `approved` and audits it as `organizer.request.approve`. Returns the request. `409
project_exists` (the name is taken, ignoring case; nothing changes), `409 not_open` with `state`,
`404 not_found`.

#### `POST /api/v2/mobile-admin/organizer-requests/:id/decline`

Super-users: `{"note"?}` (up to 1000 characters, shown to the requester). Closes the request as
`declined`, audited as `organizer.request.decline`. Returns the request. `409 not_open`, `404
not_found`.

### Stage service key

Stage only: `Authorization: Bearer <service key>` acts as a dedicated super-user, "Street Tally
stage service" (OSM id -1001, not a real OSM account; created and promoted on first use), so
scripts can import test challenges without an admin sign-in. It reaches only `POST
/api/v2/challenge`, `PUT /api/v2/challenge/:id`, the line-by-line `addFileTasks` import, `GET
/api/v2/challenge/:id` and `.../tasks`, and `GET /api/v2/mobile-admin/campaigns` and `/projects`;
anything else is `403 insufficient_scope`. Its writes are audited as `service_key.stock.<METHOD>`.

The server holds only the key's SHA-256 (`MR_STAGE_SERVICE_KEY_SHA256`, lowercase hex) and
compares in constant time. It accepts the key only when `MR_DEPLOYMENT` is `stage` and the public
origin is `https://mr-stage.osm.lol`; anywhere else it logs `Stage service key ignored` at startup
and the key is just an unknown bearer token (`401 invalid_token`). Generate it with
`openssl rand -hex 32`; its hash with `printf %s "$KEY" | sha256sum`.

## Clients

A client is `{"id", "name", "redirectUris", "scopes", "enabled"}`.

- `id`: 1 to 100 of `A-Z a-z 0-9 . _ -`.
- `name`: 1 to 200 characters, with no surrounding spaces or control characters.
- `redirectUris`: 1 to 10 distinct URIs. Each is `https://host/...`, a reverse-domain custom
  scheme (`com.example.app:/path`) or an HTTP loopback IP literal with a port
  (`http://127.0.0.1:8111/path`, `http://[::1]:8111/path`), with no query, fragment or user info.
- `scopes`: one of `["tasks:read"]` (the default), `["tasks:read","tasks:write"]`,
  `["tasks:read","tasks:write","osm:tagfix"]`, `["tasks:read","tasks:write","tasks:edit"]` or
  `["mobile:admin"]`.
- `enabled`: defaults to `true`.

### `GET /api/v2/mobile-admin/scope-sets`

`200 {"scopeSets": [["tasks:read"], ...]}`: every scope set a client may hold, each in canonical
order, derived from the backend's scope rules. Offer these instead of a fixed list.

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

Both formats name people, so both carry the note "Contains OSM usernames; don't publish this
file." (i16). `completed_by_count` is how many of the challenge's tasks that person completed, for
thank-yous. It is only in this export, never on the live page or in the app.

- `csv`: `text/csv`, CRLF lines. The first line is the note as `# …`, then the header row. One
  `answer:<question id>` column per question answered anywhere in the challenge. `tags_set` is `key=value;…`, `tags_unset` is `key;…`. Text cells that start
  with `= + - @` get a leading `'` so spreadsheets don't run them. After the answer columns:
  `pending_count` (guests' answers waiting to be published, `choice_pending` state `pending`),
  `pending_outcomes` and one `pending:<question id>` column per question guests answered, each a
  tally such as `hinged 2;sliding 1`, most given first. Last, `skipped_count` and
  `skipped_reasons`: guests' answers the publish guard skipped because the place changed before
  they were published (`choice_pending` state `skipped_stale`), with a tally of reasons such as
  `key_changed 2`.
- `geojson`: `application/geo+json`, a FeatureCollection with the note as a top-level `notice`
  member, of task points with the same fields as properties (`answers` and `tagsSet` as objects,
  `completedByCount`), plus `featureName` and `featureRef` (the task feature's `name` and `ref`
  tags, or null) so the admin can label stops, and `pending`: the waiting answers as
  `{answers, outcome, outcomeLabel}`, and `skippedCount` and `skippedReasons` (an object such as
  `{"key_changed": 2}`). Neither format carries guest ids or addresses.

Both carry `Content-Disposition: attachment; filename="challenge-<id>-results.<format>"`.

## Campaigns and projects

For the admin app's Campaigns list and project picker. A campaign is a choice challenge
(`cooperative_type` 3). Lists are capped at 500 rows. These routes, including the two project
writes, work while a field deployment's task write policy is off.

### `GET /api/v2/mobile-admin/campaigns`

`200 {"campaigns": [{"id", "name", "enabled", "published", "listed", "modified", "project": {"id",
"name", "displayName", "enabled"}, "tasks": {"total", "checked", "needsLook"}}]}`, most recently
changed first. `published` means the challenge carries the `mobile-survey-v1` tag (the admin
app's Publish). `listed` is what the app shows: the challenge is enabled and published, and its
project is on. Progress uses the app's rule: `checked` counts status 1, 2 or 5 and tasks held by a
live pending guest answer; `needsLook` counts status 6 without one; `total` leaves out deleted (4)
and disabled (9) tasks, and, as the live page does, open places found stale (gone or retagged) that
nothing holds.

### `GET /api/v2/mobile-admin/projects`

`200 {"projects": [{"id", "name", "displayName", "enabled", "campaigns", "published"}]}` by name,
leaving out deleted, archived and virtual projects. `campaigns` counts the project's choice
challenges, and `published` counts those that are enabled and published.

### `POST /api/v2/mobile-admin/projects`

`{"name", "displayName"?}`, each 1 to 200 characters. Creates the project **on**, owned by the
signed-in super-user, and records `project.create`. `201` with the project as listed above, `409
project_exists` (names are unique, ignoring case), or `400 invalid_request`.

### `PUT /api/v2/mobile-admin/projects/:id/enabled`

Exactly `{"enabled": true|false}`. Turning a project off hides all its campaigns in the app;
turning it on lists those that are enabled and published. Records `project.enable` or
`project.disable` with the project after the change. Repeating the current value is a no-op.
`200` with the project, or `404 not_found`.

## Campaign wizard

A new campaign from a question set and an area, in one call (campaign-wizard.md, Juno's
wizard-api-draft.md). Super-users, and organizers for their own projects. The library is
`conf/mobile/question-sets.json`: each set's words for the admin, its OSM filter, questions and
outcomes. A set's `version` goes up whenever its filter, questions or outcomes change. The client
never sees filters or tags.

Counts and features come from Overpass (`osm.ql.provider`), with a 10 s timeout, answers cached
for an hour, at most two queries in flight per server and 20 per minute per user (`429
rate_limited`). Centres snap to about 50 m, so the counts, the list and the create step see the same
area. A feature is **open** when the set's filter picks it and at least one question still applies
(every question is fill-missing); **complete** is the rest of what the filter picks.

### `GET /api/v2/mobile-admin/wizard/question-sets`

`{"sets": [{"id", "version", "name", "why", "audience", "noun": {"one", "many"}, "example",
"pace", "description", "questions": [{"id", "prompt", "description"?, "options": [{"id",
"label"}]}]}]}`. `pace` is places per participant per hour; `description` is a template with
`{place}`, `{nounMany}` and `{audience}`. `questions` are the participant's words in task order,
without the guards or tags behind them.

### `GET /api/v2/mobile-admin/wizard/counts?lat=&lon=&radius=`

`radius` 200 to 3000 metres; anything else is `400 invalid_request` with `detail`. One Overpass
query: `{"counts": [{"set", "version", "open", "complete"}]}`, or every set `{"set", "version",
"unavailable": true}` when Overpass didn't answer.

### `GET /api/v2/mobile-admin/wizard/coverage`

What the nightly OSM extract covers, so the wizard can stop early on a place outside it:
`{"extract": {"source", "extractAt", "loadedAt", "features", "fresh", "maxAgeHours", "bbox":
[west, south, east, north], "coverage": <GeoJSON geometry, simplified to about 100 m>}}`, or
`{"extract": null}` when none was ever loaded. A place is read from the extract when `fresh` is
true and its whole circle lies inside `coverage`; anywhere else counts and features ask Overpass.

### `GET /api/v2/mobile-admin/wizard/question-sets/:id/features?lat=&lon=&radius=`

`{"set", "version", "complete", "truncated", "open": [{"id": "node/<n>"|"way/<n>", "lon", "lat",
"name"}]}`, at most 2000 open places (`truncated: true` past that). `502 osm_unavailable` when
Overpass failed, `504 osm_timeout` when it timed out (no retry), `429 rate_limited` past 20 calls
a minute.

### `POST /api/v2/mobile-admin/wizard/campaigns`

`{"set", "version", "area": {"lat", "lon", "radius", "place"}, "leaveOut": ["node/<n>"],
"name", "description", "eventDate"?, "projectId"}`. Reads Overpass again (no cache), drops
`leaveOut`, and creates a disabled, local-only choice challenge in the project with one task per
place left, as the admin's survey builder would (`src/survey.ts`; a shared golden file keeps them
equal). The changeset comment is the admin's (Iris, admin #30): `From a Street Tally survey for <name>[, <10 November 2026>]. Checked by me. #StreetTally #StreetTally<Place>`, the place in CamelCase, at most 40 characters; source `survey`. Overpass endpoints come from `OVERPASS_URLS` (comma-separated; default `https://overpass-api.de/api/interpreter,https://maps.mail.ru/osm/tools/overpass/api/interpreter,https://overpass.private.coffee/api/interpreter`), tried in order only when connecting fails; a timeout or error after connecting is the answer. At startup each endpoint gets a tiny real query (`[out:json][timeout:5];node(1);out ids;`), and one log line per endpoint says whether it answers and where its host resolves (`Overpass answered: HTTP 200 in 312 ms (…/interpreter, resolves to 1.2.3.4 (IPv4), 2a01:4f8::1 (IPv6))`; anything but 200, with the first 300 characters of the server's answer after it, or `Overpass not reachable`, means that endpoint is out; the query is form-encoded `data=`, as the wizard sends it, and every request names itself `StreetTally/<commit> (+https://streettally.osm.lol; martijn@osm.lol)`). The set,
version, area and event date go in `mobile_campaign_sources` (evolution 146); the audit log gets
`campaign.create`.

- `201 {"id", "tasks"}`.
- `400 invalid_request` with `detail` (radius outside 200 to 3000 metres among them);
  `404 project_not_found`; `403 organizer_forbidden` for a project that isn't theirs, their Home
  project included.
- `409 set_changed` when `version` isn't current.
- `400 area_too_big` past 2000 open places; `422 nothing_to_check` when no place is left.
- `502 osm_unavailable`, `504 osm_timeout`, `429 rate_limited`; Overpass is asked once, never
  retried.

### `POST /api/v2/mobile-admin/wizard/campaigns/:id/publish`

Lists the campaign in the app as the admin's Publish does: enabled and tagged
`mobile-survey-v1`. `200 {"id", "published": true, "listed"}`; `listed` is false while the project
is off. `404 not_found` for anything but a choice campaign, `422 nothing_to_check` without tasks.
Audited as `campaign.publish`.

While the write switch is off, create still works, since the campaign is a disabled draft (W-DRAFT,
Martijn 2026-10-17), and publish is `403 mobile_writes_disabled` for everyone, super-users
included.

## Live progress page

BACKLOG #12 (the project's design/live-progress.md). One link-only page per campaign (evolution
141), reached by an unguessable token instead of the challenge id. The participant site serves it at
`/live/<token>` and reads the public route below.

### `GET /api/v2/mobile-admin/challenges/:id/live`

`200` with the page's settings:
`{"challengeId", "token", "enabled", "showAnswers", "eventStart", "endedAt", "updatedAt"}`.
`404 no_live_page` when the campaign has none yet, `404 not_found` when the challenge doesn't
exist.

### `PUT /api/v2/mobile-admin/challenges/:id/live`

Any of `{"enabled": bool, "showAnswers": bool, "eventStart": "<ISO 8601 instant>"|null,
"ended": bool}`; fields left out keep their value. The first PUT creates the page: off unless it sets `enabled: true`, answers
shown, live, no event start. `ended: true` ends live mode and keeps the first end time; `false`
makes the page live again. `eventStart` turns on the people count (people who saved a stop since
then); `null` turns it off. Records `live.create` or `live.update`, with the settings but not the
token. `200` with the settings, `400 invalid_request`, or `404 not_found`.

### `POST /api/v2/mobile-admin/challenges/:id/live/rotate`

A new token; the old link answers `404 not_found` from then on. Records `live.rotate`. `200` with
the settings, or `404 no_live_page`.

### `GET /api/v2/live/:token` (public)

No credential. `Cache-Control: no-store`, `X-Robots-Tag: noindex`, `Referrer-Policy: no-referrer`.
`404 not_found` for an unknown or rotated token, `404 turned_off` for a page that is off. Open while
field writes are off. CORS answers only the claim origin (`MR_CLAIM_ORIGIN`), which serves the
participant site.

```json
{
  "campaign": {"name": "Woodbine entrances", "hashtags": ["#StreetTallyWoodbine"],
               "match": {"entrance": "main"}},
  "mode": "live",
  "answersShown": true,
  "generatedAt": "2026-10-18T23:30:12Z",
  "tally": {"checked": 9, "total": 16, "needsLook": 1, "people": 4},
  "stops": [
    {"id": 812, "lon": -111.9068, "lat": 40.7537, "state": "checked", "name": "Granary Live",
     "at": "2026-10-18T23:12:00Z",
     "answers": [{"question": "How does this door open?", "answer": "Swings open"}]},
    {"id": 813, "lon": -111.9070, "lat": 40.7541, "state": "look", "at": "2026-10-18T23:20:00Z",
     "reported": "There's no door"},
    {"id": 814, "lon": -111.9059, "lat": 40.7530, "state": "todo", "ref": "B"}
  ]
}
```

- `state`: `checked` is status 1, 2 or 5, or a guest answer that holds the task (the excludePending
  rule: live pending, or on the map) whose outcome isn't a status-6 one. `look` ("Needs another look") is status 6, or a live
  pending answer with a status-6 outcome: `too-hard`, `not-here`, `moved`, or a campaign outcome
  declared with status 6. `todo` is status 0 or 3. Other tasks, and stale tasks still open, are
  left out. `tally.total` counts the stops listed.
- `at`: when the stop was checked or reported, cut to the minute. There's none on `todo`.
- `answers` (question prompt and answer label, in question order) and `reported` (the outcome's
  label, or "It's moved about 40 m") appear only when `answersShown`. They never appear for a
  pending guest answer. A reported spot is never sent.
- `tally.people` appears only while live with an event start. `endedAt` appears when ended.
- `campaign.match` is the first task's identity guard, so the page can name its places as the
  apps do. `hashtags` come from the changeset comment.
- The response never carries a user, guest, OSM element or changeset.

## Review page

H1a. Mappers read a campaign's held guest answers and mark places. The page is reached by the
campaign's live page token (above), and answers 404 like it: `not_found` for an unknown or rotated
token, `turned_off` when the live page is off. A place is a task of the campaign with a point that
has a held guest answer (not "I'm not sure"), or that someone marked. Every response has
`Cache-Control: no-store`, `X-Robots-Tag: noindex` and `Referrer-Policy: no-referrer`. The routes
are open while field writes are off, and CORS answers the claim origin and the admin origin,
without credentials.

Guest answers of a campaign with a live page stay `pending` after their guest expires or is
purged (then without a guest), so they can be reviewed, until a mapper has checked the place: once
it is `done`, `gone` or `needs_visit`, its answers expire and are purged as usual (D23).

### `GET /api/v2/review/:token[?filter=all|agree|visit|done&after=<placeId>&limit=1..200]`

No credential needed. With an app grant's bearer, each place also says whether this mapper skipped
it. `limit` defaults to 100; `next` is present while more places match. Anything else in the query
is `400 invalid_request`.

```json
{
  "campaign": {"name": "Woodbine entrance doors", "hashtags": ["#StreetTally"],
               "changesetComment": "Add entrance details #StreetTally"},
  "counts": {"toReview": 47, "added": 12, "needsVisit": 3, "stale": 1, "gone": 0, "disagree": 2},
  "places": [
    {"id": 1234, "osm": {"type": "node", "id": 14178183367}, "lon": -111.906812, "lat": 40.7538166,
     "name": null, "ref": null, "state": "open", "skipped": false,
     "suggestions": [
       {"question": "door", "prompt": "How does this door open?", "answer": "sliding",
        "label": "Sliding", "tags": {"door": "sliding"}, "people": 2, "day": "2026-10-14",
        "current": {"door": null}}],
     "reports": [
       {"outcome": "moved", "label": "It's moved", "people": 1, "day": "2026-10-14",
        "lon": -111.90679, "lat": 40.75382}]}
  ],
  "next": 1235
}
```

- `state`: `open`, `needs_visit`, `done` (OSM carries the suggested tags), `stale` (OSM changed
  since the survey), `gone` (the element was deleted) or `disagree` (an open place where guests
  gave different answers to one question; derived on each read, never stored). The confirm queue's
  stale check sets `done`, `stale` and `gone`; so does the hourly "On the map" check below. `counts` has one key per state; `toReview` counts
  `open`, `added` counts `done`.
- `filter`: `agree` is open places where two or more guests gave the same answer; `visit` is
  `needs_visit`; `done` is `done`.
- `suggestions`: one per question and answer, merged across guests, in question order. `tags` are
  the option's tag changes (null for a removed key); `current` is each of those keys in the task's
  imported properties. `day` is the UTC date of the latest such answer.
- `reports`: one per outcome id (built-in or the campaign's own), with the latest reported spot
  when there is one.
- No guest id or time finer than a day is ever sent.

### `PUT` / `DELETE /api/v2/review/:token/places/:id/skip`

Hides or unhides the place for this mapper only. Returns the updated place.

### `PUT` / `DELETE /api/v2/review/:token/places/:id/needs-visit`

Sets or clears the shared "Needs a visit" mark, audited as `review.needs_visit` or
`review.needs_visit_cleared` with the mapper's user id. Returns the updated place. Setting it ends
the place's guest answer holds, so the campaign task shows in the app again. `409 done` when
setting it on a place OSM already carries.

Marks need a bearer from an app grant with `tasks:read` (the review page's client is
`street-tally-review`, public, PKCE, scope `tasks:read`); without one they return
`401 sign_in_required`. They write only the review tables, never a task or OSM. `404 not_found` for
an id that isn't a place of the campaign. Guest and admin grants get `403 insufficient_scope`.

## Confirm queue

H1b. Turns a campaign's clear guest tag answers into a tag-fix challenge that mappers work on
maproulette.org. Both routes need a `mobile:admin` grant of a super-user, answer
`Cache-Control: no-store`, and are open while field writes are off. Nothing here writes to OSM.

### `POST /api/v2/mobile-admin/challenges/:id/queue`

Runs the generator for choice campaign `:id`, audited as `queue.generate` with the counts. For each
open or stale place it takes the questions every guest answered alike (questions answered
differently count as `disagree` and stay on the review page; reports and "I'm not sure" are never
queued), then reads the element from OSM once, without a token:

| OSM now | Result |
|---|---|
| Gone | counted as `gone`, place `gone` |
| No longer matches, or a question's tags changed since the survey | counted as `stale`, place `stale` |
| Already carries the answer | counted as `done`; place `done` when all its answers are |
| Unchanged | one task per place and question, counted as `queued` |

The first run creates the queue challenge in the campaign's project: `<campaign>: confirm survey
answers`, disabled, cooperative tag fix (type 1), the campaign's changeset comment, instruction
`{{evidence}}`. Each task's `evidence` property reads `Surveyed on 14 Oct by 2 people. Question: "How
does this door open?" Answer: "Sliding" (door=sliding). Add it if it looks right; skip if unsure.`
(`by 1 person` for one, `on 13–14 Oct` when the answers span several days). Reruns add
only new place and question pairs; ones already queued count as `alreadyQueued`. A queued place's
campaign task stays out of discovery until someone marks it "Needs a visit".

```json
{"queueChallengeId": 77, "queued": 12, "alreadyQueued": 3, "done": 2, "stale": 1, "gone": 1, "disagree": 0}
```

`queueChallengeId` is 0 while the campaign has no queue. `404 not_found` for an id that isn't a
choice campaign; `502 osm_unavailable` when OSM can't be read; then nothing is changed or queued.

### `GET /api/v2/mobile-admin/challenges/:id/queue/export`

The queue as line-by-line GeoJSON (`application/geo+json-seq`, attachment
`confirm-queue-<id>.geojson`): one FeatureCollection per task with its point (`@id`, `evidence`)
and `cooperativeWork`, as maproulette.org's "Upload GeoJSON" takes it with "line-by-line" on.
Stale edits (H1d) are left out. `404 no_queue` before the first run.

### "On the map" (H1c)

An hourly job (while guests are enabled) re-reads OSM, read-only, for queued edits not yet seen on
the map, the least recently checked first, up to 200 places a run. When OSM carries a queued edit,
the held guest answers that gave it become `on_map` once none of their other queued answers is
still waiting (the guest's `published` and
`byChallenge.onMap` in `GET /api/v2/mobile-guest/me`). A place whose queued edits are all on the map
becomes `done`, one whose element was deleted `gone`; the place's guest answers still waiting then
end `skipped_stale` (`result.reason` `element_gone`). The generator does the same for answers OSM
already carries, and for deleted elements, when it runs.

H1d: a queued edit whose question OSM no longer matches (its key changed after queueing, say
`material=stone` where `wood` was queued, or the element left the campaign's tags) is stale. It is
not read again and is left out of later exports; the held guest answers that gave it end
`skipped_stale` (`result.reason` `key_changed`), and the place becomes `stale`, unless someone marked
it "Needs a visit".

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
