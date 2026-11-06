package com.skillmasterai.modules.skill;

/**
 * One fact an adapter found about a source, in a shape the page can render without knowing the
 * platform.
 *
 * <p>{@code kind} is the whole rendering vocabulary: {@code count}, {@code date} or {@code text}.
 * A GitHub star count and a SkillHub install count are both {@code count}; the page does not branch
 * on the source's name.
 */
public record SourceSignal(String key, String label, String kind, String value, String display,
        String url) {
}
