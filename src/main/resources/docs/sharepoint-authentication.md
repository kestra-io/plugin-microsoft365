# SharePoint Authentication & Permissions

The Microsoft 365 SharePoint plugin uses the **Microsoft Graph SDK for Java** to interact with SharePoint. All tasks require an authenticated Graph client. Authentication is performed via **OAuth2** using Azure AD.

## 1. Register an Azure AD Application

1. Open the Azure portal and navigate to **Azure Active Directory → App registrations**.  
2. Click **New registration**.  
   - **Name**: `Kestra SharePoint Plugin` (or any name you prefer).  
   - **Supported account types**: *Accounts in this organizational directory only* (single tenant) or *Multitenant* as needed.  
   - **Redirect URI**: `http://localhost` (for development) or your production URL.  
3. Click **Register**.

## 2. Create a Client Secret

1. In the newly created app, go to **Certificates & secrets**.  
2. Click **New client secret**.  
   - Provide a description (e.g., `Kestra secret`).  
   - Choose an expiration period.  
3. Click **Add** and **copy** the generated secret value. **You will not be able to see it again.**

## 3. Configure Secrets in Kestra

Kestra reads secrets from its secret store. Define the following secrets (via the UI, `secrets.yml`, or environment variables):

| Secret Key                | Description                                 |
|---------------------------|---------------------------------------------|
| `AZURE_TENANT_ID`         | Azure AD tenant ID (directory ID).          |
| `AZURE_CLIENT_ID`         | Application (client) ID of the registered app. |
| `AZURE_CLIENT_SECRET`     | The client secret created in step 2.        |
| `SHAREPOINT_SITE_ID`      | The SharePoint site ID (e.g., `contoso.sharepoint.com,12345`). |
| `SHAREPOINT_DRIVE_ID`     | The document library (drive) ID.            |
| `SHAREPOINT_PARENT_ID`    | The folder ID where files will be created/uploaded. |
| `SHAREPOINT_ITEM_ID`      | The ID of a file or folder (used for delete, download, export). |
| `SHAREPOINT_FOLDER_ID`    | The ID of a folder to list items from.      |

You can set these secrets in `src/test/resources/application.yml` for local testing, or via the Kestra UI for production.

## 4. Required Permission Scopes

When configuring the Azure AD app, add the following **Microsoft Graph API** permission scopes (Application permissions for service‑to‑service calls, or Delegated permissions for user‑impersonation). The plugin expects **Application** permissions:

| Permission                | Description                                   |
|---------------------------|-----------------------------------------------|
| `Sites.Read.All`          | Read all SharePoint sites.                    |
| `Sites.ReadWrite.All`     | Read and write to all SharePoint sites.       |
| `Files.Read`              | Read files in all site collections.           |
| `Files.ReadWrite`         | Read, create, update, and delete files.       |
| `User.Read`               | Read the profile of the signed‑in user (required for token acquisition). |

After adding the permissions, **grant admin consent** for the tenant.

## 5. Initialising the Graph Client (Java)

Add the Microsoft Graph SDK dependency to `build.gradle`:

```gradle
dependencies {
    // existing dependencies ...

    implementation "com.microsoft.graph:microsoft-graph:5.30.0"
    implementation "com.azure:azure-identity:1.12.0"
}
```

Create a utility class to obtain an authenticated `GraphServiceClient`:

```java
package io.kestra.plugin.microsoft365.sharepoint;

import com.azure.identity.ClientSecretCredential;
import com.azure.identity.ClientSecretCredentialBuilder;
import com.microsoft.graph.authentication.TokenCredentialAuthProvider;
import com.microsoft.graph.requests.GraphServiceClient;
import java.util.Collections;

public final class GraphClientProvider {
    private static GraphServiceClient<?> client;

    public static GraphServiceClient<?> getClient() {
        if (client == null) {
            ClientSecretCredential credential = new ClientSecretCredentialBuilder()
                .clientId(System.getenv("AZURE_CLIENT_ID"))
                .clientSecret(System.getenv("AZURE_CLIENT_SECRET"))
                .tenantId(System.getenv("AZURE_TENANT_ID"))
                .build();

            TokenCredentialAuthProvider authProvider = new TokenCredentialAuthProvider(
                Collections.singletonList("https://graph.microsoft.com/.default"),
                credential
            );

            client = GraphServiceClient.builder()
                .authenticationProvider(authProvider)
                .buildClient();
        }
        return client;
    }
}
```

## 6. Using the Client in Tasks

Replace the `// TODO: Implement actual SharePoint API call` sections with real calls, for example:

```java
GraphServiceClient<?> graphClient = GraphClientProvider.getClient();

graphClient.sites(siteId)
    .drives(driveId)
    .items(parentId)
    .children()
    .buildRequest()
    .post(new DriveItem()
        .withName(filename)
        .withFile(new File())
        .withAdditionalData(Collections.singletonMap("@microsoft.graph.conflictBehavior", "replace"))
        .withContent(content.getBytes()));
```

Each task should handle:

* **Error handling** – catch `GraphServiceException` and translate to meaningful Kestra errors (e.g., permission denied, token expired).  
* **Large file uploads** – use `createUploadSession` for files > 4 MB, handling chunked uploads.  
* **Export format** – append `?format={format}` to the download URL.

## 7. Testing

The unit tests provided in `src/test/java/io/kestra/plugin/microsoft365/sharepoint/` use mocked inputs and verify that the task returns non‑null output fields. For integration tests, you can enable the real Graph client by providing valid secrets and running the flow YAML files located in `src/test/resources/flows/sharepoint/`.

## 8. References

* Microsoft Graph SDK for Java: https://github.com/microsoftgraph/msgraph-sdk-java  
* Azure Identity library: https://github.com/Azure/azure-sdk-for-java/tree/main/sdk/identity/azure-identity  
* SharePoint API documentation: https://learn.microsoft.com/graph/api/resources/sharepoint?view=graph-rest-1.0  

---

**Note:** The current implementation contains placeholder logic (`TODO` comments) and returns mock data. Replace those sections with the real Graph SDK calls as shown above to achieve full functionality.
