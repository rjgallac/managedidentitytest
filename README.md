# Azure Functions identity demo (Java + Storage Queues on Flex Consumption)

This demo is deliberately small:

```text
input-queue  ->  Java Azure Function  ->  output-queue
```

The function reads a message from `input-queue`, adds a short prefix, and writes the result to `output-queue`. You run the same demo twice:

1. **Phase 1:** the function uses an Azure Storage connection string.
2. **Phase 2:** the function uses its Azure managed identity and Azure RBAC instead of a storage secret.

The code does not need to change between the two phases. Only the app setting and permissions change.

## What you need

- An Azure subscription.
- Java 17 and Maven. Keep the local Java version consistent with the Maven compiler configuration and the Azure runtime setting.
- Azure CLI, signed in with `az login`.
- Azure Functions Core Tools v4.
- Azure Storage Explorer (recommended for viewing queue messages).

The examples use PowerShell. Replace every value in angle brackets, such as `<resource-group>`, with your own value. Queue names must be lowercase and use numbers or hyphens.

## 1. Create the Azure resources

Use one resource group, one storage account, and one Flex Consumption Function App. The Function App's automatically-created host storage is intentionally left as-is; the demo queues use a separate, explicitly named setting called `StorageConnection`.

```powershell
$location = "uksouth"
$resourceGroup = "rg-func-identity-demo"
$storageAccount = "stfuncidentity1234" # 3-24 lowercase letters/numbers; globally unique
$functionApp = "func-identity-demo-1234" # globally unique

az group create --name $resourceGroup --location $location

# Confirm that the selected region currently supports Flex Consumption.
az functionapp list-flexconsumption-locations `
  --query "sort_by(@, &name)[].{Region:name}" `
  --output table

az storage account create `
  --name $storageAccount `
  --resource-group $resourceGroup `
  --location $location `
  --sku Standard_LRS `
  --kind StorageV2 `
  --allow-blob-public-access false

$storageConnection = az storage account show-connection-string `
  --name $storageAccount `
  --resource-group $resourceGroup `
  --query connectionString `
  --output tsv

az storage queue create --name input-queue --connection-string $storageConnection
az storage queue create --name output-queue --connection-string $storageConnection

az functionapp create `
  --name $functionApp `
  --resource-group $resourceGroup `
  --storage-account $storageAccount `
  --flexconsumption-location $location `
  --runtime java `
  --runtime-version 17 `
  --instance-memory 2048 `
  --functions-version 4
```

The `--flexconsumption-location` option is the important part: it creates the app in a Flex Consumption plan. Flex Consumption uses the `FC1` hosting-plan SKU, not the legacy Consumption `Y1` SKU. Because this command does not configure always-ready instances, the app only incurs compute charges while it is executing functions.

> Keep the connection string out of source control. The command above stores it only in the current PowerShell variable. Azure will store it as an encrypted Function App application setting in the next step.

## 2. Understand the project that is already here

The Maven project is in the `identity-demo` directory:

```text
identity-demo/
├── pom.xml
├── host.json
└── src/
    ├── main/java/com/example/QueueIdentityDemo.java
    └── test/java/com/example/FunctionTest.java
```

`QueueIdentityDemo.java` is the deployed function. It has a queue trigger on `input-queue` and a queue output on `output-queue`. Both bindings use the setting name `StorageConnection`.

`FunctionTest.java` is a unit test for the function. It supplies the message `hello` and verifies that the output binding receives `processed by function: hello`.

Build and test the project from the `identity-demo` directory:

```powershell
Set-Location .\identity-demo
mvn clean package
```

The generated `HttpResponseMessageMock.java` is no longer used by the queue test. It can remain in the project, or be removed later as cleanup.

### Deployment settings to check

Before using `azure-functions:deploy`, open `identity-demo/pom.xml` and update the generated values to match your Azure resources:

```xml
<functionAppName>your-function-app-name</functionAppName>
<resourceGroup>your-resource-group</resourceGroup>
<region>uksouth</region>
```

The project is configured for Java 17, Functions runtime v4, Linux, and the Flex Consumption plan. The Maven plugin must be version `1.34.0` or later for Flex Consumption. Do not add an `<appServicePlanName>` to this configuration: Flex Consumption apps must remain on their Flex plan, and Maven can otherwise try to move the app to a different plan. Update the generated `functionAppName`, `resourceGroup`, and `region` values to match your Azure resources before deploying. Keep the Java version consistent between the `<java.version>`, compiler `<source>/<target>`, and Azure runtime `<javaVersion>` settings.

