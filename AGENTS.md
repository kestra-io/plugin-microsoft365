# Kestra Microsoft 365 Plugin

## What

description = 'Microsoft 365 plugin for Kestra Exposes 20 plugin components (tasks, triggers, and/or conditions).

## Why

Enables Kestra workflows to interact with Microsoft 365, allowing orchestration of Microsoft 365-based operations as part of data pipelines and automation workflows.

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

## Agents

**IMPORTANT:** This is a Kestra plugin repository (prefixed by `plugin-`, `storage-`, or `secret-`). You **MUST** delegate all coding tasks to the `kestra-plugin-developer` agent. Do NOT implement code changes directly — always use this agent.
