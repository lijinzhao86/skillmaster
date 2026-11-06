package com.skillmasterai.modules.skill;

/**
 * The platform's own content. It needs no network and carries no signals: the trust is the
 * namespace's {@code official} flag (ADR 0146), not a metric.
 */
public final class OfficialSourceAdapter implements SourceAdapter {

    public static final String KIND = "official";

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public SourceBinding parse(String locator, String path) {
        return new SourceBinding(KIND, locator, path, "官方", null);
    }

    @Override
    public FetchedSource fetch(SourceBinding binding, String revisionHint) {
        return new FetchedSource(revisionHint, "[]", SourceSnapshot.OK, null);
    }
}
