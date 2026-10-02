package com.skillmasterai.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.skillmasterai.support.AbstractAccountIT;
import java.net.http.HttpResponse;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.jdbc.Sql;

/**
 * The SMS limits, which are the ones that spend money.
 *
 * <p>Two things are being pinned and they are different in kind. The cooldown is a rule about the
 * experience — one message per number per minute — and the daily cap is the one that bounds what a
 * determined person can cost. Both are asserted through the endpoint rather than against the
 * counter, because a counter that is written and never consulted is not a limit.
 *
 * <p>Where a test needs to get past one rule to reach another, it moves that rule's window rather
 * than waiting: the windows are fixed intervals computed from the clock, so moving the row is the
 * same statement as waiting out the minute, in less time than it takes to read this.
 */
@Sql("/sql/truncate-business-tables.sql")
class AccountThrottleIT extends AbstractAccountIT {

    @Test
    void aSecondMessageWithinTheMinuteIsRefusedAndNotSent() {
        String phone = randomPhone();
        String first = requestCode(phone);

        HttpResponse<String> second = webPost(REGISTER_CODE, codeRequestBody(phone));

        assertThat(second.statusCode()).isEqualTo(429);
        assertThat(body(second).get("error").get("code").asText()).isEqualTo("too_many_requests");
        // Refused before anything was handed to the sender, which is the half that costs money. A
        // resend would have generated a different code, so the same code is evidence of no send.
        assertThat(code(phone)).isEqualTo(first);
    }

    @Test
    void theRefusalSaysHowLongToWait() {
        String phone = randomPhone();
        requestCode(phone);

        HttpResponse<String> refused = webPost(REGISTER_CODE, codeRequestBody(phone));

        String retryAfter = refused.headers().firstValue("Retry-After").orElse(null);
        assertThat(retryAfter).as("a 429 without Retry-After invites the retry loop it exists to stop")
                .isNotNull();
        assertThat(Long.parseLong(retryAfter)).isBetween(1L, 60L);
    }

    @Test
    void theDailyCapRefusesEvenOnceTheCooldownHasPassed() {
        String phone = randomPhone();
        for (int sent = 1; sent <= 10; sent++) {
            assertThat(webPost(REGISTER_CODE, codeRequestBody(phone)).statusCode())
                    .as("message %s of the day's ten", sent)
                    .isEqualTo(204);
            clearSmsCooldown();
        }

        HttpResponse<String> eleventh = webPost(REGISTER_CODE, codeRequestBody(phone));

        assertThat(eleventh.statusCode()).isEqualTo(429);
        // Ten, and not eleven, because the refusal rolled its whole transaction back: the eleven
        // requests were counted as they came, and the last one's count went with it. What that buys
        // is that a refused request does not push the counter further past the cap every time it is
        // retried — the rule holds at the cap rather than climbing away from it.
        assertThat(longValue("SELECT max(attempts) FROM auth_throttle WHERE scope = 'sms:daily'"))
                .isEqualTo(10L);
    }

    @Test
    void theDailyCounterIsNotSpentByARefusedRequest() {
        String phone = randomPhone();
        requestCode(phone);
        long afterOne = longValue(
                "SELECT max(attempts) FROM auth_throttle WHERE scope = 'sms:daily'");

        webPost(REGISTER_CODE, codeRequestBody(phone));

        assertThat(longValue("SELECT max(attempts) FROM auth_throttle WHERE scope = 'sms:daily'"))
                .isEqualTo(afterOne);
    }

    @Test
    void theAddressCapRefusesEvenWhenTheNumberIsWellWithinItsOwnBudget() {
        String phone = randomPhone();
        String first = requestCode(phone);
        clearSmsCooldown();

        // The address rule is a row keyed by the caller's address, and every test here arrives from
        // the same one, so it is moved rather than spent thirty times.
        assertThat(count("SELECT count(*) FROM auth_throttle WHERE scope = 'sms:ip'"))
                .as("one address has been seen").isEqualTo(1);
        jdbc.sql("UPDATE auth_throttle SET attempts = 30 WHERE scope = 'sms:ip'").update();

        HttpResponse<String> refused = webPost(REGISTER_CODE, codeRequestBody(phone));

        // This rule is the only one that notices a caller working through a list of numbers: the
        // per-number cooldown and daily cap each look at one number, and a list has many.
        assertThat(refused.statusCode()).isEqualTo(429);
        assertThat(body(refused).get("error").get("code").asText()).isEqualTo("too_many_requests");
        // And nothing went out, which is the half that costs money. A resend would have generated a
        // different code, so the same code is evidence of no send.
        assertThat(code(phone)).isEqualTo(first);
    }

    @Test
    void theFreeSendIsClaimedOnceRatherThanCounted() {
        assertThat(count("SELECT count(*) FROM auth_throttle WHERE scope = 'sms:free'"))
                .as("an address that has not sent has no row").isZero();

        webPost(REGISTER_CODE, json(Map.of("phone", randomPhone())));
        webPost(REGISTER_CODE, json(Map.of("phone", randomPhone())));

        // A claim records that the allowance has been taken, not how often anybody asked: one row per
        // address per window, and its count never climbs. That is the whole reason this is not one of
        // the counters above.
        assertThat(count("SELECT count(*) FROM auth_throttle WHERE scope = 'sms:free'")).isEqualTo(1);
        assertThat(longValue("SELECT attempts FROM auth_throttle WHERE scope = 'sms:free'"))
                .isEqualTo(1L);
    }

    @Test
    void aRefusedSendHandsTheFreeSendBack() {
        String phone = randomPhone();
        requestCode(phone);
        // Put the allowance back and move the address rule to its cap, so that the next send is
        // claimed and refused inside the same transaction. Any other arrangement has a rule firing
        // before the claim, and then the rollback is not what is being tested.
        jdbc.sql("DELETE FROM auth_throttle WHERE scope = 'sms:free'").update();
        jdbc.sql("UPDATE auth_throttle SET attempts = 30 WHERE scope = 'sms:ip'").update();

        assertThat(webPost(REGISTER_CODE, codeRequestBody(randomPhone())).statusCode())
                .as("refused by the address rule").isEqualTo(429);

        // Nothing went out, so nothing was spent — including the claim, which the refusal rolled back
        // with everything else. Without that, a caller refused for a reason that costs no message
        // would still have paid their one free send for it.
        assertThat(count("SELECT count(*) FROM auth_throttle WHERE scope = 'sms:free'"))
                .as("the claim went back with the rollback").isZero();
    }

    private long longValue(String sql) {
        Long value = jdbc.sql(sql).query(Long.class).optional().orElse(null);
        return value == null ? 0L : value;
    }
}
