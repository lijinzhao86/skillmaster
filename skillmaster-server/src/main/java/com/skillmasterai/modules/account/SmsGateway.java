package com.skillmasterai.modules.account;

/**
 * One templated message, handed to a provider.
 *
 * <p>The seam between {@link AliyunSmsSender} — which knows what a verification code is and what
 * must happen when a message does not go out — and the vendor SDK, which knows how to sign a
 * request. Everything above this line is testable without a network; everything below it is a
 * handful of lines that cannot be, and is therefore kept as small as it can be.
 *
 * <p>The return value is a plain verdict rather than the provider's status string, so that a
 * provider's vocabulary stays on its own side of the seam.
 */
@FunctionalInterface
public interface SmsGateway {

    Receipt send(String phone, String signName, String templateCode, String templateParam);

    /**
     * What the provider said about one message.
     *
     * @param accepted whether it took the message
     * @param detail   its own words for what happened — the reason is the whole value of a refusal,
     *                 because "the send failed" is not something an operator can act on
     */
    record Receipt(boolean accepted, String detail) {

        /** Named {@code ok} rather than {@code accepted} so the factory does not collide with the
         * accessor of the component it sets. */
        public static Receipt ok() {
            return new Receipt(true, "OK");
        }

        public static Receipt refused(String detail) {
            return new Receipt(false, detail);
        }
    }
}
