// OCC Pricer cloud MVP. Deployed into an existing resource group by cloud/deploy.sh.
// Stage 1 (deployApps=false) creates the registry, vault, database and environment;
// stage 2 adds the web/API container app and the nightly catalog import job once the image exists.

@description('Short lowercase prefix for resource names.')
param prefix string = 'occpricer'

param location string = resourceGroup().location

@description('Object id of the person running the deployment; granted rights to write vault secrets.')
param deployerObjectId string

param deployApps bool = false

@description('Full image reference, e.g. occpricerabc.azurecr.io/occ-pricer:20260930-1.')
param image string = ''

@description('Public hostnames for the app. Each gets a free Azure-managed certificate; DNS must already point here (see cloud/README.md).')
param customDomains array = ['cardbox.trading', 'www.cardbox.trading']

@secure()
@description('PostgreSQL administrator password (read from Key Vault by deploy.sh).')
param postgresPassword string = ''

var suffix = uniqueString(resourceGroup().id)
var postgresUser = 'occadmin'
var databaseName = 'occ'

resource logs 'Microsoft.OperationalInsights/workspaces@2023-09-01' = {
  name: '${prefix}-logs'
  location: location
  properties: {
    sku: { name: 'PerGB2018' }
    retentionInDays: 30
    workspaceCapping: { dailyQuotaGb: json('0.5') } // hard cap so logging can't run up the bill
  }
}

resource identity 'Microsoft.ManagedIdentity/userAssignedIdentities@2023-01-31' = {
  name: '${prefix}-app-identity'
  location: location
}

resource registry 'Microsoft.ContainerRegistry/registries@2023-07-01' = {
  name: '${prefix}${suffix}'
  location: location
  sku: { name: 'Basic' }
  properties: { adminUserEnabled: false }
}

resource vault 'Microsoft.KeyVault/vaults@2023-07-01' = {
  name: '${prefix}-kv-${take(suffix, 8)}'
  location: location
  properties: {
    tenantId: subscription().tenantId
    sku: { family: 'A', name: 'standard' }
    enableRbacAuthorization: true
    enableSoftDelete: true
    softDeleteRetentionInDays: 7
  }
}

// Built-in role ids.
var acrPull = '7f951dda-4ed3-4680-a7ca-43fe172d538d'
var secretsUser = '4633458b-17de-408a-b874-0445c86b69e6'
var secretsOfficer = 'b86a8fe4-44ce-4948-aee5-eccb2c155cd7'

resource appPull 'Microsoft.Authorization/roleAssignments@2022-04-01' = {
  name: guid(registry.id, identity.id, acrPull)
  scope: registry
  properties: {
    principalId: identity.properties.principalId
    principalType: 'ServicePrincipal'
    roleDefinitionId: subscriptionResourceId('Microsoft.Authorization/roleDefinitions', acrPull)
  }
}

resource appSecrets 'Microsoft.Authorization/roleAssignments@2022-04-01' = {
  name: guid(vault.id, identity.id, secretsUser)
  scope: vault
  properties: {
    principalId: identity.properties.principalId
    principalType: 'ServicePrincipal'
    roleDefinitionId: subscriptionResourceId('Microsoft.Authorization/roleDefinitions', secretsUser)
  }
}

resource deployerSecrets 'Microsoft.Authorization/roleAssignments@2022-04-01' = {
  name: guid(vault.id, deployerObjectId, secretsOfficer)
  scope: vault
  properties: {
    principalId: deployerObjectId
    roleDefinitionId: subscriptionResourceId('Microsoft.Authorization/roleDefinitions', secretsOfficer)
  }
}

resource postgres 'Microsoft.DBforPostgreSQL/flexibleServers@2024-08-01' = if (deployApps) {
  name: '${prefix}-pg-${take(suffix, 8)}'
  location: location
  sku: { name: 'Standard_B1ms', tier: 'Burstable' }
  properties: {
    version: '17'
    administratorLogin: postgresUser
    administratorLoginPassword: postgresPassword
    storage: { storageSizeGB: 32, autoGrow: 'Enabled' }
    backup: { backupRetentionDays: 7, geoRedundantBackup: 'Disabled' }
    highAvailability: { mode: 'Disabled' }
    network: { publicNetworkAccess: 'Enabled' }
  }
}

// Allows other Azure services (our container apps) to connect; TLS is required by default.
resource allowAzure 'Microsoft.DBforPostgreSQL/flexibleServers/firewallRules@2024-08-01' = if (deployApps) {
  parent: postgres
  name: 'AllowAzureServices'
  properties: { startIpAddress: '0.0.0.0', endIpAddress: '0.0.0.0' }
}

resource trigram 'Microsoft.DBforPostgreSQL/flexibleServers/configurations@2024-08-01' = if (deployApps) {
  parent: postgres
  name: 'azure.extensions'
  properties: { value: 'PG_TRGM', source: 'user-override' }
  dependsOn: [allowAzure]
}

