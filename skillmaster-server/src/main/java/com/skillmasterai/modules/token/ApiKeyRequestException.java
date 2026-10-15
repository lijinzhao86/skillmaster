package com.skillmasterai.modules.token;

import java.util.List;
import java.util.Map;

/**
 * Something about an issue-or-renew request was refused, and the client can fix it.
 *
 * <p><strong>One type carrying a named problem rather than one type per code</strong>, in the shape
 * {@code AccountRequestException} already has: the client gets a 400 that names the field and says
 * what is wrong with it, and the wire code is derived from {@link Problem} at the edge. The module
 * says what kind of thing was wrong; which string goes on the wire is the API layer's business, so
 * the mapping lives there with the other translations.
 *
 * <p>Carries no values from the request — the message is built from the field name and a stable
 * issue code, so a key's name or a date somebody typed cannot reach a log line through an exception.
 */
public final class ApiKeyRequestException extends RuntimeException {

    /**
     * What kind of thing was wrong, and — because each of these is about exactly one field — which
     * field the client should look at. {@code REVOKED} is the exception: it is about the key the
     * request named rather than about a value in the body, and it is here rather than in a type of its
     * own because the answer is the same 400-with-a-reason.
     */
    public enum Problem {
        NAME("name"),
        NOTE("note"),
        // `scopes` — the name the request body uses, because this is the field a client has to fix.
        // It said `scope` (the singular, matching the concept) while the wire says `scopes`, so a
        // client that fills its form from `details[].field` — this repository's own console included —
        // looked for a field that is not there.
        SCOPE("scopes"),
        EXPIRY("expires_at"),
        REVOKED("id");

        private final String field;

        Problem(String field) {
            this.field = field;
        }

        public String field() {
            return field;
        }
    }

    private static final Map<Problem, List<String>> ALLOWED_ISSUES = Map.of(
            Problem.NAME, List.of("blank", "too_long"),
            Problem.NOTE, List.of("too_long"),
            Problem.SCOPE, List.of("unknown", "empty"),
            Problem.EXPIRY, List.of("not_in_the_future", "beyond_maximum"),
            Problem.REVOKED, List.of("revoked"));

    private final Problem problem;
    private final String issue;

    public ApiKeyRequestException(Problem problem, String issue) {
        super("the request's " + problem.field() + " was refused: " + issue);
        if (!ALLOWED_ISSUES.get(problem).contains(issue)) {
            // A typo in an issue code would put a string on the wire that no client handles and no
            // test expects, and the failure would look like a client bug.
            throw new IllegalArgumentException(
                    issue + " is not an issue this type reports for " + problem);
        }
        this.problem = problem;
        this.issue = issue;
    }

    public Problem problem() {
        return problem;
    }

    /** The request field the client should look at, named as it appears on the wire. */
    public String field() {
        return problem.field();
    }

    /** A stable code for what is wrong with it, for the error's {@code details[].issue}. */
    public String issue() {
        return issue;
    }
}
