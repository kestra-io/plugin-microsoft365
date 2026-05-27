# How to use the Microsoft 365 plugin

Interact with Microsoft Teams, OneDrive, SharePoint, Outlook, and Dynamics 365 from Kestra flows.

## Authentication

**Teams** (`teams.TeamsIncomingWebhook`, `teams.TeamsExecution`): set `url` to a Teams incoming webhook URL. Create one in Teams under channel settings → Connectors. Store it in a [secret](https://kestra.io/docs/concepts/secret).

**OneDrive, SharePoint, and Outlook** tasks authenticate via Microsoft Graph API using an Azure AD app registration. Set `tenantId`, `clientId`, and `clientSecret` on each task (or use `pemCertificate` instead of `clientSecret` for certificate-based auth). Apply these globally with [plugin defaults](https://kestra.io/docs/workflow-components/plugin-defaults) to avoid repeating them.

**Dynamics 365 Dataverse and Business Central** tasks use the same Azure AD app registration pattern — set `tenantId`, `clientId`, and `clientSecret` (or `pemCertificate`). Grant the service principal the `Dynamics CRM user` permission for Dataverse tasks, or the `Financials.ReadWrite.All` permission for Business Central tasks.

## Tasks

`teams.TeamsIncomingWebhook` sends a message as a step within a flow — set `payload` to a JSON body in the Teams [adaptive card format](https://adaptivecards.io/). `teams.TeamsExecution` sends a structured execution summary and is designed for use with a [Flow trigger](https://kestra.io/docs/workflow-components/triggers) in a dedicated monitoring namespace that watches other namespaces for failures.

`oneshare` tasks manage files in OneDrive: `Upload` and `Download` move files between Kestra internal storage and OneDrive; `Create` creates folders; `Delete` removes items; `List` queries a drive; `Export` converts OneDrive-hosted Office files to a downloadable format. `oneshare.Trigger` polls for file changes (creates and updates) in a OneDrive or SharePoint folder using Microsoft Graph delta links.

`sharepoint` tasks manage files in SharePoint document libraries: `Upload`, `Download`, `Create`, `Delete`, `List`, `Export`, and `Move`. Set `siteId` and `driveId` on SharePoint tasks to target the correct site and library.

`outlook` tasks interact with email: `Send` sends a message, `Get` reads a message by ID, `List` queries the mailbox. `outlook.MailReceivedTrigger` polls for new messages and starts one execution per batch.

`dynamics365.dataverse` tasks interact with the Dataverse Web API (OData v9.2) — all require `orgUrl` (your Dataverse organization URL, e.g. `https://myorg.api.crm.dynamics.com`). `dataverse.Query` fetches entity records — set `entitySetName` (required); optionally set `filter` (OData `$filter` expression), `select` (comma-separated fields), `top` (default `100`), and `fetchType` (`FETCH`, `FETCH_ONE`, or `STORE`, default `FETCH`). `dataverse.Upsert` creates or updates a record by GUID — set `entitySetName`, `recordId`, and `record` (a map of field values, all required). `dataverse.Delete` permanently removes a record — set `entitySetName` and `recordId` (both required).

`dynamics365.businesscentral` tasks interact with the Business Central API v2.0 — all require `environment` (e.g. `production` or `sandbox`). `businesscentral.ListCompanies` returns all companies in the environment. The remaining tasks also require `companyId` (the company GUID): `GetCustomer` and `GetInvoice` fetch a single record by `customerId` or `invoiceId`; `CreateCustomer` and `CreateInvoice` create records from a `customer` or `invoice` map and output the new record's ID; `ListItems` lists inventory items with optional `filter` and `top` (default `100`).