resource database 'Microsoft.DBforPostgreSQL/flexibleServers/databases@2024-08-01' = if (deployApps) {
  parent: postgres
  name: databaseName
  dependsOn: [trigram]
}

resource environment 'Microsoft.App/managedEnvironments@2024-03-01' = {
  name: '${prefix}-env'
  location: location
  properties: {
    appLogsConfiguration: {
      destination: 'log-analytics'
      logAnalyticsConfiguration: {
        customerId: logs.properties.customerId
        sharedKey: logs.listKeys().primarySharedKey
      }
    }
    workloadProfiles: [{ name: 'Consumption', workloadProfileType: 'Consumption' }]
  }
}

// Apex domains validate over HTTP (A record to the environment IP); subdomains validate through their CNAME.
// A managed certificate can only be issued once the hostname is on the app, so the very first binding is done
// with the Azure CLI (cloud/README.md); later deployments find the certificates in place and keep them.
resource certificates 'Microsoft.App/managedEnvironments/managedCertificates@2024-03-01' = [for domain in customDomains: if (deployApps) {
  parent: environment
  name: replace(domain, '.', '-')
  location: location
  properties: {
    subjectName: domain
    domainControlValidation: length(split(domain, '.')) > 2 ? 'CNAME' : 'HTTP'
  }
}]

var secrets = [
  { name: 'db-password', keyVaultUrl: '${vault.properties.vaultUri}secrets/postgres-password', identity: identity.id }
  { name: 'session-secret', keyVaultUrl: '${vault.properties.vaultUri}secrets/session-secret', identity: identity.id }
]
var env = [
  { name: 'DATABASE_URL', value: 'jdbc:postgresql://${postgres.?properties.fullyQualifiedDomainName ?? ''}:5432/${databaseName}?sslmode=require' }
  { name: 'DATABASE_USER', value: postgresUser }
  { name: 'DATABASE_PASSWORD', secretRef: 'db-password' }
  { name: 'APP_SESSION_SECRET', secretRef: 'session-secret' }
]

resource app 'Microsoft.App/containerApps@2024-03-01' = if (deployApps) {
  name: '${prefix}-app'
  location: location
  identity: { type: 'UserAssigned', userAssignedIdentities: { '${identity.id}': {} } }
  properties: {
    environmentId: environment.id
    workloadProfileName: 'Consumption'
    configuration: {
      ingress: {
        external: true
        targetPort: 8080
        transport: 'auto'
        allowInsecure: false
        customDomains: [for domain in customDomains: {
          name: domain
          bindingType: 'SniEnabled'
          certificateId: '${environment.id}/managedCertificates/${replace(domain, '.', '-')}'
        }]
      }
      registries: [{ server: registry.properties.loginServer, identity: identity.id }]
      secrets: secrets
    }
    template: {
      containers: [
        {
          name: 'app'
          image: image
          resources: { cpu: json('0.5'), memory: '1Gi' }
          env: env
          probes: [
            { type: 'Startup', httpGet: { path: '/actuator/health/liveness', port: 8080 }, periodSeconds: 3, failureThreshold: 40 }
            { type: 'Liveness', httpGet: { path: '/actuator/health/liveness', port: 8080 }, periodSeconds: 30 }
            { type: 'Readiness', httpGet: { path: '/actuator/health/readiness', port: 8080 }, periodSeconds: 10 }
          ]
        }
      ]
      // Scales to zero when idle. One replica keeps the in-memory rate limiter meaningful; raise later.
      scale: { minReplicas: 0, maxReplicas: 1, rules: [{ name: 'http', http: { metadata: { concurrentRequests: '50' } } }] }
    }
  }
  dependsOn: [appPull, appSecrets, database, certificates]
}

resource importJob 'Microsoft.App/jobs@2024-03-01' = if (deployApps) {
  name: '${prefix}-catalog-import'
  location: location
  identity: { type: 'UserAssigned', userAssignedIdentities: { '${identity.id}': {} } }
  properties: {
    environmentId: environment.id
    workloadProfileName: 'Consumption'
    configuration: {
      triggerType: 'Schedule'
      scheduleTriggerConfig: { cronExpression: '30 10 * * *', parallelism: 1, replicaCompletionCount: 1 } // daily, UTC
      replicaTimeout: 3600
      replicaRetryLimit: 1
      registries: [{ server: registry.properties.loginServer, identity: identity.id }]
      secrets: secrets
    }
    template: {
      containers: [
        {
          name: 'import'
          image: image
          args: ['import-catalog']
          resources: { cpu: json('1.0'), memory: '2Gi' }
          env: env
        }
      ]
    }
  }
  dependsOn: [appPull, appSecrets, database]
}

output registryName string = registry.name
output registryServer string = registry.properties.loginServer
output vaultName string = vault.name
output appUrl string = deployApps ? 'https://${empty(customDomains) ? app.?properties.configuration.ingress.fqdn ?? '' : customDomains[0]}' : ''
output importJobName string = deployApps ? importJob.name : ''
