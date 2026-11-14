package com.skillmasterai.modules.skill;

/**
 * A label write named a value that is not in the vocabulary, or is not one this seam writes.
 *
 * <p>Carries the offending id so the inner endpoint can name it in the 400 rather than make the
 * operator guess which of a set was wrong.
 */
public final class UnknownLabelException extends RuntimeException {

    private final String definitionId;

    public UnknownLabelException(String definitionId, String message) {
        super(message + ": " + definitionId);
        this.definitionId = definitionId;
    }

    public String definitionId() {
        return definitionId;
    }
}
