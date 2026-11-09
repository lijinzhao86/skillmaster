package com.skillmasterai.modules.secret;

import com.skillmasterai.common.ConflictException;
import com.skillmasterai.common.ErrorCode;

/**
 * The requested binding (or share) collides with one that already exists — 409.
 *
 * <p>Two codes travel through this type because both are the same shape of conflict at the two
 * levels the product exposes: a binding on {@code (skill, env)} that one owner already has, and a
 * share that is already live for the same person. The wire name is what the CLI switches on, so the
 * constant is chosen by the caller rather than by the exception class.
 */
public class BindingConflictException extends ConflictException {

    private static final long serialVersionUID = 1L;

    public BindingConflictException(String message) {
        this(ErrorCode.CREDENTIAL_BINDING_CONFLICT, message);
    }

    public BindingConflictException(ErrorCode code, String message) {
        super(code, message);
    }

    /** A share conflict, which the schema reports with {@code credential_share_conflict}. */
    public static BindingConflictException share(String message) {
        return new BindingConflictException(ErrorCode.CREDENTIAL_SHARE_CONFLICT, message);
    }
}
