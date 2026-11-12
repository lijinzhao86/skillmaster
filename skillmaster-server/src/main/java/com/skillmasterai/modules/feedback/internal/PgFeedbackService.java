package com.skillmasterai.modules.feedback.internal;

import com.skillmasterai.common.Sha256Hex;
import com.skillmasterai.common.ShortCode;
import com.skillmasterai.common.Ulid;
import com.skillmasterai.modules.blob.BlobStore;
import com.skillmasterai.modules.feedback.Feedback;
import com.skillmasterai.modules.feedback.FeedbackSafety;
import com.skillmasterai.modules.feedback.FeedbackService;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * M18's one implementation.
 *
 * <p><strong>The receipt is retried, not reserved.</strong> Six characters carry about thirty bits;
 * the unique constraint is what makes a collision impossible to miss, and the loop is what makes it
 * invisible to the caller. The insert uses {@code ON CONFLICT DO NOTHING}: a caught duplicate-key
 * exception would leave the caller's transaction aborted and the second attempt could not run.
 * Three attempts is already far beyond the birthday bound for this volume.
 *
 * <p><strong>The object is deleted before the row.</strong> If the object delete fails the row stays,
 * so a retry still knows the key. Doing it the other way round would leave an object nobody can
 * name.
 */
public final class PgFeedbackService implements FeedbackService {

    private static final Logger LOG = LoggerFactory.getLogger(PgFeedbackService.class);

    private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;

    private static final int RECEIPT_RANDOM = 6;
    private static final int RECEIPT_ATTEMPTS = 3;

    /** The only channel v64 has; stored on the row so a future channel is distinguishable. */
    private static final String SOURCE = "cli";

    private final FeedbackRepository repository;
    private final BlobStore blobs;

    public PgFeedbackService(FeedbackRepository repository, BlobStore blobs) {
        this.repository = repository;
        this.blobs = blobs;
    }

    @Override
    public Feedback create(String userId, String skillAddress, String content,
            String consentVersion, Map<String, Object> diagnostics, String at) {
        String stored = FeedbackSafety.redactSecrets(content);
        String sha = Sha256Hex.of(stored.getBytes(StandardCharsets.UTF_8));
        for (int attempt = 0; attempt < RECEIPT_ATTEMPTS; attempt++) {
            Feedback feedback = new Feedback(
                    Ulid.generate(),
                    receipt(at),
                    userId,
                    skillAddress,
                    stored,
                    sha,
                    consentVersion,
                    at,
                    SOURCE,
                    diagnostics,
                    null,
                    null,
                    at);
            if (repository.insert(feedback) == 1) {
                return feedback;
            }
            LOG.debug("feedback receipt collision, retrying");
        }
        throw new IllegalStateException("could not allocate a unique feedback receipt");
    }

    @Override
    public Optional<Feedback> findByReceiptForUser(String receipt, String userId) {
        return repository.findByReceiptAndUser(receipt, userId);
    }

    @Override
    public boolean deleteByReceipt(String receipt, String userId) {
        // **Locked until commit.** The archive job writes the object and then marks the row; if it
        // could interleave between this read and the DELETE, the object would outlive the row.
        Optional<Feedback> found = repository.lockByReceiptAndUser(receipt, userId);
        if (found.isEmpty()) {
            return false;
        }
        String archiveKey = found.get().archiveKey();
        if (archiveKey != null) {
            blobs.deleteObject(archiveKey);
        }
        return repository.deleteByReceiptAndUser(receipt, userId) > 0;
    }

    @Override
    public int countSince(String userId, String since) {
        return repository.countSince(userId, since);
    }

    @Override
    public Optional<String> oldestSince(String userId, String since) {
        return repository.oldestSince(userId, since);
    }

    @Override
    public List<Feedback> pendingArchive(int limit) {
        return repository.pendingArchive(limit);
    }

    @Override
    public int markArchived(String id, String key, String at) {
        return repository.markArchived(id, key, at);
    }

    @Override
    public boolean existsById(String id) {
        return repository.existsById(id);
    }

    /** {@code FB-YYYYMMDD-XXXXXX}, the day taken from the submission instant in UTC. */
    static String receipt(String at) {
        String day = Instant.parse(at).atZone(ZoneOffset.UTC).toLocalDate().format(DAY);
        return "FB-" + day + "-" + ShortCode.generate().substring(0, RECEIPT_RANDOM);
    }

    /** Where the durability copy lives; derived from the row so a withdrawal can name it. */
    static String archiveKey(Feedback feedback) {
        String day = Instant.parse(feedback.createdAt())
                .atZone(ZoneOffset.UTC).toLocalDate().format(DAY);
        return "feedback/" + day.substring(0, 4) + "/" + day.substring(4, 6) + "/" + day.substring(6, 8)
                + "/" + feedback.receipt() + ".ndjson.gz";
    }
}
