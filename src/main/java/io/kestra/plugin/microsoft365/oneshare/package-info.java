@PluginSubGroup(
    title = "OneShare",
    description = "This sub-group of plugins contains Tasks to interact with OneDrive for file storage " +
        "and collaboration including a Trigger to react on OneDrive/Sharepoint file CREATE, UPDATE or BOTH. \n" +
        "If you need SharePoint Tasks please check the SharePoint sub-plugin",
    categories = { PluginSubGroup.PluginCategory.STORAGE, PluginSubGroup.PluginCategory.CLOUD }
)
package io.kestra.plugin.microsoft365.oneshare;

import io.kestra.core.models.annotations.PluginSubGroup;
