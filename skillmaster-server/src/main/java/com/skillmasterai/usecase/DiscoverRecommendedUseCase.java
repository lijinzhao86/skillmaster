package com.skillmasterai.usecase;

import com.skillmasterai.common.CursorCodec;
import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.modules.namespace.NamespaceService;
import com.skillmasterai.modules.search.InvalidSearchRequestException;
import com.skillmasterai.modules.skill.Caller;
import com.skillmasterai.modules.skill.SkillCatalogService;
import com.skillmasterai.modules.skill.SkillLibraryService;
import com.skillmasterai.usecase.model.SkillCardView;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The bounded "recommended for you" stream on the discovery page (v14, TD Q4).
 *
 * <p><strong>Its own route, not a search scope.</strong> It has no query text and no relevance
 * ordering: the rule is "public, published, online, not in my library, newest first", and it is
 * paged by keyset in batches of six. Putting it into the search would add a "no input" exception to
 * a route whose contract is "input is required".
 *
 * <p>The rule is explainable on purpose: changing it later is changing the ordering behind this one
 * method, not introducing a recommendation model (PRD #8 / 不做 #26).
 */
@Component
public class DiscoverRecommendedUseCase {

    public static final int DEFAULT_BATCH = 6;

    /**
     * The largest batch the route will assemble, whatever {@code limit} says.
     *
     * <p>The product fixes the batch at six; the cap exists so an arbitrary query parameter cannot
     * turn one request into a thousand-row card assembly (each card costs extra queries).
     */
    public static final int MAX_BATCH = 50;

    private static final String ORDERING = "discover:recommended";

    private final NamespaceService namespaces;
    private final SkillLibraryService library;
    private final SkillCardAssembler cards;

    public DiscoverRecommendedUseCase(NamespaceService namespaces, SkillLibraryService library,
            SkillCardAssembler cards) {
        this.namespaces = namespaces;
        this.library = library;
        this.cards = cards;
    }

    @Transactional(readOnly = true)
    public Result recommended(String cursor, Integer limit, AuthenticatedSubject subject) {
        Caller caller = Callers.of(namespaces, subject);
        int batch = limit == null ? DEFAULT_BATCH : limit;
        if (batch < 1) {
            throw new InvalidSearchRequestException("limit must be at least 1, was " + batch);
        }
        batch = Math.min(batch, MAX_BATCH);

        List<String> afterKey = resumeKey(cursor);
        List<SkillCatalogService.CatalogRow> rows =
                library.recommended(afterKey, batch + 1, caller);
        String next = rows.size() > batch
                ? CursorCodec.encode(ORDERING,
                        List.of(rows.get(batch - 1).updatedAt(), rows.get(batch - 1).id()))
                : null;
        List<SkillCatalogService.CatalogRow> page = rows.stream().limit(batch).toList();
        return new Result(cards.assemble(page, subject.userId()), next);
    }

    private static List<String> resumeKey(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        CursorCodec.Cursor decoded = CursorCodec.decode(cursor)
                .orElseThrow(() -> new InvalidSearchRequestException(
                        "the cursor is not one this version understands"));
        if (!ORDERING.equals(decoded.ordering()) || decoded.key().size() != 2
                || decoded.key().get(1).isBlank()) {
            throw new InvalidSearchRequestException(
                    "the cursor belongs to a different ordering");
        }
        try {
            if (!com.skillmasterai.common.Timestamps.format(
                    com.skillmasterai.common.Timestamps.parse(decoded.key().get(0)))
                    .equals(decoded.key().get(0))) {
                throw new InvalidSearchRequestException(
                        "the cursor is not one this version understands");
            }
        } catch (java.time.format.DateTimeParseException e) {
            throw new InvalidSearchRequestException(
                    "the cursor is not one this version understands");
        }
        return decoded.key();
    }

    public record Result(List<SkillCardView> cards, String nextCursor) {
        public Result {
            cards = List.copyOf(cards);
        }
    }
}
