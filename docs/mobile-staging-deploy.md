# Deploy the mobile OAuth backend with Docker Compose

This guide deploys the `feat/mobile-oauth` backend as an isolated service with
PostGIS and Caddy. It uses [compose.mobile.yml](../compose.mobile.yml) with
Docker Compose alone. The separate `compose.production.yml` is tailored to an
existing host's shared ingress and is not used here. See
[Mobile OAuth](mobile-oauth.md) for the sign-in protocol and scopes.

## Prerequisites

- A server with Docker Compose, persistent storage, and enough memory to build
  the Scala backend.
- A public DNS name pointing to the server, with ports 80 and 443 available
  for Caddy's automatic HTTPS certificate.
- An OSM OAuth 2 application registered for the backend callback
  `https://YOUR_HOST/oauth/mobile/callback`. Start with
  `master.apis.dev.openstreetmap.org` and request only `read_prefs`. The OSM
  client secret stays on the server.

Use a separate database, OSM OAuth application, and secrets from any existing
MapRoulette deployment. This setup does not modify `maproulette.org`.

## Configure

Check out `feat/mobile-oauth`. In the checkout, create a `.env` file with your
DNS hostname and deployment credentials:

```dotenv
PUBLIC_HOST=mr-api.example.org
POSTGRES_PASSWORD=<unique-random-database-password>
APPLICATION_SECRET=<unique-random-Play-secret>
MAPROULETTE_SECRET_KEY=<different-unique-random-encryption-secret>
MR_OAUTH_CONSUMER_KEY=<development-OSM-client-ID>
MR_OAUTH_CONSUMER_SECRET=<development-OSM-client-secret>
# Optional: enables osm:tagfix (choice answers applied to OSM). 32 random bytes:
# openssl rand -base64 32
MR_MOBILE_OSM_TOKEN_KEY=<base64-key>
```

Use only a hostname in `PUBLIC_HOST`, without `https://` or a path. Compose
derives the public origin and backend callback from it. Keep
`APPLICATION_SECRET` and `MAPROULETTE_SECRET_KEY` stable across restarts and
deployments. Run `chmod 600 .env`; this file is ignored by Git and excluded
from the Docker build context. The default OSM server is the development
server. To use another OSM environment, set `MR_OSM_SERVER` in `.env` and
register the matching callback there.

The included native client registrations are for the SDK examples:
`maproulette-android-example` and `maproulette-ios-example`, both with callback
`org.maproulette.example:/oauth2redirect`. Change
`conf/mobile-staging.conf` and rebuild for a different native app. The native
client ID is public; never put the OSM client secret in an app.

The staging config also registers the admin web app: client
`maproulette-mobile-admin`, redirect `https://admin.mr-dev.osm.lol/callback`,
scope `mobile:admin` (MapRoulette super-users only), and the admin origin
`https://admin.mr-dev.osm.lol` for CORS. This follows the planned rename of
`mr-api.osm.lol` to `mr-dev.osm.lol`, with the admin app at
`admin.<deployment host>`. To use another admin origin, add
`MR_MOBILE_ADMIN_ORIGIN` to the API service's environment. The Compose files
don't pass it, because an empty value would turn admin CORS off. Config clients seed the
`mobile_oauth_clients` table; once a client is edited through the
[admin API](mobile-admin-api.md), config no longer changes it (see
[Clients](mobile-oauth.md#clients)).

## Start and verify

```sh
docker compose --env-file .env -f compose.mobile.yml config --quiet
docker compose --env-file .env -f compose.mobile.yml up -d --build
docker compose --env-file .env -f compose.mobile.yml ps
```

The first build can take several minutes. Compose starts PostGIS, waits for
its health check, starts the API, then starts Caddy after the API is healthy.
Caddy publishes ports 80 and 443; only the API can reach the private database
network. Play automatically applies database evolutions, including version
129 for mobile OAuth, 130 for choice tasks and 131 for the client table and
admin audit log.

Replace the hostname below with your `PUBLIC_HOST`:

```sh
curl --fail https://mr-api.example.org/ping
curl -i https://mr-api.example.org/oauth/mobile/me
docker compose --env-file .env -f compose.mobile.yml exec -T db \
  psql -U maproulette -d maproulette -Atc 'SELECT max(id) FROM play_evolutions'
```

Expect HTTP 200 for `/ping`, HTTP 401 for `/oauth/mobile/me` without a bearer
token, and evolution 131. A 404 on `/oauth/mobile/me` means the responding
backend does not have mobile OAuth enabled. Complete a browser sign-in from
the approved Android app to verify OSM login, consent, app callback, token
exchange, and authenticated identity together.

## Back up and roll back

Create an off-repository PostgreSQL dump, include it in an encrypted off-host
backup, and keep the stable secrets in your secret manager:

```sh
mkdir -p "$HOME/maproulette-backups"
chmod 700 "$HOME/maproulette-backups"
docker compose --env-file .env -f compose.mobile.yml exec -T db \
  pg_dump -U maproulette -d maproulette -Fc \
  > "$HOME/maproulette-backups/maproulette.dump"
```

Verify recovery by restoring the dump into a **separate** PostGIS database
and comparing challenge and task counts. Do not restore over the running
database as the first test.

To roll back an API change, redeploy a known-good backend revision while
preserving the Compose `postgres-data` volume and the stable secrets. Validate
database compatibility before starting an older revision against the evolved
schema. Keep DNS, TLS, and the OSM callback aligned with the deployed origin.
