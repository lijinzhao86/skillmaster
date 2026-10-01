package com.skillmasterai.modules.account;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What stands in for an SMS provider until one is configured.
 *
 * <p>The real sender is a third-party SDK whose signature and template have to be approved before
 * it can send anything (TD §8, question 12). Until that exists this is the implementation behind
 * the seam, and it has two modes:
 *
 * <ul>
 *   <li><strong>Reading the code out of a terminal</strong> — for a developer or a test, where the
 *       code has to go somewhere a person can see it.</li>
 *   <li><strong>Refusing</strong> — everywhere else. Not logging, and not quietly succeeding: a
 *       request that reports success while no code was sent is worse than a request that fails,
 *       because the user waits for a message that is never coming and nothing in the logs says so.
 *       Failing also means an unconfigured deployment cannot be mistaken for a working one.</li>
 * </ul>
 *
 * <p>The refusal branch deliberately does not log the code. A verification code in a production log
 * is a code an operator — or anyone who can read logs — can use to take over an account during the
 * window it is valid.
 */
public final class LoggingSmsSender implements SmsSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingSmsSender.class);

    private final boolean logCodes;

    /** @param logCodes true only where a person is reading the log to complete a flow by hand */
    public LoggingSmsSender(boolean logCodes) {
        this.logCodes = logCodes;
    }

    @Override
    public void send(String phone, String code) {
        if (!logCodes) {
            throw new IllegalStateException(
                    "No SMS provider is configured, so no verification code was sent. Set "
                            + "skillmaster.sms.access-key-id and its siblings, or set "
                            + "skillmaster.sms.log-codes for a local run where reading the code "
                            + "out of the log is the point.");
        }
        log.info("SMS to {}: verification code {}", mask(phone), code);
    }

    /** Enough of the number to tell which attempt this was, and not enough to be the number. */
    private static String mask(String phone) {
        if (phone == null || phone.length() < 7) {
            return "***";
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }
}
