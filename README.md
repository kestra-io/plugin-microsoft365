<p align="center">
  <a href="https://www.kestra.io">
    <img src="https://kestra.io/banner.png"  alt="Kestra workflow orchestrator" />
  </a>
</p>

<h1 align="center" style="border-bottom: none">
    Event-Driven Declarative Orchestrator
</h1>

<div align="center">
 <a href="https://github.com/kestra-io/kestra/releases"><img src="https://img.shields.io/github/tag-pre/kestra-io/kestra.svg?color=blueviolet" alt="Last Version" /></a>
  <a href="https://github.com/kestra-io/kestra/blob/develop/LICENSE"><img src="https://img.shields.io/github/license/kestra-io/kestra?color=blueviolet" alt="License" /></a>
  <a href="https://github.com/kestra-io/kestra/stargazers"><img src="https://img.shields.io/github/stars/kestra-io/kestra?color=blueviolet&logo=github" alt="Github star" /></a> <br>
<a href="https://kestra.io"><img src="https://img.shields.io/badge/Website-kestra.io-192A4E?color=blueviolet" alt="Kestra infinitely scalable orchestration and scheduling platform"></a>
<a href="https://kestra.io/slack"><img src="https://img.shields.io/badge/Slack-Join%20Community-blueviolet?logo=slack" alt="Slack"></a>
</div>

<br />

<p align="center">
    <a href="https://twitter.com/kestra_io"><img height="25" src="https://kestra.io/twitter.svg" alt="twitter" /></a> &nbsp;
    <a href="https://www.linkedin.com/company/kestra/"><img height="25" src="https://kestra.io/linkedin.svg" alt="linkedin" /></a> &nbsp;
<a href="https://www.youtube.com/@kestra-io"><img height="25" src="https://kestra.io/youtube.svg" alt="youtube" /></a> &nbsp;
</p>

<br />
<p align="center">
    <a href="https://go.kestra.io/video/product-overview" target="_blank">
        <img src="https://kestra.io/startvideo.png" alt="Get started in 4 minutes with Kestra" width="640px" />
    </a>
</p>
<p align="center" style="color:grey;"><i>Get started with Kestra in 4 minutes.</i></p>


# Kestra Plugin Microsoft 365

> Integrate Microsoft 365 services with Kestra data workflows

This plugin provides tasks and triggers for interacting with Microsoft 365 services including OneDrive and SharePoint. It uses the Microsoft Graph API to access files, manage folders, and react to changes in your Microsoft 365 environment.

![Kestra orchestrator](https://kestra.io/video.gif)

## Sub Plugins in MICROSOFT 365

### OneShare
- **Upload**: Upload files to OneDrive
- **Download**: Download files from OneDrive
- **List**: List files and folders
- **Create**: Create files or folders
- **Delete**: Delete files or folders
- **Export**: Export files to different formats
- **Trigger**: React to OneDrive/SharePoint file CREATE, UPDATE, or BOTH events using Delta API

This sub-plugin is named as **OneShare** because it supports both OneDrive and Sharepoint for Trigger.
If you need the same above Tasks for SharePoint please check the SharePoint sub-plugin.


### Setup Instructions

To use the OneShare subplugin, you need to configure Azure AD authentication:

#### Azure AD App Registration

1. **Create App Registration**:
   - Navigate to [Azure Portal](https://portal.azure.com/) → **Azure Active Directory** → **App registrations**
   - Click **New registration**
   - Provide a name (e.g., "Kestra OneShare Integration")
   - Select **Accounts in this organizational directory only**
   - Click **Register**

2. **Note Credentials**:
   - Copy the **Application (client) ID**
   - Copy the **Directory (tenant) ID**

3. **Create Client Secret**:
   - Go to **Certificates & secrets** → **Client secrets**
   - Click **New client secret**
   - Provide a description and select expiration
   - **Copy the secret value immediately** (won't be shown again)

4. **Grant API Permissions**:
   - Go to **API permissions** → **Add a permission**
   - Select **Microsoft Graph** → **Application permissions**
   - Add the following permissions:
     - `Files.ReadWrite.All` - Read and write files in all site collections
     - `Sites.ReadWrite.All` - Read and write items in all site collections
   - Click **Grant admin consent for your organization**

5. **Get Drive ID**:
   - For OneDrive: Use [Graph Explorer](https://developer.microsoft.com/en-us/graph/graph-explorer) and query `/me/drive` to get your drive ID
   - For SharePoint: Query `/sites/{site-id}/drive` or `/sites/{site-id}/drives`


### Authentication Methods

This plugin supports two authentication methods:

#### Option 1: Client Secret (Recommended for most scenarios)
1. Go to **Certificates & secrets** in your app registration
2. Create a new **Client Secret**
3. Note the secret value immediately (it won't be shown again)
4. Use `clientId`, `tenantId`, and `clientSecret` in your Kestra tasks

#### Option 2: Client Certificate (For enhanced security)
1. Go to **Certificates & secrets** in your app registration
2. Upload a certificate (.cer, .pem, or .crt file)
3. Export your certificate with private key as PEM format
4. Use `clientId`, `tenantId`, and `pemCertificate` in your Kestra tasks

**Note**: Use **either** Client Secret **OR** Client Certificate, not both.

### API Permissions

Grant the following API permissions in your app registration:
- `Files.ReadWrite.All` - Read and write files in all site collections
- `Sites.ReadWrite.All` - Read and write items in all site collections

After adding permissions, click **Grant admin consent** for your organization.

For more details, see the [Microsoft Graph API documentation](https://learn.microsoft.com/en-us/graph/auth-register-app-v2).

## Running the project in local
### Prerequisites
- Java 21
- Docker

### Running tests
```
./gradlew check --parallel
```

### Development

`VSCode`:

Follow the README.md within the `.devcontainer` folder for a quick and easy way to get up and running with developing plugins if you are using VSCode.

`Other IDEs`:

```
./gradlew shadowJar && docker build -t kestra-custom . && docker run --rm -p 8080:8080 kestra-custom server local
```
> [!NOTE]
> You need to relaunch this whole command everytime you make a change to your plugin

go to http://localhost:8080, your plugin will be available to use

## Documentation
* Full documentation can be found under: [kestra.io/docs](https://kestra.io/docs)
* Documentation for developing a plugin is included in the [Plugin Developer Guide](https://kestra.io/docs/plugin-developer-guide/)


## License
Apache 2.0 © [Kestra Technologies](https://kestra.io)


## Stay up to date

We release new versions every month. Give the [main repository](https://github.com/kestra-io/kestra) a star to stay up to date with the latest releases and get notified about future updates.

![Star the repo](https://kestra.io/star.gif)
