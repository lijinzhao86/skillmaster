package com.skillmasterai.modules.account;

/**
 * Whether a verification code that was presented is compared against the one that was issued.
 *
 * <p><strong>{@link #ANY} is a hole held open on purpose, not a feature.</strong> No code can be sent
 * at all until the SMS provider has approved a signature and a template, and that approval is on
 * somebody else's clock — so a deployment that wants to exercise the whole registration and recovery
 * flow in the meantime can have the flow without the comparison. Everything else about a code stays:
 * one still has to have been asked for, it still expires after five minutes, and it is still spent by
 * being used.
 *
 * <p>A named type rather than a boolean because M1 may not read configuration (see
 * {@code config.SkillmasterProperties}): the value is decided in {@code config.SmsConfig} and handed
 * over, and naming the decision is what keeps it legible at the place it is taken — including at the
 * guard that refuses to start when it contradicts a configured provider.
 */
public enum CodeComparison {

    /** The digits are compared, and a code that does not match is refused. */
    DIGITS,

    /** Any six digits are accepted. Correct only where nobody is being let in yet. */
    ANY
}
