package io.kestra.plugin.microsoft365.teams;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.VoidOutput;
import io.kestra.core.plugins.notifications.ExecutionInterface;
import io.kestra.core.plugins.notifications.ExecutionService;
import io.kestra.core.runners.RunContext;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.util.Map;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Send Teams notification with execution details",
    description = "Renders a template with execution metadata (ID, namespace, flow, start time, duration, status, failing task) and posts via Teams webhook. Use with Flow triggers; for `errors` handlers prefer [TeamsIncomingWebhook](https://kestra.io/plugins/plugin-microsoft365/tasks/teams/io.kestra.plugin.microsoft365.teams.teamsincomingwebhook). No Graph permission required."
)
@Plugin(
    examples = {
        @Example(
            title = "Send a Microsoft Teams notification on a failed flow execution.",
            full = true,
            code = """
                id: failure_alert
                namespace: company.team

                tasks:
                  - id: send_alert
                    type: io.kestra.plugin.microsoft365.teams.TeamsExecution
                    url: "{{ secret('TEAMS_WEBHOOK') }}" # format: https://microsoft.webhook.office.com/webhook/xyz
                    activityTitle: "Kestra Teams notification"
                    executionId: "{{ trigger.executionId }}"

                triggers:
                  - id: failed_prod_workflows
                    type: io.kestra.plugin.core.trigger.Flow
                    conditions:
                      - type: io.kestra.plugin.core.condition.ExecutionStatus
                        in:
                          - FAILED
                          - WARNING
                      - type: io.kestra.plugin.core.condition.ExecutionNamespace
                        namespace: prod
                        prefix: true
                """
        )
    },
    aliases = "io.kestra.plugin.notifications.teams.TeamsExecution"
)
public class TeamsExecution extends TeamsTemplate implements ExecutionInterface {
    @Builder.Default
    private final Property<String> executionId = Property.ofExpression("{{ execution.id }}");
    private Property<Map<String, Object>> customFields;
    private Property<String> customMessage;

    @Override
    public VoidOutput run(RunContext runContext) throws Exception {
        this.templateUri = Property.ofValue("teams-template.peb");
        this.templateRenderMap = Property.ofValue(ExecutionService.executionMap(runContext, this));

        return super.run(runContext);
    }
}
