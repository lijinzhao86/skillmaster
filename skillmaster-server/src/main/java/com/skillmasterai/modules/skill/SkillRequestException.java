package com.skillmasterai.modules.skill;

/**
 * A field of a skill request was refused, and the client can fix it.
 *
 * <p>The same shape as the account module's request exception, and for the same reason: one type
 * for every such field, because the field and the issue together are already the specific thing and
 * the wire answer is a 400 naming both. Carries no values from the request.
 *
 * <p>New in v5 because the ladder gave these routes values a client composes — a rung, a visibility
 * pair — and an invalid one is the caller's to fix rather than a 403 or a 404.
 */
public final class SkillRequestException extends RuntimeException {

    private final String field;
    private final String issue;

    public SkillRequestException(String field, String issue) {
        super("the request's " + field + " was refused: " + issue);
        this.field = field;
        this.issue = issue;
    }

    /** The request field the client should look at, named as it appears on the wire. */
    public String field() {
        return field;
    }

    /** A stable code for what is wrong with it, for the error's {@code details[].issue}. */
    public String issue() {
        return issue;
    }
}
