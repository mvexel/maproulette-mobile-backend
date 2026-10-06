# Upstream issue notes

Behavior found in the upstream MapRoulette backend while designing mobile task
completion. These notes are drafts for later filing with
`maproulette/maproulette-backend`; nothing here has been filed. Unless stated,
this fork does **not** change the behavior for web or API-key clients. The
mobile bearer gate (`MobileWriteRoutes`) keeps mobile tokens off these paths.

## 1. Any user can set any task to Deleted or Disabled

`Task.isValidStatusProgression` accepts a change to `4` (Deleted) or `9`
(Disabled) from any status, and `TaskDAL.setTaskStatus` does not check project
or challenge permissions. Any authenticated, non-guest user can therefore call
`PUT /api/v2/task/:id/4` (or `/9`) on a task they do not manage, as long as no
other user holds its lock.

Expected: only project managers/admins (or the challenge owner) can delete or
disable tasks; mappers should get 403.

Mobile: status codes other than 1, 2, 5 and 6 are rejected by the gate.

## 2. A different user can rewrite the same status and take over completion

`isValidStatusProgression(current, requested, allowChange)` returns `true` when
`current == requested`, before considering who completed the task. User B can
therefore `PUT /api/v2/task/:id/1` on a task user A already marked Fixed. The
update sets `completed_by = B` and `mapped_on = NOW()`, logs another status
action and can request review again, so B takes credit for A's work. No lock
is needed for this (see 4).

Expected: rewriting a completed status by someone other than `completed_by`
returns 400, like other changes between completed statuses.

Mobile: the SDK requires a successful `start` (lock) before resolving and
re-reads the task after uncertain outcomes, but the server does not enforce
this.

## 3. Cached task keeps stale `completedBy`/`mappedOn` after a status write

`TaskDAL.setTaskStatus` writes `task.copy(status, modified, review...)` into the
task cache without the `completed_by`, `mapped_on` and `completed_time_spent`
values it just wrote. `GET /api/v2/task/:id` can then show the new status with
the previous completer for up to the cache lifetime.

**Fixed in this fork for all clients**: the cached copy now carries those
fields as stored. Regression test: `TaskServiceSpec` "refresh cached completion
fields after a status write". A good upstream PR candidate.

## 4. Status writes do not require holding the lock

The status `UPDATE` matches when the caller holds the lock **or the task is
unlocked**. A client can resolve a task it never started. Combined with 1 and
2 this widens what an arbitrary user can change. Upstream may consider this
intentional (bulk or API workflows); worth a discussion rather than a bug.

## 5. `requestReview=false` overrides mandatory review

`PUT /api/v2/task/:id/:status?requestReview=false` skips review even for users
whose `needsReview` setting makes review mandatory. Expected: the query
parameter can request review but not opt out of a mandatory one.

Mobile: write routes reject any query string.

## 6. Fix/apply trusts client-supplied OSM changes

`POST /api/v2/task/:taskId/fix/apply` (`TaskController.applyTagFix`, with
`ChangesetProvider.submitOsmChange`) turns the request body
`TagChangeSubmission {comment, changes: [{osmId, osmType, version?, updates,
deletes}]}` directly into an OSMChange and uploads it with the user's stored
OSM token. Nothing checks the changes against the task's `cooperativeWork`, so
any authenticated caller can upload arbitrary tag edits to arbitrary OSM
elements in a MapRoulette changeset. The changeset comment also comes from the
client; only `source` comes from the challenge's check-in source.

Expected: the server builds the change from `cooperativeWork`, accepts only
user edits within the suggested keys, and uses the challenge's check-in
comment.

## 7. Fix/apply uploads to OSM before the MapRoulette status checks

`submitOsmChange` creates, uploads and closes the changeset before
`customTaskStatus(Fixed)` runs. The lock, status-progression, paused-challenge
and guest checks therefore run after the OSM edit is committed. If one fails,
OSM has changed but the task has not.

Expected: run the same checks (caller holds the lock, valid progression,
challenge not paused, not a guest) before creating a changeset.

