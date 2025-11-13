package io.kestra.plugin.microsoft365.oneshare;

import com.devskiller.friendly_id.FriendlyId;
import com.google.common.collect.ImmutableMap;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.Task;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.storages.StorageInterface;
import io.kestra.core.tenant.TenantService;
import io.kestra.core.utils.IdUtils;
import io.kestra.core.utils.TestsUtils;
import io.kestra.plugin.microsoft365.oneshare.models.ItemType;
import io.kestra.plugin.microsoft365.oneshare.models.OneShareFile;
import io.micronaut.context.annotation.Value;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

@Singleton
class OnesShareTestUtils {
    @Inject
    private StorageInterface storageInterface;

    @Inject
    private RunContextFactory runContextFactory;

    @Value("${kestra.tasks.oneshare.tenantId}")
    private String tenantId;

    @Value("${kestra.tasks.oneshare.clientId}")
    private String clientId;

    @Value("${kestra.tasks.oneshare.clientSecret}")
    private String clientSecret;

    @Value("${kestra.tasks.oneshare.driveId}")
    private String driveId;

    Upload.Output upload(String parentPath, String fileName) throws Exception {
        return this.upload(parentPath, fileName, "application.yml");
    }

    Upload.Output upload(String parentPath, String fileName, String resource) throws Exception {
        URI source = storageInterface.put(
            TenantService.MAIN_TENANT,
            null,
            new URI("/" + FriendlyId.createFriendlyId()),
            new FileInputStream(new File(Objects.requireNonNull(UploadTest.class.getClassLoader()
                .getResource(resource))
                .toURI()))
        );

        String parentIdForUpload = ensureParentPath(parentPath);

        Upload task = Upload.builder()
            .id(UploadTest.class.getSimpleName())
            .type(Upload.class.getName())
            .tenantId(Property.ofValue(tenantId))
            .clientId(Property.ofValue(clientId))
            .clientSecret(Property.ofValue(clientSecret))
            .driveId(Property.ofValue(driveId))
            .parentId(Property.ofValue(parentIdForUpload))
            .fileName(Property.ofValue(fileName))
            .from(Property.ofValue(source.toString()))
            .build();

        return task.run(runContext(task));
    }

    void uploadNamed(String parentPath, String fileName) throws Exception {
        // Put arbitrary content into Kestra storage and upload to OneDrive/SharePoint with a specific name
        String content = "integration test file: " + fileName + " - " + IdUtils.create();
        InputStream input = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));

        URI source = storageInterface.put(
            TenantService.MAIN_TENANT,
            null,
            new URI("/" + FriendlyId.createFriendlyId()),
            input
        );

        String parentIdForUpload = ensureParentPath(parentPath);

        Upload upload = Upload.builder()
            .id("upload-" + FriendlyId.createFriendlyId())
            .type(Upload.class.getName())
            .tenantId(Property.ofValue(tenantId))
            .clientId(Property.ofValue(clientId))
            .clientSecret(Property.ofValue(clientSecret))
            .driveId(Property.ofValue(driveId))
            .parentId(Property.ofValue(parentIdForUpload))
            .fileName(Property.ofValue(fileName))
            .from(Property.ofValue(source.toString()))
            .build();

        upload.run(runContext(upload));
    }

    private String ensureParentPath(String parentPath) throws Exception {
        if (parentPath == null || parentPath.isBlank()) {
            return "root";
        }

        String[] parts = parentPath.replaceFirst("^/+", "").split("/");
        String currentParent = "root";
        
        for (String part : parts) {
            // First, try to find existing folder with this name
            String existingFolderId = findChildFolder(currentParent, part);
            
            if (existingFolderId != null) {
                // Folder already exists, use its ID
                currentParent = existingFolderId;
            } else {
                // Folder doesn't exist, create it
                try {
                    Create createFolder = Create.builder()
                        .id("create-" + FriendlyId.createFriendlyId())
                        .type(Create.class.getName())
                        .tenantId(Property.ofValue(tenantId))
                        .clientId(Property.ofValue(clientId))
                        .clientSecret(Property.ofValue(clientSecret))
                        .driveId(Property.ofValue(driveId))
                        .parentId(Property.ofValue(currentParent))
                        .name(Property.ofValue(part))
                        .itemType(Property.ofValue(ItemType.FOLDER))
                        .build();
                    currentParent = createFolder.run(runContext(createFolder)).getFile().getId();
                } catch (Exception e) {
                    // If creation fails due to conflict/already exists, try to find the folder again
                    if (e.getMessage() != null && 
                        (e.getMessage().contains("nameAlreadyExists") || 
                         e.getMessage().contains("already exists") ||
                         e.getMessage().contains("conflict"))) {
                        existingFolderId = findChildFolder(currentParent, part);
                        if (existingFolderId != null) {
                            currentParent = existingFolderId;
                        } else {
                            // If we still can't find it, re-throw the original exception
                            throw e;
                        }
                    } else {
                        // For other errors, re-throw
                        throw e;
                    }
                }
            }
        }
        return "root:/" + parentPath;
    }

    private String findChildFolder(String parentId, String folderName) throws Exception {
        try {
            List listFiles = List.builder()
                .id("list-" + FriendlyId.createFriendlyId())
                .type(List.class.getName())
                .tenantId(Property.ofValue(tenantId))
                .clientId(Property.ofValue(clientId))
                .clientSecret(Property.ofValue(clientSecret))
                .driveId(Property.ofValue(driveId))
                .itemId(Property.ofValue(parentId))
                .build();
            
            List.Output output = listFiles.run(runContext(listFiles));
            
            // Search for a folder with the matching name
            if (output.getFiles() != null) {
                for (OneShareFile file : output.getFiles()) {
                    if (file.isFolder() && folderName.equals(file.getName())) {
                        return file.getId();
                    }
                }
            }
        } catch (Exception e) {
            // If listing fails, return null to indicate folder not found
            return null;
        }
        
        return null; // Folder not found
    }

    RunContext runContext(Task task) {
        return TestsUtils.mockRunContext(
            this.runContextFactory,
            task,
            ImmutableMap.of(
                "tenantId", tenantId,
                "clientId", clientId,
                "clientSecret", clientSecret,
                "driveId", driveId
            )
        );
    }
}
