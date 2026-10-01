package com.skillmasterai.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.skillmasterai.support.AbstractAccountIT;
import com.skillmasterai.support.Browser;
import java.net.http.HttpResponse;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.jdbc.Sql;

/**
 * Password reset, whose second half is the part that is easy to leave out.
 *
 * <p>Changing the password but leaving the old sessions alive leaves whoever had the old password
 * signed in — which is the situation the person resetting is usually trying to get out of. That is
 * why the revocation runs through {@code WebSessionRegistry} and why the sessions are asserted on
 * rather than inferred from the response.
 *
 * <p>What this cannot yet cover is the other half of that revocation: M2's tokens. They do not
 * exist, so there is nothing to revoke; when they do, the call joins the same seam.
 */
@Sql("/sql/truncate-business-tables.sql")
class WebPasswordResetIT extends AbstractAccountIT {

    private static final String NEW_PASSWORD = "a-different-long-password";

    @Test
    void resetReplacesThePassword() {
        String phone = randomPhone();
        registerAndSignIn(randomUsername(), phone);

        HttpResponse<String> response = reset(phone);

        assertThat(response.statusCode()).as(response.body()).isEqualTo(204);
        assertThat(signIn(phone, PASSWORD).statusCode()).isEqualTo(401);
        assertThat(signIn(phone, NEW_PASSWORD).statusCode()).isEqualTo(200);
    }

    @Test
    void resetEndsEverySessionTheAccountHad() {
        String phone = randomPhone();
        registerAndSignIn(randomUsername(), phone);
        String userId = body(webGet(SESSION)).get("user_id").asText();
        Browser second = secondSignedInBrowser(phone, PASSWORD);
        assertThat(sessionsOf(userId)).as("the account should have two sessions").isEqualTo(2);

        assertThat(reset(phone).statusCode()).isEqualTo(204);

        assertThat(sessionsOf(userId)).isZero();
        // Both clients, not just the one that asked: a revocation that only ended the current
        // session would pass a count taken from a single client.
        assertThat(webGet(SESSION).statusCode()).isEqualTo(401);
        assertThat(second.get(uri(SESSION)).statusCode()).isEqualTo(401);
    }

    @Test
    void resetDoesNotSignTheCallerIn() {
        String phone = randomPhone();
        registerAndSignIn(randomUsername(), phone);

        assertThat(reset(phone).statusCode()).isEqualTo(204);

        // Not signed in afterwards, on purpose: the client logs in with the new password, which is
        // what proves to the person resetting that the password they chose is the one that works.
        assertThat(webGet(SESSION).statusCode()).isEqualTo(401);
    }

    @Test
    void aCodeIssuedForRegistrationIsNotAcceptedForReset() {
        String phone = randomPhone();
        String registrationCode = requestCode(REGISTER_CODE, phone);

        HttpResponse<String> response = webPost(RESET, json(Map.of(
                "phone", phone, "code", registrationCode, "password", NEW_PASSWORD)));

        // The two flows are kept apart by the stored purpose, not by the shape of the request: the
        // weaker of them must not be a way into the stronger one.
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(body(response).get("error").get("code").asText())
                .isEqualTo("verification_code_invalid");
    }

    @Test
    void resettingANumberNobodyRegisteredIsRefused() {
        String phone = randomPhone();

        HttpResponse<String> response = reset(phone);

        // Reachable only by somebody who just read a code sent to this number, so this says nothing
        // they did not already control — and answering as though it had worked would send them to a
        // login that fails for a reason nothing explained.
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(body(response).get("error").get("code").asText()).isEqualTo("invalid_request");
        assertThat(field(response)).isEqualTo("phone");
        // The issue code, not just the field: it is what the client turns into a sentence, and
        // `no_account` is the only one it words as "this number has no account yet" rather than
        // "check what you typed".
        assertThat(issue(response)).isEqualTo("no_account");
    }

    @Test
    void threeWrongCodesSpendTheCodeSoTheFourthGuessIsRefusedWhateverItIs() {
        String phone = randomPhone();
        registerAndSignIn(randomUsername(), phone);
        webPost(LOGOUT, "");
        clearSmsCooldown();
        String sent = requestCode(RESET_CODE, phone);

        for (int guess = 1; guess <= 3; guess++) {
            HttpResponse<String> refused = resetWith(phone, wrongCode(sent));
            assertThat(refused.statusCode()).as("guess %s: %s", guess, refused.body()).isEqualTo(400);
            assertThat(body(refused).get("error").get("code").asText())
                    .isEqualTo("verification_code_invalid");
        }

        // The three attempts are spent, so the code that was actually sent is refused too. This is
        // the assertion that keeps the count from being rolled back with the refusal it belongs to —
        // six digits are a few thousand requests inside a five-minute life, and the count is the
        // only thing that makes them hard to enumerate.
        assertThat(resetWith(phone, sent).statusCode()).isEqualTo(400);
        // The one row still live is the reset code; registration's was consumed and is still there.
        assertThat(count("SELECT attempts FROM phone_verification WHERE consumed_at IS NULL"))
                .isEqualTo(3);

        // And nothing half-happened: a refused reset leaves the password exactly as it was.
        assertThat(signIn(phone, PASSWORD).statusCode()).isEqualTo(200);
    }

