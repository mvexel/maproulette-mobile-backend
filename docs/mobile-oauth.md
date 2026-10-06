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

Apply database evolutions through the normal backend setup. Evolution 129 adds separate interaction, authorization-code, grant-family and token tables; it does not replace existing user credentials.

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
    # request task lifecycle writes.
    scopes = ["tasks:read", "tasks:write"]
  }]
}
```

The OSM callback and the app callback are different URLs. The app callback must match an entry exactly, including its path. HTTPS app links and reverse-domain custom schemes are accepted; query strings and fragments are not accepted in registered callbacks. There is no dynamic registration endpoint or public-client secret. Each host app owns its callback registration and operating-system integration.

For loopback development only, `allowInsecureLoopback = true` permits an HTTP backend callback on localhost. Production callbacks require HTTPS. The existing OSM consumer configuration supplies the server-side OSM client credentials. This flow requests only `read_prefs`; it does not authorize OSM uploads.

Default lifetimes are 10 minutes for a browser interaction, 2 minutes for an authorization code, 15 minutes for an access token, and 30 days for each refresh token. `accessSeconds` and `refreshSeconds` are configurable within the bounds enforced by `MobileOAuthSettings`.

## Endpoints

| Endpoint | Purpose |
| --- | --- |
| `GET /oauth/mobile/authorize` | Start browser login with `response_type=code`, `client_id`, exact `redirect_uri`, `scope` (`tasks:read` or `tasks:read tasks:write`), `state`, `code_challenge` and `code_challenge_method=S256`. |
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

## Scope and credential lifecycle

Scopes are a space-separated set. Every grant includes `tasks:read`; `tasks:write` is optional and only granted to clients whose `scopes` configuration lists it. Unknown, duplicate or write-only scope requests fail with `invalid_scope`. Stored and returned scope strings use the canonical order `tasks:read tasks:write`.

`tasks:read` permits the SDK's challenge discovery/detail/tags, task listing/detail, spatial queries, read-only marker query, and the new identity endpoint. The HTTP method **and exact route pattern** must be allowlisted in `MobileReadRoutes`. Legacy `whoami`, task-start/release and mutation routes are excluded. The user's existing permissions still apply. The marker query is a read-only PUT; not every GET is read-only.

`tasks:write` additionally permits exactly these task lifecycle routes (`MobileWriteRoutes`), acting as the signed-in MapRoulette user:

| Route | Purpose |
| --- | --- |
| `GET /api/v2/task/:id/start` | Lock the task, immediately before resolving it |
| `GET /api/v2/task/:id/release` | Release the caller's lock |
| `POST /api/v2/task/:id/skip` | Skip: count the skip, release the lock, keep the status |
| `PUT /api/v2/task/:id/(1\|2\|5\|6)` | Fixed, Not an issue, Already fixed, Too hard |

Mobile clients lock late: they call `start` only when the user commits a
resolution, then write the status (which releases the lock). Skip needs no
lock. `refreshLock` is therefore not allowed for bearer tokens.

These write requests must have **no query string and no body** (`400 invalid_request` otherwise). That excludes `requestReview` (the user's review setting applies), task `tags` and `completionResponses`. All other mutations, including statuses 0, 3, 4, 7, 8 and 9, comments, tags, bundles, review routes, `refreshLock`, unlock requests and anything that edits OpenStreetMap, return `403 insufficient_scope` for every bearer token. A `tasks:read`-only token on a lifecycle route also gets `403 insufficient_scope`.

Existing `tasks:read` grants keep working unchanged for reads. They are never upgraded: the user signs in again and approves the write permission, which creates a new grant. The consent page names the write permission only when it is requested. If an operator removes `tasks:write` from a client's configuration, that client's existing write grants stop authenticating (`401 invalid_token`) until the user signs in again.

Send the access token only in `Authorization: Bearer ...`. Do not combine it with a personal API key or a legacy authenticated session. Invalid mobile credentials never fall back to either. Disabling the provider restores legacy handling; the new endpoints return 404.

Codes are consumed atomically with token issuance. Refresh tokens rotate atomically; reuse revokes the family, including its current access tokens. Apps must serialize refresh and replace saved credentials atomically. A lost refresh response can require fresh login; do not retry an old refresh token as if rotation were idempotent. Revocation affects this grant family, not another app's grant or the user's personal API key.

Only credential hashes are stored in the new tables. Upstream OSM credentials are not returned to apps or persisted as mobile credentials. Existing users' web-session tokens and API keys are preserved during mobile login.

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
