package com.skillmasterai.modules.account;

/**
 * Sends one verification code to one phone number.
 *
 * <p>A seam with a deliberately thin shape, because the only implementation is eventually a
 * third-party SDK whose signature and template must be approved before it can send anything
 * (TD §8, question 12). Until then this is satisfied by something that logs, and swapping in the
 * real one is a new implementation rather than a change here.
 *
 * <p>Implementations must let a failure out rather than swallow it: a code that was not sent and a
 * code that was sent but not delivered look the same to the caller, and only one of them should
 * look like success to us.
 */
@FunctionalInterface
public interface SmsSender {

    /** @param code the six digits to put in the message */
    void send(String phone, String code);
}