## 8. Fix/apply can leave the request hanging

On success, `customTaskStatus(...)` runs inside
`submitOsmChange(...) onComplete { case Success(res) => ... }` before
`p success Ok(res)`. If `customTaskStatus` throws (403 locked by another user,
400 invalid progression, 400 paused), the exception escapes the callback and
the promise is never completed, so the request hangs until a timeout. Found by
code reading; not reproduced.

Expected: wrap the callback body in `Try` and complete the promise on every
branch.

Related: an OSM 401 becomes `OAuthNotAuthorizedException`, which returns
MapRoulette `401 NotAuthorized` and clears the web session (`withNewSession`).
Clients cannot tell a revoked OSM token from an expired MapRoulette session.

Mobile: none of these routes is reachable with a bearer token.

## 9. Swagger and route documentation gaps

- Status writes return 204, documented as "304 No Content".
- `GET /task/:id/start` returns 403 when another user holds the lock; the
  documented 409 means the caller holds a *different* task.
- `GET /task/:id/release` always returns 200, even when nothing was released.
- `PUT /task/:taskId/unlock/request` acquires the lock if the task is free,
  despite its name.
- Lock expiry is enforced only by the hourly `cleanLocks` job, so an expired
  lock stays valid for up to an extra hour.

## 10. Proposals from mobile choice challenges

These are additions this fork made for mobile choice tasks that could be useful upstream. Each
keeps stock behavior for existing clients.

- **`cooperativeWork.meta.type = 3` ("choice").** One OSM element, a `match` identity guard,
  several questions that each guard their own keys (`expect`) and offer fixed tag changes, and
  task-level outcomes. The server derives the OSM change from the stored payload, so clients send
  only `{"answers": {...}}`, which also addresses 6 and 7 above. Today upstream validates only
  `meta.version` and copies any `meta.type` into `challenges.cooperative_type` (last task wins,
  never reset). Format and rules: the SDK's `docs/mobile-choice-challenges.md` §2.
- **`cct` search filter.** A comma-separated list of `challenges.cooperative_type` values on the
  task box, marker, cluster and in-cluster endpoints (`SearchParametersMixin`
  `filterChallengeCooperativeType`, about 40 lines). Lets a client that supports only some task
  kinds ask for those. A non-integer value is a 400. The fork's separate `excludeStale=true`
  depends on a fork-only table (`choice_stale`) and is not part of this proposal. Note that `cct`
  is read from the query string only; `SearchParametersFormat` does not round-trip it through
  the search cookie (neither direction), like several other fork-irrelevant fields.
- **`addFileTasks?report=true`.** Line-by-line ingest currently swallows task creation errors
  (`ChallengeProvider._createNewTask` logs and returns `None`), so callers cannot tell which lines
  were dropped. With `report=true` the route returns
  `200 {"created", "updated", "rejected": [{"line", "errors"}]}`, and a rejected line no longer
  marks the challenge FAILED. Without the flag the route still returns 204.
- **Changeset left open.** `ChangesetProvider.submitOsmChange` did not close the changeset when
  building the change failed after the changeset was created. Fixed in this fork for all clients.
- **A GET with a side effect, on purpose.** The fork's `GET /task/:id/choice/check` records a
  system observation (`choice_stale`) when OSM shows the element changed. It never touches a
  task's status, lock or history, and repeating it changes nothing. Upstream may prefer a POST
  or a background sweep for this.
- **Changeset matching vs. one-transaction writes.** With `changesets.enabled=true`,
  `TaskDAL.setTaskStatus` commits its connection from a `Future` and may then overwrite
  `tasks.changeset_id` via `matchToOSMChangeSet`. The choice submit stores the changeset id in the
  status transaction; deployments that enable matching should skip it for tasks that already have
  one. Default is disabled; the fork does not change it.
- **Test infrastructure.** `TestDatabase` overrides `db.default.*` but the background pool copies
  `db.default` when the config is parsed, so in tests it pointed at the default URL. The fork's
  `TestDatabase` overrides `db.background.*` too.
