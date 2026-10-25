package com.skillmasterai.usecase;

import com.skillmasterai.common.ApiError;
import com.skillmasterai.common.ErrorCode;
import com.skillmasterai.common.RequestException;
import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.modules.skill.NotPermittedException;
import com.skillmasterai.modules.version.PromotionOutcome;
import com.skillmasterai.modules.version.VersionNameTakenException;
import com.skillmasterai.modules.version.VersionPin;
import com.skillmasterai.modules.version.VersionStateException;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Publishes several drafts at once, one at a time (v18).
 *
 * <p><strong>Each row is its own transaction, and its own success or failure.</strong> A batch is
 * not one operation — the drafts have nothing to do with each other — so one row failing must not
 * roll back the rows that already went live, and a row that already went live must not be undone
 * because a later one failed. The template is opened per row for exactly that reason, the same way
 * {@link DraftRecorder} opens one per submission.
 *
 * <p><strong>One publish implementation, and this class is not it.</strong> Publishing one version
 * is {@link PublishSkillVersionUseCase} — the pointer move, the number, the projection rewrite and
 * the audit row. A batch that re-implemented any of those would be the second place a publish could
 * go wrong, and the two would drift silently: the audit trail in particular is only worth having if
 * every publish writes the same shape. It joins the transaction this class opens, so "one row, one
 * transaction" still holds.
 *
 * <p><strong>The request is checked before the first row.</strong> An empty batch and a batch naming
 * the same skill twice are refusals of the request, not results of one of its rows: there is nothing
 * sensible to report per row for "you sent nothing", and for a duplicate there is no defined order —
 * the second publish would silently replace what the first just put live.
 */
@Component
public class PublishBatchUseCase {

    /**
     * @param version the pin, bare short code or bare ULID — the queue hands it out in exactly that
     *                spelling and the client passes it back unchanged (ADR 0063)
     * @param predicted what the client showed the publisher this row would be numbered; echoed back
     *                  so the page can tell a plan from a promise without trusting itself
     */
    public record Target(String namespace, String name, VersionPin pin, String spelling,
            String predicted) {
    }

    /**
     * One row's answer.
     *
     * <p>A success carries the number that was actually assigned — never the prediction. A failure
     * carries the code the same error would have had as a single request, so a client that already
     * handles publish errors does not learn a second vocabulary.
     */
    public record Outcome(boolean ok, String namespace, String name, String version,
            String draftName, String predicted, String code, String message) {
    }

    private final PublishSkillVersionUseCase single;
    private final TransactionTemplate transactions;

    public PublishBatchUseCase(PublishSkillVersionUseCase single,
            PlatformTransactionManager transactionManager) {
        this.single = single;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /**
     * Refuses the request when it cannot be carried out as a whole.
     *
     * @throws RequestException when there is nothing to publish, a target names no pin, or two
     *         targets name one skill
     */
    public void validate(List<Target> targets) {
        if (targets.isEmpty()) {
            throw new RequestException(ErrorCode.INVALID_REQUEST,
                    "a batch needs at least one target");
        }
        Set<String> seen = new HashSet<>();
        for (Target target : targets) {
            if (target.namespace() == null || target.name() == null || target.pin() == null) {
                throw new RequestException(ErrorCode.INVALID_REQUEST,
                        "every target needs namespace, name and version",
                        List.of(new ApiError.Detail("targets", "incomplete")));
            }
            if (!seen.add(target.namespace() + "/" + target.name())) {
                throw new RequestException(ErrorCode.DUPLICATE_TARGET,
                        "'" + target.namespace() + "/" + target.name()
                                + "' appears twice in one batch; a skill is published once per batch",
                        List.of(new ApiError.Detail("targets", "duplicate")));
            }
        }
    }

    /**
     * Publishes one target in its own transaction, and never throws: a failure is this row's answer.
     *
     * <p>Only exceptions the publish path raises for a reason are turned into codes; anything else is
     * re-thrown, because a row that failed for a reason nobody named is not a result the page can
     * show — it should end the stream and be reported, not be quietly listed as "failed".
     */
    public Outcome publishOne(Target target, AuthenticatedSubject subject) {
        try {
            return transactions.execute(status -> publish(target, subject));
        } catch (VersionNameTakenException e) {
            return failure(target, ErrorCode.VERSION_ALREADY_EXISTS, e.getMessage());
        } catch (VersionStateException e) {
            return failure(target, e.code(), e.getMessage());
        } catch (NotPermittedException e) {
            return failure(target, ErrorCode.FORBIDDEN, e.getMessage());
        } catch (com.skillmasterai.modules.retrieval.EmbeddingUnavailable e) {
            // A named, expected failure since v19: the semantic index could not be written, so this
            // row's publish rolled back. It is this row's answer like any other — letting it end the
            // stream would leave the page with a truncated NDJSON body that cannot even carry a 503,
            // because the response has already begun.
            return failure(target, ErrorCode.SEMANTIC_UNAVAILABLE,
                    "语义索引暂时不可用，这一条没有发布");
        }
    }

    private Outcome publish(Target target, AuthenticatedSubject subject) {
        // No release name: a batch never names numbers, the type and the live version compute them
        // (TD §接口). The caller's own permission is checked inside, per row.
        Optional<PromotionOutcome> promoted = single.publish(target.namespace(), target.name(),
                target.pin(), null, subject);

        if (promoted.isEmpty()) {
            // The address names nothing this caller may publish: the same one-404 rule the single
            // route follows, said per row.
            return failure(target, ErrorCode.SKILL_NOT_FOUND,
                    "no version '" + target.spelling() + "' to publish from '"
                            + target.namespace() + "/" + target.name() + "'");
        }
        PromotionOutcome outcome = promoted.get();
        return new Outcome(true, target.namespace(), target.name(), outcome.version(),
                outcome.draftName(), target.predicted(), null, null);
    }

    private static Outcome failure(Target target, ErrorCode code, String message) {
        return new Outcome(false, target.namespace(), target.name(), null, null, target.predicted(),
                code.wireName(), message);
    }

}
