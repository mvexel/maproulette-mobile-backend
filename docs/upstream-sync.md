# Evolution numbers and upstream syncs

This fork shares one database schema history with upstream
[`maproulette/maproulette-backend`](https://github.com/maproulette/maproulette-backend).
This page says who may use which evolution number, how to claim one, and how to
merge upstream without breaking databases that are already deployed. It covers
guardrail 4 (upstream sync on a cadence) and risk 5 (linear evolution numbers)
of the keep-or-redo decision.

## Why there is no reserved high range

Play reads `conf/evolutions/default/1.sql`, `2.sql`, … and **stops at the first
missing number** (`ResourceEvolutionsReader.evolutions` in Play 2.9.11's
`EvolutionsApi.scala`, a `Seq.unfold(1)` over `loadResource`). A fork range such
as 1000+ would never be read: 138–999 don't exist, so Play stops at 137. Every
alternative costs more than it saves:

| Option | Why not |
| --- | --- |
| High range plus no-op filler files | Hundreds of empty files, each a row in `play_evolutions`, and upstream catches up with the filler sooner or later |
| A second evolutions namespace (another `db` config with its own `metaTable`) | The fork's migrations 129–137 are already applied in the shared namespace on dev and stage. Moving them means rewriting `play_evolutions` by hand on every database, and Play doesn't order migrations across two namespaces, while fork tables reference upstream ones |
| Renumber the fork's migrations at each sync | Play compares numbers and hashes with `play_evolutions`. A renumbered migration looks changed, and with `autoApplyDowns = false` the app refuses to start |

So the fork and upstream keep **one gapless sequence**, and the rule is:

- **1–128** are upstream's own files, unchanged (the *base*).
- **Above 128 the fork owns every number.** Fork migrations are never renumbered.
- **Upstream migrations above 128 are copied** to the next free number when we
  sync, with a comment naming the upstream file. Upstream's number is never used
  as is.

At the last sync (upstream `bbdc2d64`, 2026-10-10) upstream's newest migration was
`129.sql`. It is the fork's `133.sql`.

A database migrated by this fork can't go back to upstream code, and the reverse
also holds, because the numbers mean different things above 128. That was already
true once 129 was used.

## The ledger

[`conf/evolutions/ledger.txt`](../conf/evolutions/ledger.txt) has one row for each
number above the base: `fork`, `upstream:<m>` (a copy of upstream's `m.sql`), or
`reserved` (claimed by an open PR, no file yet). Its `synced` line records the last
upstream commit merged and upstream's highest migration at that commit.

`EvolutionLedgerSpec` (in `sbt test`, no database needed) fails when:

- a number is missing, which would make Play stop there, or a file isn't named `N.sql`;
- a file above the base has no row, or two rows claim one number;
- a `reserved` row has a file, or sits below the highest file;
- a `fork` or `upstream` row has no file;
- an upstream copy doesn't name its source (`maproulette-backend <m>.sql`) in a comment;
- an upstream migration between the base and `synced` has no copy, or the copies are out of upstream's order.

## Claiming a number

1. Fetch `feat/mobile-oauth` and take the lowest number with no row in the ledger.
2. In the PR's first commit, add `<n> reserved <PR or branch> (<owner>)`. If
   another open PR already claimed that number, the ledger line conflicts and you
   take the next one. That's cheaper than finding out after merge.
3. When you add `<n>.sql`, change the row to `<n> fork <short description>`.
4. If a PR is abandoned, delete its reserved row. Merge migrations in number
   order. If a higher number merges first, the base branch has a gap, and the spec
   fails until the lower number merges.

Never change a merged migration. A fix is a new migration.

## Syncing upstream

Cadence: once a month and before each pilot (guardrail 4). Ada's Monday upstream
check decides whether a sync is due earlier, for example for a security fix. The
sync is a normal PR against `feat/mobile-oauth`, merged with a merge commit.

1. Set up and branch:

   ```sh
   git remote add upstream https://github.com/maproulette/maproulette-backend.git  # once
   git fetch upstream main
   git checkout -b claude/upstream-sync-<date> origin/feat/mobile-oauth
   git merge --no-ff upstream/main
   ```

2. List upstream's new migrations since the last sync:

   ```sh
   git diff --name-status <synced commit> upstream/main -- conf/evolutions/default/
   ```

   - **Added files above the base:** each one is either an add/add conflict (the fork
     already has that number) or merged in cleanly at a free number. In both cases,
     keep the fork's file, write upstream's content to the next free number, and
     start its `!Ups` section with
     `-- Upstream maproulette-backend <m>.sql, renumbered for the fork.` Use
     `git show upstream/main:conf/evolutions/default/<m>.sql` for the content.
     Add `<n> upstream:<m> <description>` to the ledger. Copy in upstream's order.
   - **Changed files at or below the base:** stop and raise it with Ada. Deployed
     databases have already applied these files, and Play won't start with a changed
     hash. Usually the answer is to keep our version and copy upstream's change into
     a new migration.
   - **Open reserved rows** in the ledger keep their numbers when their migration
     has already run on a database (dev, for example). Upstream copies then take
     the numbers after them. Otherwise either side may move, but tell the PR's
     owner.

3. Update `synced <commit> <upstream's highest migration>` in the ledger, and the
   base commit in `docs/fork-changes.md`.
4. Read every upstream migration you copied for anything that touches tables the
   fork changed (`grants`, `tasks`, `challenges`, `users`, `projects`).
5. Resolve the remaining conflicts. For each upstream file the fork edits, the
   list is in `docs/fork-changes.md`. Run `sbt scalafmtCheck Test/scalafmtCheck`
   and `sbt test`, including the choice and mobile specs with their database
   (`docs/testing.md`).
6. Open the PR. The description lists upstream's new migrations and the numbers
   they got. A sync PR goes through review like any other.
7. Deploying is a separate step, and Martijn does it: dev, then stage, then prod.
   Evolutions auto-apply on start (`autoApply = true`, `autoApplyDowns = false`),
   so the deploy note lists the new numbers and a rollback point.

## Shrinking the fork

Each upstream file the fork edits raises the cost of a sync. New fork features
belong in fork-owned packages and migrations (guardrail 1). The proposals in
[upstream-issues.md §10](upstream-issues.md#10-proposals-from-mobile-choice-challenges)
would remove fork edits if upstream took them. Filing them is an outside
communication, so it waits for Martijn.
