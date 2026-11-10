package com.skillmasterai.usecase;

import com.skillmasterai.common.Timestamps;
import com.skillmasterai.modules.audit.AuditEvent;
import com.skillmasterai.modules.audit.AuditLog;
import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.modules.namespace.Namespace;
import com.skillmasterai.modules.namespace.NamespaceService;
import com.skillmasterai.modules.retrieval.PersonalEmbeddingIndex;
import com.skillmasterai.modules.skill.MyWhenToUseService;
import com.skillmasterai.modules.skill.SkillMetadataService;
import com.skillmasterai.modules.skill.SkillStanding;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Reading, writing and clearing the caller's own "when I would look for this" sentence (v59).
 *
 * <p><strong>The sentence belongs to the library relationship, but outlives it.</strong> Reading and
 * writing require the skill to be in the caller's library (a dormant entry is readable but not
 * writable, the same split private tags use). Clearing does not: a person who left the library or
 * lost a share must still be able to delete their own data by address, and an address that names
 * nothing simply clears nothing.
 *
 * <p><strong>Nothing here touches the skill or its versions.</strong> The sentence is M12's private
 * row; the audit entry carries ids only, never the text.
 */
@Component
public class MyWhenToUseUseCase {

    private static final Logger log = LoggerFactory.getLogger(MyWhenToUseUseCase.class);

    /** At most five sentence vectors per sentence; the table's CHECK agrees. */
    private static final int MAX_SENTENCES = 5;

    /** The current sentence, {@code null} when the caller has not written one. */
    public record Read(String whenToUse) {
    }

    private final NamespaceService namespaces;
    private final SkillMetadataService metadata;
    private final MyWhenToUseService notes;
    private final PersonalEmbeddingIndex personalIndex;
    private final AuditLog audit;
    private final TransactionTemplate transactions;

    public MyWhenToUseUseCase(NamespaceService namespaces, SkillMetadataService metadata,
            MyWhenToUseService notes, PersonalEmbeddingIndex personalIndex, AuditLog audit,
            PlatformTransactionManager transactionManager) {
        this.namespaces = namespaces;
        this.metadata = metadata;
        this.notes = notes;
        this.personalIndex = personalIndex;
        this.audit = audit;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /** @return empty when the address names nothing the caller may see (the controller's 404) */
    @Transactional(readOnly = true)
    public Optional<Read> read(String namespaceSlug, String name, AuthenticatedSubject subject) {
        Optional<Organize.Target> resolved =
                Organize.resolve(namespaces, metadata, namespaceSlug, name, subject);
        if (resolved.isEmpty()) {
            return Optional.empty();
        }
        SkillStanding standing = resolved.get().standing();
        if (!standing.inLibrary()) {
            throw Organize.notInLibraryForMyWhenToUse(standing, namespaceSlug);
        }
        return Optional.of(new Read(
                notes.find(subject.userId(), standing.skillId()).orElse(null)));
    }

    /**
     * Writes or replaces the sentence.
     *
     * @return empty for an address the caller may not see; otherwise whether the stored text
     *         changed — false means it was already that sentence
     */
    public Optional<Boolean> set(String namespaceSlug, String name, String rawWhenToUse,
            AuthenticatedSubject subject) {
        Optional<Organize.Target> resolved =
                Organize.resolve(namespaces, metadata, namespaceSlug, name, subject);
        if (resolved.isEmpty()) {
            return Optional.empty();
        }
        SkillStanding standing = resolved.get().standing();
        if (!standing.inLibrary()) {
            throw Organize.notInLibraryForMyWhenToUse(standing, namespaceSlug);
        }
        if (!standing.active()) {
            throw Organize.dormant(standing.name());
        }

        String whenToUse = notes.parse(rawWhenToUse);
        // The note and its audit row commit together; the provider call happens after that
        // transaction closes. Holding a pooled connection across an HTTP call is what the search
        // path already refuses (SemanticSearchUseCase §search), and a slow provider would
        // otherwise stall ten unrelated requests behind ten note writes.
        Boolean changed = transactions.execute(status -> {
            boolean stored = notes.set(subject.userId(), standing.skillId(), whenToUse,
                    Timestamps.now());
            if (stored) {
                audit.record(new AuditEvent(subject.userId(), "my_when_to_use_set", "skill",
                        standing.skillId(), detail(standing.skillId())));
            }
            return stored;
        });
        // S2's vectors are best-effort: a provider outage must not take the sentence away, and
        // S1 still matches it exactly. Retried on every write, including a same-text save, so a
        // transient failure does not leave the note without S2 forever.
        try {
            personalIndex.refresh(subject.userId(), standing.skillId(), whenToUse,
                    sentences(whenToUse),
                    () -> notes.find(subject.userId(), standing.skillId()).orElse(null));
        } catch (RuntimeException e) {
            // **Nothing after the commit may fail the request.** The note and its audit row are
            // already durable; a provider or database failure on the vector side costs S2 until
            // the next write, never the sentence the caller just saved. Catching only
            // EmbeddingUnavailable let an FK violation (the note was cleared mid-embed) escape as
            // a 500 for a write that had succeeded.
            log.warn("my-when-to-use saved without sentence vectors: {}", e.getMessage());
            try {
                personalIndex.clear(subject.userId(), standing.skillId());
            } catch (RuntimeException cleanup) {
                log.warn("could not remove stale sentence vectors: {}", cleanup.getMessage());
            }
        }
        return Optional.of(Boolean.TRUE.equals(changed));
    }

    /**
     * Removes the sentence by address, with no library gate (the person may already have left).
     *
     * @return whether a sentence was removed; an address that resolves to nothing clears nothing
     */
    @Transactional
    public boolean clear(String namespaceSlug, String name, AuthenticatedSubject subject) {
        Optional<Namespace> namespace = namespaces.bySlug(namespaceSlug);
        if (namespace.isEmpty()) {
            return false;
        }
        Optional<String> skillId = metadata.idOf(namespace.get().id(), name);
        if (skillId.isEmpty()) {
            return false;
        }
        boolean changed = notes.clear(subject.userId(), skillId.get());
        if (changed) {
            personalIndex.clear(subject.userId(), skillId.get());
            audit.record(new AuditEvent(subject.userId(), "my_when_to_use_clear", "skill",
                    skillId.get(), detail(skillId.get())));
        }
        return changed;
    }

    /**
     * Split at sentence punctuation, at most five pieces. A sentence with no punctuation is one
     * piece; the table refuses a sixth.
     */
    static List<String> sentences(String text) {
        List<String> sentences = new ArrayList<>();
        // ASCII '.' splits only when whitespace follows, so "3.14" and "e.g." stay one piece;
        // without it an English note never produced more than one vector.
        for (String piece : text.split("(?<=[。！？!?；;])|(?<=\\.)\\s+")) {
            String sentence = piece.strip();
            if (!sentence.isEmpty()) {
                sentences.add(sentence);
            }
            if (sentences.size() == MAX_SENTENCES) {
                break;
            }
        }
        return sentences.isEmpty() ? List.of(text) : sentences;
    }

    /** Ids only: the sentence is private and must not reach a log line or another reader. */
    private static Map<String, Object> detail(String skillId) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("skill_id", skillId);
        return detail;
    }
}
