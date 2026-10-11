# Selected stage pending-guest preparation

This is source preparation for C-001 D-017, not deployment or registration approval.
Base: `e2fe49d2656675d610593ca2a7eea9fc9f128ee0`.

`compose.field.yml` passes guest/mail settings to the container, with guest default
`false` and mail provider default `none`. It still selects `mobile-field.conf` and
does not add a guest-capable native client to shared field/production config.
For the separately approved **stage** deployment, select both
`compose.field.yml` and `compose.field-stage-pending.yml`; the latter selects
`mobile-field-stage-pending.conf`. Enable guests explicitly with
`MR_MOBILE_GUESTS_ENABLED=true` only in that reviewed stage Stack environment.
Keep existing stage project, aliases, volumes, encryption secret references and
all other field resource settings. No Stack/environment has been changed here.

The selected config adds client `maproulette-ios-choice`, callback
`lol.osm.maproulette.choice:/oauth2redirect`, scopes `tasks:read guest`.
Both native apps share this ID/callback. `guest` is client capability and a guest
token scope; it cannot be requested in an OAuth grant. Neither `tasks:write` nor
`osm:tagfix` is permitted by this native client. The existing field config retains
`allowTaskWrites=true`, `writeControlEnabled=true`; the audited database policy
must stay **Disabled** (`mobile_write_policy.enabled=false`). Evolution132 creates
the policy false; it does not prove the current stage database value. This diff
does not change policy source, migrations or database rows.

## Runtime client and policy gate

Before any proposed stage release, obtain redacted read-only operator evidence
for the exact deployed image/config selection, policy and registered client.
`MobileClientRows.seed` runs once per process on first registry use. It inserts
missing config clients and updates existing config-seeded, never-admin-edited
rows only when `created_by` and `updated_by` are NULL. Admin-created/edited rows
are preserved. Removed untouched config clients are disabled. Accordingly,
config/fixture passing is not evidence of the effective stage registration.

Review current `maproulette-ios-choice` provenance, enabled state, redirects and
scopes. If an admin-owned existing row differs, prepare an individually reviewed
operator/admin mutation with the exact ID/callback/scopes above and request its
authorization. Never apply a blanket SQL reset or grant OSM write scopes. Verify
effective registry evidence after an authorized change, including cache/process
state; keep policy Disabled throughout. No mutation is supplied or executed here.

## Optional mail and unfinished claim gate

The container now accepts `MR_GUEST_MAIL_PROVIDER` (default `none`),
`MR_GUEST_MAIL_FROM`, `MR_CLAIM_ORIGIN`, `MR_POSTMARK_SERVER_TOKEN` and optional
`MR_POSTMARK_MESSAGE_STREAM` (all otherwise empty). Existing application config
reads these variables. No secret, sender, provider selection or mail send is
changed. An empty origin/from/token is not Postmark readiness. An approved mail
deployment requires reviewed provider/sender/secret-reference/origin evidence.

This base contains pending receipt and guest email source, not the separate
claim/publish branch stack. Novice pending receipt does not require claim
publication. Optional claim-client registration, claim callback/CORS, integrated
claim sources/final-head tests and participant finish-later claim links remain
unsatisfied gates. This diff does not reconcile or publish that stack.

## Focused verification

The fixture is an exact copy of the synthetic team Woodbine draft, SHA256
`86b2cc388f7b90d7a44e417da83bc1b379cf9b75929cefb20b937c77b5a6483a`.
IDs101/42 are synthetic. No task import, live OSM/API call or service startup is
part of the checks. The pure probe compiles the unchanged `ChoiceWork` and
`MobileScopes` source sections; only unrelated full-app exception/settings wiring
is omitted. It executes real `ChoiceWork.validate` and actual Typesafe HOCON,
including a rejected present-tag guard and OAuth guest/write-scope denial.

Run offline with pre-fetched jars and Docker Compose CLI (no daemon required):

```sh
python3 scripts/check-stage-pending.py --jars /tmp/c001-backend-validator/jars \
  --fixture ../team/docs/cycles/C-001/woodbine-entrances/fixture-task.json
```

Pinned jars used: Scala compiler/library/reflect2.13.18, Play JSON/functional2.10.5,
Jackson core/annotations/datatype-jdk8/datatype-jsr3102.13.4 and databind2.13.4.2
(matching build overrides), Typesafe Config1.4.3. Jars were fetched from Maven
Central into `/tmp` for this bounded probe; the script does not download/install.

Full Scala test/format checks still require sbt1.12.11 and the existing build's
dependencies; database integration also requires an isolated test PostGIS DB.
This host lacks sbt and Docker socket access, so these tests are **unrun**:

```sh
sbt scalafmtCheckAll
sbt 'testOnly org.maproulette.auth.mobile.MobileFieldConfigSpec org.maproulette.provider.choice.ChoiceWorkSpec'
# With conf/test.conf directed to a disposable local test DB, never stage/prod:
sbt -Dconfig.file=conf/test.conf 'testOnly org.maproulette.framework.FrameworkMasterSuite -- -n choice'
```

The strengthened existing writes-off `MobileChoicePendingSpec` expects a pending
receipt, unchanged task status and zero fake OSM writes after the published tag.
Pure validator/config checks cannot substitute for executing this database test
or for native/device/runtime stage evidence.
