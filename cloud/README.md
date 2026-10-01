# OCC Pricer Cloud (MVP)

The multi-store web version of OCC Pricer, live at **https://cardbox.trading**. One container serves the React client
and the API; PostgreSQL holds the data.

- **Free price check** at `/`: search a card and see its Scryfall market price. No account, as Scryfall's terms require.
- **Store workflow** under `/app` (sign-in, 30-day trial): trade entry with store credit, check or split payouts,
  customers linked by phone number, trade history, tiered buy rates, staff accounts, and the 19-column receiving POS CSV.

Pricing, condition multipliers, settlement and the POS CSV come from the desktop app's own classes
(`SettlementEngine`, `PricingService`, `TradePosEncoder`, ...), compiled directly from `../src` (see `api/pom.xml`),
so web and desktop produce the same offers and cent allocations.

## Layout

| Path | What |
|---|---|
| `api/` | Spring Boot 3.5 on Java 21, JDBC + Flyway (`api/src/main/resources/db/migration`) |
| `web/` | React + Vite client |
| `Dockerfile` | Builds both into one image; build from the repo root |
| `infra/main.bicep` | Azure resources |
| `deploy.sh` | Deploys to Azure with the Azure CLI |

## Run locally

```sh
docker run -d --name occpg -e POSTGRES_USER=occ -e POSTGRES_PASSWORD=occ -e POSTGRES_DB=occ -p 5432:5432 postgres:17-alpine
cd cloud/api
export APP_SESSION_SECRET=dev-secret-dev-secret-dev-secret-012345 APP_SECURE_COOKIE=false
mvn -DskipTests package
java -jar target/occ-pricer-cloud.jar import-catalog            # downloads Scryfall bulk data (~500 MB)
java -jar target/occ-pricer-cloud.jar                           # API on :8080
cd ../web && npm install && npm run dev                          # client on :5173, proxies /api to :8080
```

`import-catalog --app.catalog.file=src/test/resources/cards-fixture.json` loads a five-card fixture instead of Scryfall.
`mvn verify` runs the integration tests against PostgreSQL in Docker.

## Azure

Everything lives in one resource group:

| Resource | SKU | Purpose |
|---|---|---|
| Container App `occpricer-app` | Consumption, 0.5 vCPU / 1 GiB, scales to zero | Web client + API |
| Container Apps Job `occpricer-catalog-import` | Consumption, daily 10:30 UTC | Scryfall price import |
| PostgreSQL Flexible Server | Burstable B1ms, 32 GB | Data |
| Container Registry | Basic | Images, built with `az acr build` |
| Key Vault | Standard | Database password and session signing key |
| Log Analytics | Pay as you go, 0.5 GB/day cap | Logs |

Deploy or update (idempotent):

```sh
az login --use-device-code --tenant b5a8b81b-a80c-4aaa-b3cc-2e54736c0fe4
cloud/deploy.sh
```

### Domain

`cardbox.trading` is registered at Cloudflare and its DNS is hosted there. Both `cardbox.trading` and
`www.cardbox.trading` are bound to the Container App with free Azure-managed certificates, which Azure renews on its
own; `customDomains` in `infra/main.bicep` keeps the bindings on every deploy. The records, all set to
**DNS only** (grey cloud) because Azure cannot issue or renew the certificates through Cloudflare's proxy:

| Type | Name | Value |
|---|---|---|
| A | `@` | the environment's static IP (`az containerapp env show -g occ-pricer -n occpricer-env --query properties.staticIp`) |
| TXT | `asuid` | the app's verification id (`az containerapp show -g occ-pricer -n occpricer-app --query properties.customDomainVerificationId`) |
| CNAME | `www` | the app's default hostname (`az containerapp show -g occ-pricer -n occpricer-app --query properties.configuration.ingress.fqdn`) |
| TXT | `asuid.www` | the same verification id |

A managed certificate can only be issued after its hostname is on the app, so on a brand-new environment (which also
gets a new IP) deploy once with `customDomains=[]`, update the DNS records, then bind each hostname before redeploying
normally:

```sh
az containerapp hostname add -g occ-pricer -n occpricer-app --hostname cardbox.trading
az containerapp env certificate create -g occ-pricer -n occpricer-env --hostname cardbox.trading \
  --certificate-name cardbox-trading --validation-method HTTP
az containerapp hostname bind -g occ-pricer -n occpricer-app --hostname cardbox.trading \
  --environment occpricer-env --certificate cardbox-trading
# Repeat for www.cardbox.trading with --certificate-name www-cardbox-trading --validation-method CNAME.
```

The certificate names must stay `<hostname with dots as dashes>`, which is what the Bicep expects.

Because the app scales to zero, the first request after an idle period waits for the JVM to start (roughly 10 to 20 seconds).

## Not in the MVP yet

Stripe billing (trials are tracked, and an ended trial locks the store workflow), Entra External ID sign-in,
PostgreSQL row-level security (tenant isolation is enforced in every query and covered by a test),
bounties, trade editing and deletion, receipts as PDF, and importing a store's desktop history.
