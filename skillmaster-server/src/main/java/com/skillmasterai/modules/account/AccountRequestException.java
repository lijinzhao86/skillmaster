package com.skillmasterai.modules.account;

/**
 * A field of an account request was refused, and the client can fix it.
 *
 * <p>One type for every such field rather than one per case, because the field and the issue
 * together are already the specific thing and the wire answer is the same for all of them: a 400
 * naming which field and what is wrong with it. The alternative is a code per field, which is four
 * codes where one code and a detail say strictly more.
 *
 * <p>Carries no values from the request. Messages are built here from the field name alone, so a
 * rejected phone number cannot reach a log line through an exception message.
 */
public final class AccountRequestException extends RuntimeException {

    private final String field;
    private final String issue;

    public AccountRequestException(String field, String issue) {
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
