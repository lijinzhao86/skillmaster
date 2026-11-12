package com.skillmasterai.modules.feedback;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * M18's published entry: write one feedback document, read it back for its owner, withdraw it, and
 * hand the archive job the rows that have no durability copy yet.
 *
 * <p>Ownership is decided by the caller: every read and delete takes a user id, and a receipt that
 * names somebody else is answered as "not found" rather than "forbidden". The service never
 * resolves a skill address; it stores the string as context.
 */
public interface FeedbackService {

    /**
     * Stores the document with secret-bearing prefixes replaced. The server computes both the
     * digest and the consent version; neither comes from the client.
     */
    Feedback create(String userId, String skillAddress, String content, String consentVersion,
            Map<String, Object> diagnostics, String at);

    Optional<Feedback> findByReceiptForUser(String receipt, String userId);

    /**
     * Deletes the object storage copy first, then the row.
     *
     * @return false when the receipt names nothing this person may withdraw
     */
    boolean deleteByReceipt(String receipt, String userId);

    /** How many documents this person submitted at or after {@code since}. */
    int countSince(String userId, String since);

    /** The oldest submission at or after {@code since}, or empty when there is none. */
    Optional<String> oldestSince(String userId, String since);

    /** The rows the archive job has not written yet, oldest first. */
    List<Feedback> pendingArchive(int limit);

    /**
     * Records where the durability copy lives and when it was written.
     *
     * @return 1 when this run recorded the key; 0 when the row was withdrawn in the meantime or
     *     another run recorded one first. The archive job deletes the object only when the row is
     *     gone, so a lost race does not delete the copy the winner recorded.
     */
    int markArchived(String id, String key, String at);

    /** Whether the row is still there; the archive job asks before deleting an orphaned object. */
    boolean existsById(String id);
}
