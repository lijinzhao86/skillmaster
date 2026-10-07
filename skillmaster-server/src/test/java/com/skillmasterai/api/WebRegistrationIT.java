package com.skillmasterai.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.skillmasterai.common.Ulid;
import com.skillmasterai.modules.account.PhoneCipher;
import com.skillmasterai.support.AbstractAccountIT;
import java.net.http.HttpResponse;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.jdbc.Sql;
import tools.jackson.databind.JsonNode;

/**
 * Registration, the one flow that spans two modules in one transaction: M1 writes the account and
 * its credential, M4 writes the namespace it publishes into.
 *
 * <p>Several of the assertions reach into the database as well as the response, because what they
 * claim is about stored rows that no response should ever show — that the phone number is not
 * stored as it was given, and that the session is indexed by the user id. Those are exactly the
 * claims a response-only test would pass while the property was broken.
 */
@Sql("/sql/truncate-business-tables.sql")
class WebRegistrationIT extends AbstractAccountIT {

    @Autowired
    private PhoneCipher cipher;

    @Test
    void registrationCreatesTheAccountItsNamespaceAndItsSession() {
        String username = randomUsername();
        String phone = randomPhone();

        HttpResponse<String> response = register(username, phone);

        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        JsonNode account = body(response);
        String userId = account.get("user_id").asText();
        assertThat(Ulid.isValid(userId)).isTrue();
        assertThat(account.get("username").asText()).isEqualTo(username);
        assertThat(account.get("namespace").asText()).isEqualTo(username);

        String storedHash = jdbc.sql("SELECT phone_hash FROM app_user WHERE id = :id")
                .param("id", userId).query(String.class).single();
        String storedCiphertext = jdbc.sql("SELECT phone_enc FROM app_user WHERE id = :id")
                .param("id", userId).query(String.class).single();
        assertThat(storedHash).isEqualTo(cipher.hash(phone)).isNotEqualTo(phone);
        assertThat(cipher.decrypt(storedCiphertext)).isEqualTo(phone);
        // And the number as it was given is in no column: one column is a keyed hash, the other is
        // sealed, and neither equals what the client sent.
        assertThat(count("SELECT count(*) FROM app_user WHERE phone_hash = :phone"
                + " OR phone_enc = :phone", Map.of("phone", phone))).isZero();

        // M4's half of the same transaction.
        assertThat(count("SELECT count(*) FROM namespace WHERE owner_user_id = :id AND slug = :slug",
                Map.of("id", userId, "slug", username))).isEqualTo(1);
        assertThat(jdbc.sql("SELECT role FROM namespace_member WHERE user_id = :id")
                .param("id", userId).query(String.class).single()).isEqualTo("owner");

        // The session is indexed by the user id. Load-bearing rather than incidental: revoking
        // every session a user has is a lookup on this column, and a principal name of the username
        // would satisfy every other assertion in this class.
        assertThat(count("SELECT count(*) FROM spring_session WHERE principal_name = :id",
                Map.of("id", userId))).isEqualTo(1);
    }

    @Test
    void aUsernameSomebodyElseHasIsRefused() {
        String username = randomUsername();
        registerAndSignIn(username, randomPhone());

        HttpResponse<String> response = register(username, randomPhone());

        assertThat(response.statusCode()).as(response.body()).isEqualTo(400);
        assertThat(body(response).get("error").get("code").asText()).isEqualTo("invalid_request");
        assertThat(field(response)).isEqualTo("username");
        assertThat(issue(response)).isEqualTo("already_taken");
    }

    @Test
    void theReservedUsernameIsRefused() {
        // Held by the system account V2 seeds, which is how the gateway's own namespace stays out
        // of reach. The guard is UNIQUE(handle), so this arrives as a field error and not a 500.
        HttpResponse<String> response = register("skillmaster", randomPhone());

        assertThat(response.statusCode()).as(response.body()).isEqualTo(400);
        assertThat(issue(response)).isEqualTo("already_taken");
    }

    @Test
    void aUsernameThatCouldNotBeAnAddressIsRefused() {
        // Two failures, told apart in the order the client can act on: the alphabet is wrong here,
        // and answering "too short" would send the author off to type more Chinese characters.
        HttpResponse<String> chinese = register("飞书", randomPhone());
        HttpResponse<String> tooShort = register("ab", randomPhone());

        assertThat(issue(chinese)).isEqualTo("invalid_format");
        assertThat(issue(tooShort)).isEqualTo("invalid_length");
    }

    @Test
    void aCodeThatDoesNotMatchIsRefused() {
        String phone = randomPhone();
        String issued = requestCode(phone);

        HttpResponse<String> response = registerWithCode(randomUsername(), phone, wrong(issued));

        assertThat(response.statusCode()).as(response.body()).isEqualTo(400);
        assertThat(body(response).get("error").get("code").asText())
                .isEqualTo("verification_code_invalid");
    }

    @Test
    void anExpiredCodeIsRefusedAndStaysRefused() {
        String phone = randomPhone();
        String issued = requestCode(phone);
        // Fast-forward rather than sleep: the lifetime is a column, so the test moves the column.
        jdbc.sql("UPDATE phone_verification SET expires_at = '2000-01-01T00:00:00Z'"
                + " WHERE phone_hash = :hash").param("hash", cipher.hash(phone)).update();

        HttpResponse<String> expired = registerWithCode(randomUsername(), phone, issued);
        HttpResponse<String> again = registerWithCode(randomUsername(), phone, issued);

        assertThat(expired.statusCode()).isEqualTo(400);
        // Spending the expired row is what makes this stay refused: otherwise a code that expired
        // while the form was open would come back to life on the next attempt.
        assertThat(body(again).get("error").get("code").asText())
                .isEqualTo("verification_code_invalid");
    }