`StorageConnection` is the **name** of a setting, not the connection string itself. The trigger and output binding both resolve that setting at runtime.

## 3. Phase 1 - use a connection string

### Local run

The local settings file must be in the same directory where you run `func start`: `identity-demo/local.settings.json`. Keep the real connection string only in that file or in Azure application settings; never commit it. The file should contain the storage connection string in both settings for this simple local run:

```json
{
  "IsEncrypted": false,
  "Values": {
    "AzureWebJobsStorage": "<storage-account-connection-string>",
    "FUNCTIONS_WORKER_RUNTIME": "java",
    "StorageConnection": "<storage-account-connection-string>"
  }
}
```

Do not commit `local.settings.json`; it contains a secret. The generated `.gitignore` should exclude it. If it does not, add it.

From the `identity-demo` directory, build and start the Java function host with the Azure Functions Maven plugin. For this Maven-based Java project, use `mvn azure-functions:run` rather than plain `func start`:

```powershell
Set-Location .\identity-demo
mvn clean package
mvn azure-functions:run -DenableDebug
```

If your populated `local.settings.json` is currently in the repository root, copy it into the project directory before starting the host:

```powershell
Copy-Item ..\local.settings.json .\local.settings.json -Force
```

Alternatively, edit `identity-demo/local.settings.json` and set both `AzureWebJobsStorage` and `StorageConnection` to the storage account connection string. Do not leave `AzureWebJobsStorage` empty.

In another PowerShell window, put a test message on the input queue:

```powershell
az storage message put `
  --queue-name input-queue `
  --content "hello from connection string" `
  --connection-string $storageConnection
```

Watch the local function log. Then inspect `output-queue` with Storage Explorer or:

```powershell
az storage message get `
  --queue-name output-queue `
  --connection-string $storageConnection `
  --num-messages 1 `
  --visibility-timeout 1
```

### Deploy and configure Azure

Package and deploy the function:

```powershell
mvn clean package azure-functions:deploy
```

Set the Azure application setting. The setting name must match the `connection` value in the Java annotations:

```powershell
az functionapp config appsettings set `
  --name $functionApp `
  --resource-group $resourceGroup `
  --settings "StorageConnection=$storageConnection"
```

Send a message again. View the deployed function's logs in the portal under **Function App > Monitoring > Log stream**, or run:

```powershell
func azure functionapp logstream $functionApp
```

At this point the function is using a shared secret. Anyone or anything that obtains the setting can use the storage account connection string.

## 4. Phase 2 - convert the queue binding to managed identity

The managed identity is an Entra ID service principal owned by the Function App. Azure issues tokens for it; your code no longer stores or reads a storage key.

### 4.1 Enable a system-assigned identity

```powershell
az functionapp identity assign `
  --name $functionApp `
  --resource-group $resourceGroup

$principalId = az functionapp identity show `
  --name $functionApp `
  --resource-group $resourceGroup `
  --query principalId `
  --output tsv

$storageId = az storage account show `
  --name $storageAccount `
  --resource-group $resourceGroup `
  --query id `
  --output tsv
```

### 4.2 Grant queue data permissions

The function needs to read from the trigger queue and write to the output queue. Grant the managed identity the built-in **Storage Queue Data Contributor** role on the storage account:

```powershell
az role assignment create `
  --assignee-object-id $principalId `
  --assignee-principal-type ServicePrincipal `
  --role "Storage Queue Data Contributor" `
  --scope $storageId
```

Role assignments can take a short time to become effective.

### 4.3 Replace the secret with identity-based settings

For an identity-based Functions binding, use a setting prefix with a double underscore. On Windows and in Azure app settings, `__` represents a hierarchy separator.

The queue service URI is:

```text
https://<storage-account-name>.queue.core.windows.net
```

Set these two app settings. `StorageConnection__queueServiceUri` replaces the old `StorageConnection` connection string. The `credential` value tells the binding to use the Function App's system-assigned identity.

```powershell
$queueServiceUri = "https://$storageAccount.queue.core.windows.net"

az functionapp config appsettings set `
  --name $functionApp `
  --resource-group $resourceGroup `
  --settings `
    "StorageConnection__queueServiceUri=$queueServiceUri" `
    "StorageConnection__credential=managedidentity"
```

Remove the old secret from the Function App:

```powershell
az functionapp config appsettings delete `
  --name $functionApp `
  --resource-group $resourceGroup `
  --setting-names StorageConnection
```

