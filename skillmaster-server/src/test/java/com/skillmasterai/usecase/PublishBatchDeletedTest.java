package com.skillmasterai.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.modules.version.SkillDeletedException;
import com.skillmasterai.modules.version.VersionPin;
import com.skillmasterai.usecase.PublishBatchUseCase.Outcome;
import com.skillmasterai.usecase.PublishBatchUseCase.Target;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

/**
 * A row whose skill was deleted mid-batch is that row's failure, not the end of the stream.
 *
 * <p>v37 gave the publish path a new refusal — {@link SkillDeletedException}, raised when the
 * publish loses the skill row to a delete (ADR 0127). The batch already answers named publish
 * failures per row; a new one that escaped would truncate the NDJSON body, because the response has
 * already begun, and every later target would go untried.
 */
class PublishBatchDeletedTest {

    @Test
    void aDeletedSkillIsOneRowsFailure() {
        PublishSkillVersionUseCase single = mock(PublishSkillVersionUseCase.class);
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        when(single.publish(anyString(), anyString(), any(), any(), any()))
                .thenThrow(new SkillDeletedException("dev/x"));

        PublishBatchUseCase batch = new PublishBatchUseCase(single, transactions);
        Outcome outcome = batch.publishOne(
                new Target("dev", "x", new VersionPin.RowId("d1"), "dev/x@d1", "1.0.0"),
                new AuthenticatedSubject("01JKMNPQRSTVWXYZ0123456789", Set.of("skills:write"), null,
                        null));

        assertThat(outcome.ok()).isFalse();
        assertThat(outcome.code()).isEqualTo("skill_deleted");
        assertThat(outcome.namespace()).isEqualTo("dev");
        assertThat(outcome.name()).isEqualTo("x");
    }
}
