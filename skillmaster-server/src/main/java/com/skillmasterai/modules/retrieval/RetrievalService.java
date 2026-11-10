package com.skillmasterai.modules.retrieval;

import com.skillmasterai.modules.retrieval.internal.EmbeddingRepository;
import java.util.List;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * M13's read side: query text in, ranked skill ids out.
 *
 * <p><strong>It says nothing about who may see what.</strong> Two entry points, one question:
 * {@link #nearestWithin} searches a set M12 has already narrowed, {@link #nearest} searches the
 * whole corpus and lets the caller filter the results afterwards. The caller picks which, because
 * it is the caller that knows whether its admissible set is small enough to enumerate — and the
 * second one is only correct when that set is large enough that filtering a prefix of the corpus
 * costs little recall (v19 TD §检索链).
 *
 * <p><strong>Failure degrades, it never throws.</strong> A provider outage means the answer is
 * "no semantic candidates, here is why", and the caller falls back to the keyword path it already
 * had. Turning a search into a 500 because a third party is slow would take a working feature down
 * with it (PRD #7, test-plan T07).
 */
public final class RetrievalService {

    private static final Logger LOG = LoggerFactory.getLogger(RetrievalService.class);

    private final EmbeddingRepository repository;
    private final Embedder embedder;
    private final EmbeddingSettings settings;

    public RetrievalService(EmbeddingRepository repository, Embedder embedder,
            EmbeddingSettings settings) {
        this.repository = repository;
        this.embedder = embedder;
        this.settings = settings;
    }

    /** One result, before visibility. */
    public record Candidate(String skillId, double score) {
    }

    /**
     * @param candidates     ranked best first, already past the threshold
     * @param fallbackReason null when semantic search ran; why it did not, when it did not. The
     *                       caller turns it into the response's {@code note} rather than inventing
     *                       its own wording
     */
    public record Outcome(List<Candidate> candidates, String fallbackReason) {
        public Outcome {
            candidates = List.copyOf(candidates);
        }

        public boolean degraded() {
            return fallbackReason != null;
        }
    }

    /** One skill's best private-sentence score (v59 P2). */
    public record PersonalHit(String skillId, double score) {
    }

    /**
     * Both library reads from one embedding call: the author vectors and the caller's own sentence
     * vectors. One provider round trip is the point — a second call would double the only network
     * cost the library search has.
     *
     * @param fallbackReason null when both reads ran; the reason when the provider failed
     */
    public record LibraryOutcome(List<Candidate> corpus, List<PersonalHit> personal,
            String fallbackReason) {
        public LibraryOutcome {
            corpus = List.copyOf(corpus);
            personal = List.copyOf(personal);
        }

        public boolean degraded() {
            return fallbackReason != null;
        }
    }

    /** Whether semantic search may be attempted at all — the switch, not the provider's health. */
    public boolean enabled() {
        return embedder != null;
    }

    /**
     * The nearest vectors anywhere in the index.
     *
     * @param limit how many candidates to fetch, before the caller filters. For a scope with no
     *              admissible-set narrowing this is {@code pageSize × overFetch}
     */
    public Outcome nearest(String query, int limit) {
        return run(query, vector -> repository.nearest(embedder.model(), vector, limit));
    }

    /**
     * The nearest vectors among these skills only — the exact path for a bounded candidate set.
     *
     * <p>No over-fetch: nothing is going to be filtered out afterwards, because the set already is
     * the answer's universe.
     */
    public Outcome nearestWithin(String query, List<String> skillIds, int limit) {
        if (skillIds.isEmpty()) {
            return new Outcome(List.of(), null);
        }
        return run(query, vector ->
                repository.nearestWithin(embedder.model(), vector, skillIds, limit));
    }

    /**
     * The library's two semantic reads in one provider call (v59 P2).
     *
     * <p>The personal read is by the caller's id and never touches anybody else's rows; the corpus
     * read is the existing author-vector scan. A provider failure returns the same fallback reason
     * the other paths use, and the caller keeps its exact S0/S1 evidence either way.
     */
    public LibraryOutcome searchLibrary(String query, String userId, List<String> skillIds,
            int limit) {
        if (embedder == null || query == null || query.isBlank() || skillIds.isEmpty()) {
            return new LibraryOutcome(List.of(), List.of(), null);
        }
        try {
            float[] vector = embedder.embed(List.of(query)).getFirst();
            List<Candidate> corpus = repository.nearestWithin(embedder.model(), vector, skillIds,
                    limit).stream()
                    .filter(hit -> hit.score() >= settings.threshold())
                    .map(hit -> new Candidate(hit.skillId(), hit.score()))
                    .toList();
            // The S2 gate compares the top two among the caller's own sentences, so it must see
            // the whole library rather than the page being requested; a `limit`-sized read made
            // the same hit confident at limit=1 and suppressed at limit=2.
            List<PersonalHit> personal = repository.personalNearest(embedder.model(), userId,
                    vector, skillIds, Math.max(limit, skillIds.size())).stream()
                    .map(hit -> new PersonalHit(hit.skillId(), hit.score()))
                    .toList();
            return new LibraryOutcome(corpus, personal, null);
        } catch (RuntimeException e) {
            LOG.warn("library semantic search fell back to keyword: {}", e.getMessage());
            return new LibraryOutcome(List.of(), List.of(), "语义检索暂不可用。");
        }
    }

    private Outcome run(String query, Function<float[], List<EmbeddingRepository.Hit>> query1) {
        if (embedder == null) {
            // A configured state, not a fault: the caller decides silently (PRD #8 — turning the
            // switch off must leave the rest of the product untouched, including its wording).
            return new Outcome(List.of(), null);
        }
        if (query == null || query.isBlank()) {
            // A semantic search needs something to embed. An empty query is the listing case, which
            // is not this path's job, and embedding "" would answer with an arbitrary neighbourhood.
            return new Outcome(List.of(), null);
        }
        try {
            long started = System.nanoTime();
            float[] vector = embedder.embed(List.of(query)).getFirst();
            List<EmbeddingRepository.Hit> hits = query1.apply(vector);
            long elapsedMs = (System.nanoTime() - started) / 1_000_000;
            // Never the query itself: it is the caller's own words, and a search log is the easiest
            // place to leak them (PRD #9). Latency and counts are what the cost question needs.
            LOG.info("semantic search embedded a query in {} ms and found {} candidates", elapsedMs,
                    hits.size());
            return new Outcome(hits.stream()
                    .filter(hit -> hit.score() >= settings.threshold())
                    .map(hit -> new Candidate(hit.skillId(), hit.score()))
                    .toList(), null);
        } catch (RuntimeException e) {
            LOG.warn("semantic search fell back to keyword: {}", e.getMessage());
            // **Only the reason, never what the caller then did.** Whether the answer beside this
            // note came from the literal search — or does not exist at all, as for `scope=all` —
            // is something this module cannot know, and a sentence that guessed was wrong the
            // first time it ran.
            return new Outcome(List.of(), "语义检索暂不可用。");
        }
    }
}
