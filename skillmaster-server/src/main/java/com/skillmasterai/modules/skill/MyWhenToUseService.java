package com.skillmasterai.modules.skill;

import com.skillmasterai.common.ApiError;
import com.skillmasterai.common.SkillFieldLimits;
import com.skillmasterai.common.ErrorCode;
import com.skillmasterai.common.RequestException;
import com.skillmasterai.modules.skill.internal.MyWhenToUseRepository;
import java.util.List;
import java.util.Optional;

/**
 * M12's seam for the caller's own "when I would look for this" sentence (v59).
 *
 * <p><strong>Spelling is decided here, once.</strong> The browser and the CLI send what a person
 * typed; {@link #parse} trims it, folds every internal run of whitespace (newlines included) into one
 * space, and counts the result in Unicode code points. The limit is the same 200 the private tag's
 * description uses, and the unit is the same one ADR 0179 chose for every text field.
 *
 * <p><strong>An empty sentence is not a clear.</strong> Clearing is the DELETE route; a PUT with
 * nothing left after normalization is refused so that "I meant to clear" and "I sent an empty body"
 * stay distinguishable.
 */
public final class MyWhenToUseService {

    /** 1–200 Unicode code points; one han character, one Latin letter and one punctuation mark each
     * count as one (ADR 0179). */
    public static final int MAX_CODE_POINTS = SkillFieldLimits.MY_WHEN_TO_USE_MAX_CODE_POINTS;

    private final MyWhenToUseRepository repository;

    public MyWhenToUseService(MyWhenToUseRepository repository) {
        this.repository = repository;
    }

    /**
     * @return the normalized sentence
     * @throws RequestException {@code MY_WHEN_TO_USE_EMPTY} when nothing is left, or
     *         {@code MY_WHEN_TO_USE_TOO_LONG} when it is above the limit — long input is refused
     *         rather than truncated, so the person knows what was kept
     */
    public String parse(String raw) {
        String text = raw == null ? ""
                : raw.replaceAll("[\\s\\p{Z}]+", " ").strip();
        if (text.isEmpty()) {
            throw new RequestException(ErrorCode.MY_WHEN_TO_USE_EMPTY,
                    "写一句你平时会怎么找它；要清除请用 clear");
        }
        int actual = SkillFieldLimits.codePoints(text);
        if (actual > MAX_CODE_POINTS) {
            throw new RequestException(ErrorCode.MY_WHEN_TO_USE_TOO_LONG,
                    "我的适用场景最多 " + MAX_CODE_POINTS + " 个字符（汉字、英文字母、标点各算 1 个）；当前 "
                            + actual + " 个",
                    List.of(ApiError.Detail.codePoints("when_to_use", "too_long", actual,
                            MAX_CODE_POINTS)));
        }
        return text;
    }

    /** The caller's sentences for a page of skills, keyed by skill id. */
    public java.util.Map<String, String> forSkills(String userId, java.util.List<String> skillIds) {
        return repository.forSkills(userId, skillIds);
    }

    /** Every sentence this caller has written, keyed by skill id. */
    public java.util.Map<String, String> allFor(String userId) {
        return repository.allFor(userId);
    }

    /** The current sentence, or empty when the caller has never written one. */
    public Optional<String> find(String userId, String skillId) {
        return repository.find(userId, skillId);
    }

    /** @return whether the stored sentence changed */
    public boolean set(String userId, String skillId, String whenToUse, String updatedAt) {
        return repository.set(userId, skillId, whenToUse, updatedAt);
    }

    /** @return whether a sentence was removed; none is a no-op */
    public boolean clear(String userId, String skillId) {
        return repository.clear(userId, skillId);
    }
}
