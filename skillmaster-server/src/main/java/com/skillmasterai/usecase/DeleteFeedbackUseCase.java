package com.skillmasterai.usecase;

import com.skillmasterai.common.ErrorCode;
import com.skillmasterai.common.RequestException;
import com.skillmasterai.modules.audit.AuditEvent;
import com.skillmasterai.modules.audit.AuditLog;
import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.modules.feedback.FeedbackService;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Withdraws one feedback document: the object storage copy first, then the row, then the audit
 * record (v64).
 *
 * <p><strong>Somebody else's receipt is a 404, not a 403.</strong> The same answer a private skill
 * gives: whether the receipt exists is not information this caller is entitled to.
 */
@Component
public class DeleteFeedbackUseCase {

    private final FeedbackService feedback;
    private final AuditLog audit;

    public DeleteFeedbackUseCase(FeedbackService feedback, AuditLog audit) {
        this.feedback = feedback;
        this.audit = audit;
    }

    @Transactional
    public void delete(String receipt, AuthenticatedSubject subject) {
        if (!feedback.deleteByReceipt(receipt, subject.userId())) {
            throw new RequestException(ErrorCode.FEEDBACK_NOT_FOUND,
                    "没有这条反馈，或它不属于你。");
        }
        audit.record(new AuditEvent(subject.userId(), "feedback_delete", "feedback", receipt,
                Map.of()));
    }
}
