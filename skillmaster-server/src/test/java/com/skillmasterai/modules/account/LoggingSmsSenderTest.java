package com.skillmasterai.modules.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * The stand-in sender, whose two modes are the whole class.
 *
 * <p>Asserted against the log rather than against a return value, because that is where both claims
 * live: what it writes when it is willing to write, and that it writes nothing when it is not. A
 * future change that put the code into a production log — or the unmasked number into a local one —
 * would satisfy every behavioural test written any other way.
 */
class LoggingSmsSenderTest {

    private static final String PHONE = "13800138000";
    private static final String CODE = "123456";

    private final ListAppender<ILoggingEvent> logged = new ListAppender<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(LoggingSmsSender.class);

    @BeforeEach
    void attachToTheLog() {
        logged.start();
        logger.addAppender(logged);
    }

    @AfterEach
    void detachFromTheLog() {
        logger.detachAppender(logged);
    }

    @Test
    void writesTheCodeWhereSomebodyIsReadingTheLog() {
        // The local-run mode: the whole point is that a developer or a test can read the code out of
        // the terminal and finish the flow by hand.
        new LoggingSmsSender(true).send(PHONE, CODE);

        assertThat(messages()).contains(CODE);
    }

    @Test
    void doesNotWriteTheNumberItWasGiven() {
        // Willing to write the code is not willing to write the number: one is a value generated for
        // this message, the other identifies a person.
        new LoggingSmsSender(true).send(PHONE, CODE);

        assertThat(messages()).doesNotContain(PHONE).contains("138****8000");
    }

    @Test
    void refusesRatherThanPretendingWhenNoProviderIsConfigured() {
        // A request that reports success while nothing was sent is worse than one that fails: the
        // person waits for a message that is never coming, and nothing anywhere says so. It also
        // means an unconfigured deployment cannot be mistaken for a working one.
        assertThatThrownBy(() -> new LoggingSmsSender(false).send(PHONE, CODE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("skillmaster.sms.log-codes");
    }

    @Test
    void writesNothingAtAllWhenItRefuses() {
        assertThatThrownBy(() -> new LoggingSmsSender(false).send(PHONE, CODE))
                .isInstanceOf(IllegalStateException.class);

        // Not even masked, and never the code: a verification code in a production log is a code an
        // operator — or anybody who can read logs — can use during the minutes it is valid.
        assertThat(logged.list).isEmpty();
    }

    private String messages() {
        return logged.list.stream().map(ILoggingEvent::getFormattedMessage)
                .collect(Collectors.joining("\n"));
    }
}
