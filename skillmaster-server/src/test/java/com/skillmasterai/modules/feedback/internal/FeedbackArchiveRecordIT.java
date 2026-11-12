package com.skillmasterai.modules.feedback.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;

import com.skillmasterai.modules.blob.BlobStore;
import com.skillmasterai.modules.feedback.Feedback;
import com.skillmasterai.modules.feedback.FeedbackService;
import com.skillmasterai.support.AbstractIT;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.jdbc.Sql;
import tools.jackson.databind.ObjectMapper;

/**
 * The one branch {@link FeedbackArchiveJob} cannot reach in a normal run: the row it uploaded for
 * disappeared before it could record the key.
 */
@Sql("/sql/truncate-business-tables.sql")
class FeedbackArchiveRecordIT extends AbstractIT {

    private static final String USER = "01M3HTG7GCCVBGRPAFFSVSF12W";

    @Autowired
    private FeedbackService feedback;

    @Autowired
    private ObjectMapper json;

    @Test
    void aWithdrawnRowTakesItsObjectWithIt() throws Exception {
        Feedback row = feedback.create(USER, null, "withdrawn before the mark", "1.0", null,
                "2026-11-13T04:00:00Z");
        jdbc.sql("DELETE FROM user_feedback WHERE id = :id").param("id", row.id()).update();
        String key = "feedback/2026/11/13/" + row.receipt() + ".ndjson.gz";

        BlobStore blobs = Mockito.mock(BlobStore.class);
        job(blobs).recordArchive(row, key);

        Mockito.verify(blobs).deleteObject(key);
    }

    @Test
    void aRowArchivedByAnotherRunKeepsItsObject() throws Exception {
        Feedback row = feedback.create(USER, null, "archived elsewhere", "1.0", null,
                "2026-11-13T04:05:00Z");
        String key = "feedback/2026/11/13/" + row.receipt() + ".ndjson.gz";
        assertThat(feedback.markArchived(row.id(), key, "2026-11-13T04:06:00Z")).isEqualTo(1);

        BlobStore blobs = Mockito.mock(BlobStore.class);
        job(blobs).recordArchive(row, key);

        Mockito.verify(blobs, Mockito.never()).deleteObject(anyString());
    }

    private FeedbackArchiveJob job(BlobStore blobs) throws Exception {
        Path directory = Files.createTempDirectory("feedback-record-");
        directory.toFile().deleteOnExit();
        return new FeedbackArchiveJob(feedback, blobs, json, directory);
    }
}