    @Test
    void aCodeIsIssuedWithFiveMinutesToLive() {
        requestCode(randomPhone());

        // Read back from the row. Until now only the expiry *check* was pinned — a lifetime changed
        // to a year would have left every test green while a leaked message stayed worth something.
        assertThat(lifetimeSecondsOf("phone_verification"))
                .as("a code should be good for five minutes")
                .isBetween(299L, 300L);
    }

    @Test
    void aCodeStopsBeingUsableAfterTooManyGuesses() {
        String phone = randomPhone();
        String issued = requestCode(phone);
        String wrong = wrong(issued);

        HttpResponse<String> firstGuess = registerWithCode(randomUsername(), phone, wrong);
        for (int attempt = 0; attempt < 3; attempt++) {
            registerWithCode(randomUsername(), phone, wrong);
        }
        HttpResponse<String> rightCodeAtLast = registerWithCode(randomUsername(), phone, issued);

        // Every refusal is the same answer, and the correct code is refused too: the row is spent,
        // which is what stops six digits from being enumerable in a few thousand requests.
        assertThat(firstGuess.statusCode()).isEqualTo(400);
        assertThat(rightCodeAtLast.statusCode()).isEqualTo(400);
        assertThat(rightCodeAtLast.body()).isEqualTo(firstGuess.body());
    }

    @Test
    void aPhoneThatAlreadyHasAnAccountIsRefused() {
        String phone = randomPhone();
        registerAndSignIn(randomUsername(), phone);
        clearSmsCooldown();

        HttpResponse<String> response = register(randomUsername(), phone);

        assertThat(response.statusCode()).as(response.body()).isEqualTo(400);
        assertThat(field(response)).isEqualTo("phone");
        assertThat(issue(response)).isEqualTo("already_registered");
    }

    @Test
    void aPhoneThatCouldNotBeOneIsRefusedBeforeAnythingIsSent() {
        // The shape is checked before the captcha is spent and before a message is paid for. The
        // claim is about the Sender, not the status code: a 400 that had already sent would still
        // read as a refusal from outside.
        String phone = "12800138000";

        HttpResponse<String> response = webPost(REGISTER_CODE, codeRequestBody(phone));

        assertThat(response.statusCode()).as(response.body()).isEqualTo(400);
        assertThat(field(response)).isEqualTo("phone");
        assertThat(issue(response)).isEqualTo("invalid_format");
        assertThat(sms.lastCode(phone)).as("nothing was handed to the sender").isNull();
    }

    @Test
    void aPasswordFromTheBlocklistIsRefused() {
        // The real list, through the real endpoint: the unit tests prove the rule, this proves the
        // bean is wired to it. A blocklist that nobody consults is the failure both are guarding.
        String phone = randomPhone();
        String code = requestCode(phone);

        HttpResponse<String> response = webPost(REGISTER, json(Map.of(
                "phone", phone, "code", code, "password", "password", "username", randomUsername())));

        assertThat(response.statusCode()).as(response.body()).isEqualTo(400);
        assertThat(field(response)).isEqualTo("password");
        assertThat(issue(response)).isEqualTo("too_common");
    }

    @Test
    void aPasswordWithFullWidthCharactersIsRefused() {
        // The case the rule exists for (ADR 0017): full-width text looks exactly like the half-width
        // password it is not — and being three bytes per character, it walked straight past the
        // blocklist. `ｐａｓｓｗｏｒｄ` is `password` typed with the input method in full-width mode.
        String phone = randomPhone();
        String code = requestCode(phone);

        HttpResponse<String> response = webPost(REGISTER, json(Map.of(
                "phone", phone, "code", code, "password", "ｐａｓｓｗｏｒｄ",
                "username", randomUsername())));

        assertThat(response.statusCode()).as(response.body()).isEqualTo(400);
        assertThat(field(response)).isEqualTo("password");
        assertThat(issue(response)).isEqualTo("invalid_format");
    }

    @Test
    void aPasswordMadeOfTheHandleIsRefused() {
        String phone = randomPhone();
        String username = randomUsername();
        String code = requestCode(phone);

        HttpResponse<String> response = webPost(REGISTER, json(Map.of(
                "phone", phone, "code", code, "password", username + "123", "username", username)));

        assertThat(response.statusCode()).as(response.body()).isEqualTo(400);
        assertThat(field(response)).isEqualTo("password");
        assertThat(issue(response)).isEqualTo("too_common");
    }

    private HttpResponse<String> register(String username, String phone) {
        return registerWithCode(username, phone, requestCode(phone));
    }

    private HttpResponse<String> registerWithCode(String username, String phone, String code) {
        return webPost(REGISTER, json(Map.of(
                "phone", phone, "code", code, "password", PASSWORD, "username", username)));
    }

    /** A six-digit string that is certainly not the one that was sent. */
    private static String wrong(String issued) {
        return issued.equals("000000") ? "000001" : "000000";
    }
}
