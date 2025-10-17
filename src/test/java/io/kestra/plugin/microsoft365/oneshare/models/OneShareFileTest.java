package io.kestra.plugin.microsoft365.oneshare.models;

import com.microsoft.graph.models.DriveItem;
import com.microsoft.graph.models.File;
import com.microsoft.graph.models.Folder;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OneShareFileTest {
    @Test
    void of() {
        DriveItem driveItem = mock(DriveItem.class);
        File file = mock(File.class);
        OffsetDateTime now = OffsetDateTime.now();

        when(driveItem.getId()).thenReturn("id");
        when(driveItem.getName()).thenReturn("name");
        when(driveItem.getWebUrl()).thenReturn("webUrl");
        when(driveItem.getSize()).thenReturn(100L);
        when(driveItem.getCreatedDateTime()).thenReturn(now);
        when(driveItem.getLastModifiedDateTime()).thenReturn(now);
        when(driveItem.getFile()).thenReturn(file);
        when(file.getMimeType()).thenReturn("application/json");

        // Test file mapping
        OneShareFile onesShareFile = OneShareFile.of(driveItem);

        assertThat(onesShareFile.getId(), is("id"));
        assertThat(onesShareFile.getName(), is("name"));
        assertThat(onesShareFile.getWebUrl(), is("webUrl"));
        assertThat(onesShareFile.getSize(), is(100L));
        assertThat(onesShareFile.getMimeType(), is("application/json"));
        assertThat(onesShareFile.isFolder(), is(false));
        assertThat(onesShareFile.getCreatedDateTime(), is(now));
        assertThat(onesShareFile.getLastModifiedDateTime(), is(now));

        // Test folder mapping
        when(driveItem.getFolder()).thenReturn(mock(Folder.class));
        when(driveItem.getFile()).thenReturn(null);

        onesShareFile = OneShareFile.of(driveItem);
        assertThat(onesShareFile.isFolder(), is(true));
        assertThat(onesShareFile.getMimeType(), is(nullValue()));
    }
}