Restart the Function App so it reloads configuration:

```powershell
az functionapp restart --name $functionApp --resource-group $resourceGroup
```

The Java code remains unchanged: it still says `connection = "StorageConnection"`. The Functions host now finds the `StorageConnection__...` setting group and authenticates with the managed identity.

## 5. Test the managed-identity version

Send a new message using the storage account key only from your test machine:

```powershell
az storage message put `
  --queue-name input-queue `
  --content "hello from managed identity" `
  --connection-string $storageConnection
```

The Function App should process it and write to `output-queue`. Confirm the result in Storage Explorer or with `az storage message get`.

For a stronger demonstration, remove the local `$storageConnection` variable or close that shell, and verify that the deployed Function App still works. Its queue access now comes from its identity and RBAC role, not from the deleted app setting.

## What changed?

| Concern | Connection-string phase | Managed-identity phase |
|---|---|---|
| Function code | `connection = "StorageConnection"` | unchanged |
| Azure setting | `StorageConnection=<secret>` | `StorageConnection__queueServiceUri` and `StorageConnection__credential=managedidentity` |
| Authentication | Storage account key | Microsoft Entra token from the Function App identity |
| Permission model | Broad access represented by the key | Azure RBAC: `Storage Queue Data Contributor` |
| Secret rotation | You must rotate and redeploy/update settings | No storage key in the function binding |

## Troubleshooting

- **The function cannot start locally:** check that `local.settings.json` is valid JSON and that `StorageConnection` contains a complete connection string.
- **The host reports `Unable to create client for AzureWebJobsStorage`:** make sure `identity-demo/local.settings.json` exists, contains a non-empty `AzureWebJobsStorage`, and is being loaded from the directory where `func start` runs. Also verify that the storage account key has not been rotated or revoked.
- **The host reports an old extension bundle such as `1.8.1`:** `identity-demo/host.json` should reference the Functions v4 bundle range `[4.*, 5.0.0)`. Restart `mvn azure-functions:run` after changing it.
- **The host reports `0 functions loaded`:** run `mvn clean package` followed by `mvn azure-functions:run` from `identity-demo`. The Maven goal starts the host using the generated metadata under `target/azure-functions`.
- **The host reports `Message decoding has failed`:** this demo sends plain-text messages with Azure CLI, so `identity-demo/host.json` sets queue `messageEncoding` to `none`. Rebuild and restart the Maven host after changing it. Messages already moved to `input-queue-poison` must be re-added to `input-queue` for another test.
- **The function starts but no message is processed:** verify the queue names, the storage account, and that the deployed app has the `StorageConnection` setting group.
- **Managed identity gets 403/authorization errors:** confirm the role is `Storage Queue Data Contributor`, the scope is the correct storage account, and wait briefly for RBAC propagation.
- **Managed identity settings are ignored:** confirm the names use two underscores (`StorageConnection__queueServiceUri`) and that the Function App runs Functions runtime v4 or later.
- **Flex quota or region error:** run `az functionapp list-flexconsumption-locations --output table` and confirm that the selected region is listed. Flex Consumption has a regional quota shared by Flex apps; in the portal, open a Flex app and use **Diagnose and solve problems > Flex Consumption Quota** to inspect usage and request a quota increase if necessary.
- **Local managed-identity testing behaves differently:** locally, identity-based connections use your developer identity rather than the deployed Function App identity. For the first pass, test locally with the connection string and test managed identity after deployment.
- **Do not change `AzureWebJobsStorage` for this first demo:** it is the Function host's own storage connection and has additional hosting-plan considerations. This README deliberately uses `StorageConnection` for the two queue bindings.

## Cleanup

When finished, delete the resource group and everything in it:

```powershell
az group delete --name $resourceGroup --yes --no-wait
```

## Official references

- [Azure Queue storage trigger for Azure Functions](https://learn.microsoft.com/azure/azure-functions/functions-bindings-storage-queue-trigger)
- [Azure Queue storage output binding for Azure Functions](https://learn.microsoft.com/azure/azure-functions/functions-bindings-storage-queue-output)
- [Configure connections to remote services in Azure Functions](https://learn.microsoft.com/azure/azure-functions/manage-connections)
- [Connect Azure Functions to Azure Storage using command line tools (Java)](https://learn.microsoft.com/azure/azure-functions/functions-add-output-binding-storage-queue-java)
- [Create and manage function apps in a Flex Consumption plan](https://learn.microsoft.com/en-us/azure/azure-functions/flex-consumption-how-to)
