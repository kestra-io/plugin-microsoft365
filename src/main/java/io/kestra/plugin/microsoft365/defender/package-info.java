@PluginSubGroup(
    title = "Defender",
    description = "Microsoft Defender XDR integration through the Microsoft Graph security API. List, get and update alerts and incidents, run advanced hunting queries in KQL, and trigger workflows on new or updated alerts and incidents.",
    categories = {
        PluginSubGroup.PluginCategory.INFRASTRUCTURE
    }
)
package io.kestra.plugin.microsoft365.defender;

import io.kestra.core.models.annotations.PluginSubGroup;
