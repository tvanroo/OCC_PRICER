#!/usr/bin/env bash
# Deploys the OCC Pricer cloud MVP into an existing Azure resource group.
# Needs the Azure CLI, signed in (az login --use-device-code --tenant "$AZURE_TENANT_ID").
# Safe to re-run: secrets are created once and kept, the image is rebuilt and the app updated.
set -euo pipefail

TENANT="${AZURE_TENANT_ID:-b5a8b81b-a80c-4aaa-b3cc-2e54736c0fe4}"
SUBSCRIPTION="${AZURE_SUBSCRIPTION_ID:-8dd9b7ff-76b3-48d1-956e-9874c5d7c8f6}"
GROUP="${AZURE_RESOURCE_GROUP:-occ-pricer}"
PREFIX="${PREFIX:-occpricer}"
TAG="${IMAGE_TAG:-$(date -u +%Y%m%d-%H%M%S)-$(git rev-parse --short HEAD)}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
INFRA="$ROOT/cloud/infra/main.bicep"

az account set --subscription "$SUBSCRIPTION"
[ "$(az account show --query tenantId -o tsv)" = "$TENANT" ] || { echo "Signed in to the wrong tenant" >&2; exit 1; }
for ns in Microsoft.App Microsoft.ContainerRegistry Microsoft.DBforPostgreSQL Microsoft.KeyVault Microsoft.OperationalInsights Microsoft.ManagedIdentity; do
  az provider register --namespace "$ns" --wait >/dev/null
done
DEPLOYER="$(az ad signed-in-user show --query id -o tsv)"

echo "== Stage 1: registry, vault, container environment"
out=$(az deployment group create -g "$GROUP" -n "${PREFIX}-base" -f "$INFRA" \
  -p prefix="$PREFIX" deployerObjectId="$DEPLOYER" deployApps=false --query properties.outputs -o json)
REGISTRY=$(jq -r .registryName.value <<<"$out")
SERVER=$(jq -r .registryServer.value <<<"$out")
VAULT=$(jq -r .vaultName.value <<<"$out")

echo "== Secrets (created once, then reused)"
ensure_secret() {
  if ! az keyvault secret show --vault-name "$VAULT" -n "$1" --query id -o tsv >/dev/null 2>&1; then
    # Role assignments can take a minute to apply; retry the first write.
    for i in 1 2 3 4 5 6; do
      az keyvault secret set --vault-name "$VAULT" -n "$1" --value "$(openssl rand -base64 36 | tr -d '/+=' | cut -c1-40)Aa1" -o none && return
      sleep 20
    done
    echo "Could not write secret $1" >&2; exit 1
  fi
}
ensure_secret postgres-password
ensure_secret session-secret
PG_PASSWORD=$(az keyvault secret show --vault-name "$VAULT" -n postgres-password --query value -o tsv)

echo "== Building image $SERVER/occ-pricer:$TAG in Azure"
az acr build -r "$REGISTRY" -t "occ-pricer:$TAG" -f "$ROOT/cloud/Dockerfile" "$ROOT"

echo "== Stage 2: database, app and nightly import job"
out=$(az deployment group create -g "$GROUP" -n "${PREFIX}-apps" -f "$INFRA" \
  -p prefix="$PREFIX" deployerObjectId="$DEPLOYER" deployApps=true image="$SERVER/occ-pricer:$TAG" \
     postgresPassword="$PG_PASSWORD" --query properties.outputs -o json)
URL=$(jq -r .appUrl.value <<<"$out")
JOB=$(jq -r .importJobName.value <<<"$out")

if [ "${SKIP_IMPORT:-0}" != 1 ]; then
  echo "== Loading the card catalog now (the job also runs nightly)"
  az containerapp job start -g "$GROUP" -n "$JOB" -o none
fi

echo "Deployed: $URL"
