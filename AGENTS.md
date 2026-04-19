# Kestra Microsoft 365 Plugin

## What

- Provides plugin components under `io.kestra.plugin.microsoft365`.
- Covers Microsoft Graph-based integrations for Outlook, OneShare and SharePoint file operations, and Teams notifications.
- Includes classes such as `Send`, `MailReceivedTrigger`, `Upload`, `Trigger`, and `TeamsIncomingWebhook`.

## Why

- What user problem does this solve? Teams need to integrate Microsoft 365 services through the Microsoft Graph API from orchestrated workflows instead of relying on manual console work, ad hoc scripts, or disconnected schedulers.
- Why would a team adopt this plugin in a workflow? It keeps Microsoft 365 steps in the same Kestra flow as upstream preparation, approvals, retries, notifications, and downstream systems.
- What operational/business outcome does it enable? It reduces manual handoffs and fragmented tooling while improving reliability, traceability, and delivery speed for processes that depend on Microsoft 365.

## How

### Architecture

Single-module plugin. Source packages under `io.kestra.plugin`:

- `microsoft365`

Infrastructure dependencies (Docker Compose services):

- `app`

### Key Plugin Classes

- `io.kestra.plugin.microsoft365.oneshare.Create`
- `io.kestra.plugin.microsoft365.oneshare.Delete`
- `io.kestra.plugin.microsoft365.oneshare.Download`
- `io.kestra.plugin.microsoft365.oneshare.Export`
- `io.kestra.plugin.microsoft365.oneshare.List`
- `io.kestra.plugin.microsoft365.oneshare.Trigger`
- `io.kestra.plugin.microsoft365.oneshare.Upload`
- `io.kestra.plugin.microsoft365.outlook.Get`
- `io.kestra.plugin.microsoft365.outlook.List`
- `io.kestra.plugin.microsoft365.outlook.MailReceivedTrigger`
- `io.kestra.plugin.microsoft365.outlook.Send`
- `io.kestra.plugin.microsoft365.sharepoint.Create`
- `io.kestra.plugin.microsoft365.sharepoint.Delete`
- `io.kestra.plugin.microsoft365.sharepoint.Download`
- `io.kestra.plugin.microsoft365.sharepoint.Export`
- `io.kestra.plugin.microsoft365.sharepoint.List`
- `io.kestra.plugin.microsoft365.sharepoint.Move`
- `io.kestra.plugin.microsoft365.sharepoint.Upload`
- `io.kestra.plugin.microsoft365.teams.TeamsExecution`
- `io.kestra.plugin.microsoft365.teams.TeamsIncomingWebhook`

### Project Structure

```
plugin-microsoft365/
├── src/main/java/io/kestra/plugin/microsoft365/teams/
├── src/test/java/io/kestra/plugin/microsoft365/teams/
├── build.gradle
└── README.md
```

### Important Commands

```bash
# Build the plugin
./gradlew shadowJar

# Run tests
./gradlew test

# Build without tests
./gradlew shadowJar -x test
```

### Configuration

All tasks and triggers accept standard Kestra plugin properties. Credentials should use
`{{ secret('SECRET_NAME') }}` — never hardcode real values.

## References

- https://kestra.io/docs/plugin-developer-guide
- https://kestra.io/docs/plugin-developer-guide/contribution-guidelines
