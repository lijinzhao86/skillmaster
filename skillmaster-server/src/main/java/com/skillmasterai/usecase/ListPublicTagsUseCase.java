package com.skillmasterai.usecase;

import com.skillmasterai.common.CursorCodec;
import com.skillmasterai.modules.search.InvalidSearchRequestException;
import com.skillmasterai.modules.skill.SkillTagService;
import com.skillmasterai.modules.skill.SkillTagService.PublicTagRow;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The public-tag vocabulary with its public counts (v14).
 *
 * <p>Paged by keyset because this one grows with use: the default is 50 rows and the CLI's
 * {@code --all} walks the cursor. The order is the product's — count descending, then name, then
 * key — and the key carries all three parts, so a page boundary cannot fall through a tie and the
 * database's collation cannot reorder a name differently from the key that continues the page.
 *
 * <p>{@code public_total} is the discoverable count, not the management page's "used in". The two
 * numbers have different meanings and different field names on purpose (PRD §展示与计数口径).
 */
@Component
public class ListPublicTagsUseCase {

    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 100;

    private static final String ORDERING = "public-tags";

    private final SkillTagService tags;

    public ListPublicTagsUseCase(SkillTagService tags) {
        this.tags = tags;
    }

    @Transactional(readOnly = true)
    public Page list(String q, Integer limit, String cursor) {
        int pageSize;
        if (limit == null) {
            pageSize = DEFAULT_LIMIT;
        } else if (limit < 1) {
            throw new InvalidSearchRequestException("limit must be at least 1, was " + limit);
        } else {
            pageSize = Math.min(limit, MAX_LIMIT);
        }

        List<String> afterKey = resumeKey(cursor);
        List<PublicTagRow> rows = tags.publicVocabulary(q, pageSize + 1, afterKey);
        String next = rows.size() > pageSize
                ? CursorCodec.encode(ORDERING,
                        List.of(String.valueOf(rows.get(pageSize - 1).publicTotal()),
                                rows.get(pageSize - 1).name(),
                                rows.get(pageSize - 1).key()))
                : null;
        return new Page(rows.stream().limit(pageSize).toList(), next);
    }

    private static List<String> resumeKey(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        CursorCodec.Cursor decoded = CursorCodec.decode(cursor)
                .orElseThrow(() -> new InvalidSearchRequestException(
                        "the cursor is not one this version understands"));
        if (!ORDERING.equals(decoded.ordering()) || decoded.key().size() != 3) {
            throw new InvalidSearchRequestException(
                    "the cursor belongs to a different ordering");
        }
        // The first part feeds a CAST(… AS bigint); a non-number would be a 500 for what §4.1
        // calls a cursor nobody can read. A blank name or key could not be a real word either.
        if (!decoded.key().get(0).matches("[0-9]+") || decoded.key().get(1).isBlank()
                || decoded.key().get(2).isBlank()) {
            throw new InvalidSearchRequestException(
                    "the cursor is not one this version understands");
        }
        return decoded.key();
    }

    /** @param nextCursor null on the last page — the CLI's {@code --all} loops until it is null */
    public record Page(List<PublicTagRow> tags, String nextCursor) {
        public Page {
            tags = List.copyOf(tags);
        }
    }
}