    @Test
    void askingForASecondCodeRetiresTheFirst() {
        String phone = randomPhone();
        registerAndSignIn(randomUsername(), phone);
        webPost(LOGOUT, "");

        clearSmsCooldown();
        String first = requestCode(RESET_CODE, phone);
        clearSmsCooldown();
        String second = requestCode(RESET_CODE, phone);
        assertThat(second).as("a second request issues a different code").isNotEqualTo(first);

        assertThat(resetWith(phone, second).statusCode()).isEqualTo(204);

        // The first code was superseded, and it is the reset flow that shows it: registration masks
        // the same hole with `already_registered`. Without the retirement, this one still resets the
        // password — a code its owner believes is dead stays live for the rest of its five minutes.
        HttpResponse<String> superseded = resetWith(phone, first, "yet-another-password");
        assertThat(superseded.statusCode()).isEqualTo(400);
        assertThat(body(superseded).get("error").get("code").asText())
                .isEqualTo("verification_code_invalid");
    }

    @Test
    void aCodeStopsBeingUsableOnceItHasBeenUsed() {
        String phone = randomPhone();
        registerAndSignIn(randomUsername(), phone);
        webPost(LOGOUT, "");
        clearSmsCooldown();
        String sent = requestCode(RESET_CODE, phone);

        assertThat(resetWith(phone, sent).statusCode()).isEqualTo(204);

        // The registration flow pins the same thing for its own codes; without it a code stays good
        // for the rest of its five minutes after it has done its job, so anyone who read the message
        // or replayed the request could use it again.
        HttpResponse<String> replayed = resetWith(phone, sent);
        assertThat(replayed.statusCode()).isEqualTo(400);
        assertThat(body(replayed).get("error").get("code").asText())
                .isEqualTo("verification_code_invalid");
    }

    @Test
    void resettingEndsTheSessionsOfThatAccountAndNobodyElses() {
        String phone = randomPhone();
        registerAndSignIn(randomUsername(), phone);
        String userId = body(webGet(SESSION)).get("user_id").asText();
        String otherPhone = randomPhone();
        registerAndSignIn(randomUsername(), otherPhone);
        String otherUserId = body(webGet(SESSION)).get("user_id").asText();
        assertThat(sessionsOf(otherUserId)).as("the other account should still be signed in")
                .isEqualTo(1);

        assertThat(reset(phone).statusCode()).isEqualTo(204);

        // A revocation that enumerated the store rather than the account would pass every assertion
        // taken from a single account — which is what this test had until now.
        assertThat(sessionsOf(userId)).isZero();
        assertThat(sessionsOf(otherUserId)).isEqualTo(1);
    }

    /** Asks for a reset code and spends it, which is the whole flow in one call. */
    private HttpResponse<String> reset(String phone) {
        // The cooldown is one message per number per minute and counts by number rather than by
        // flow, so registering an account with this number has already spent it. What is under test
        // here is the reset, and the cooldown is pinned in AccountThrottleIT.
        clearSmsCooldown();
        return webPost(RESET, json(Map.of("phone", phone,
                "code", requestCode(RESET_CODE, phone), "password", NEW_PASSWORD)));
    }

    /** The same request with the code and password given rather than arranged. */
    private HttpResponse<String> resetWith(String phone, String code) {
        return resetWith(phone, code, NEW_PASSWORD);
    }

    private HttpResponse<String> resetWith(String phone, String code, String password) {
        return webPost(RESET, json(Map.of("phone", phone, "code", code, "password", password)));
    }

    /** A six-digit code that is not the one that was sent, with the digits moved by one. */
    private static String wrongCode(String sent) {
        StringBuilder wrong = new StringBuilder(sent.length());
        for (char digit : sent.toCharArray()) {
            wrong.append(digit == '9' ? '0' : (char) (digit + 1));
        }
        return wrong.toString();
    }

    private long sessionsOf(String userId) {
        return count("SELECT count(*) FROM spring_session WHERE principal_name = :id",
                Map.of("id", userId));
    }
}
