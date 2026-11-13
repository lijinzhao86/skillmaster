package com.skillmasterai.usecase;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.skillmasterai.modules.audit.AuditLog;
import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.modules.blob.BlobStore;
import com.skillmasterai.modules.feedback.FeedbackService;
import com.skillmasterai.modules.notification.NotificationService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

/**
 * The one path no integration test can reach: the objects are in the bucket, the transaction then
 * fails, and nothing may be left that no row names.
 */
class SubmitFeedbackUseCaseTest {

    private static final String USER = "01JKMNPQRSTVWXYZ0123456789";

    private static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+ip1sAAAAASUVORK5CYII=");

    @Test
    void aFailedTransactionRemovesEveryUploadedObject() throws Exception {
        FeedbackService feedback = mock(FeedbackService.class);
        NotificationService notifications = mock(NotificationService.class);
        AuditLog audit = mock(AuditLog.class);
        BlobStore blobs = mock(BlobStore.class);
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        when(feedback.create(anyString(), any(), anyString(), anyString(), any(), anyString(), any()))
                .thenThrow(new IllegalStateException("db down"));

        SubmitFeedbackUseCase useCase = new SubmitFeedbackUseCase(feedback,
                new LegalTextVersions("1.1", "1.3"), notifications, audit, blobs, transactions);
        Path file = Files.createTempFile("feedback-tx-", ".png");
        try {
            Files.write(file, PNG);
            assertThatThrownBy(() -> useCase.submit("text", null, Map.of(), "1.3", false,
                    List.of(new SubmitFeedbackUseCase.Upload(file, "shot.png")),
                    AuthenticatedSubject.ofSession(USER)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("db down");

            verify(blobs).putObject(anyString(), any(Path.class), anyLong());
            verify(blobs).deleteObject(anyString());
            verifyNoInteractions(notifications, audit);
        } finally {
            Files.deleteIfExists(file);
        }
    }
}
