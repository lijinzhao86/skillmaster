package com.skillmasterai.modules.skill;

/**
 * Which body of skills a search looks in (v19).
 *
 * <p><strong>Three, and the third is not redundant.</strong> {@link #LIBRARY} is the private
 * domain — the skills the caller created, was granted, or joined (ADR 0082's {@code source IS NOT
 * NULL}). {@link #PUBLIC} is the discovery plane. {@link #ALL} is both at once, which is what a
 * person means by "have I got something for this, anywhere": a public skill they never added is
 * invisible to the library scope, and a private draft is invisible to the public one.
 *
 * <p>The wire names are the contract and the constants are ours — the same rule
 * {@code SearchRequest.SortOrder} follows: Spring's default enum conversion would have accepted
 * {@code scope=LIBRARY} and refused the documented {@code library}.
 */
public enum SearchScope {
    LIBRARY("library"),
    PUBLIC("public"),
    ALL("all");

    private final String wireName;

    SearchScope(String wireName) {
        this.wireName = wireName;
    }

    /**
     * @return empty for a value no scope names. <strong>Empty rather than an exception</strong>:
     *         M12 may not depend on M8, and "this is a bad request" is the transport's judgement
     *         to make. The controller turns it into the 400 with the parameter's name.
     */
    public static java.util.Optional<SearchScope> fromWire(String value) {
        for (SearchScope scope : values()) {
            if (scope.wireName.equals(value)) {
                return java.util.Optional.of(scope);
            }
        }
        return java.util.Optional.empty();
    }
}
