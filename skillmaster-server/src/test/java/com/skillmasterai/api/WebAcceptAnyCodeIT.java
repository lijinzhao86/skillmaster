package com.skillmasterai.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.skillmasterai.modules.account.PhoneCipher;
import com.skillmasterai.support.AbstractAccountIT;
import java.net.http.HttpResponse;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.jdbc.Sql;

/**
 * The deployment state that exists while the SMS provider has approved nothing: the flow is whole,
 * the comparison is not.
 *
 * <p>Its own context, because {@code accept-any-code} is read once at startup — which is the point of
 * it being configuration and not a request parameter, and this class is what pays for the second one.
 * Everything else in the suite runs with it off, and that is what keeps
 * {@code WebRegistrationIT.aCodeThatDoesNotMatchIsRefused} honest.
 *
 * <p>What is pinned here is the <em>shape</em> of the hole: the digits are what is not checked, not
 * the step. A code still has to have been asked for, it still expires, and it is still spent by being
 * used — so the two-screen flow is exercised exactly as it will be once codes are real.
 */
@SpringBootTest(properties = "skillmaster.sms.accept-any-code=true",
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Sql("/sql/truncate-business-tables.sql")
class WebAcceptAnyCodeIT extends AbstractAccountIT {

    private static final String NEW_PASSWORD = "a-different-long-password";

    @Autowired
    private PhoneCipher cipher;

    @Test
    void anyDigitsAtAllRegisterTheAccount() {
        String phone = randomPhone();
        String issued = requestCode(phone);

        HttpResponse<String> response = webPost(REGISTER, json(Map.of(
                "phone", phone, "code", wrong(issued),
                "password", PASSWORD, "username", randomUsername())));

        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        // Signed in like any other registration, because it is one: the account is real and complete,
        // and the only thing that did not happen is the check that the person holds the number.
        assertThat(webGet(SESSION).statusCode()).isEqualTo(200);
    }

    @Test
    void aCodeStillHasToHaveBeenAskedFor() {
        // The step is not what was switched off. With nothing outstanding there is no request for a
        // code to be about, so a client cannot skip the send by inventing one.
        HttpResponse<String> response = webPost(REGISTER, json(Map.of(
                "phone", randomPhone(), "code", "000000",
                "password", PASSWORD, "username", randomUsername())));

        assertThat(response.statusCode()).as(response.body()).isEqualTo(400);
        assertThat(body(response).get("error").get("code").asText())
                .isEqualTo("verification_code_invalid");
    }

    @Test
    void aCodeStillHasToLookLikeOne() {
        // What the switch removes is the *comparison*, not the shape: 「任意六位数字」 is the contract,
        // and a client that sends one digit — or leaves the field out entirely — is refused exactly as
        // it would be with real codes. Without this the mode would take an empty string, which no
        // client could have meant and no document claims.
        String phone = randomPhone();
        requestCode(phone);
        String other = randomPhone();
        requestCode(other);

        HttpResponse<String> tooShort = webPost(REGISTER, json(Map.of(
                "phone", phone, "code", "1", "password", PASSWORD, "username", randomUsername())));
        HttpResponse<String> missing = webPost(REGISTER, json(Map.of(
                "phone", other, "password", PASSWORD, "username", randomUsername())));

        assertThat(tooShort.statusCode()).as(tooShort.body()).isEqualTo(400);
        assertThat(missing.statusCode()).as(missing.body()).isEqualTo(400);
    }

    @Test
    void aCodeStillExpires() {
        String phone = randomPhone();
        requestCode(phone);
        // Fast-forward rather than sleep: the lifetime is a column, so the test moves the column.
        jdbc.sql("UPDATE phone_verification SET expires_at = '2000-01-01T00:00:00Z'"
                + " WHERE phone_hash = :hash").param("hash", cipher.hash(phone)).update();

        HttpResponse<String> response = webPost(REGISTER, json(Map.of(
                "phone", phone, "code", "000000",
                "password", PASSWORD, "username", randomUsername())));

        assertThat(response.statusCode()).as(response.body()).isEqualTo(400);
    }

    @Test
    void anyDigitsAtAllAlsoResetThePassword() {
        // The worse of the two flows, and the reason the setting is refused outright beside real
        // credentials: with it on, knowing a number is enough to take the account from whoever holds
        // it. Pinned rather than left implied, so nobody has to reason about whether it applies here.
        String phone = randomPhone();
        registerAndSignIn(randomUsername(), phone);
        clearSmsCooldown();
        String issued = requestCode(RESET_CODE, phone);

        HttpResponse<String> response = webPost(RESET, json(Map.of(
                "phone", phone, "code", wrong(issued), "password", NEW_PASSWORD)));

        assertThat(response.statusCode()).as(response.body()).isEqualTo(204);
        assertThat(signIn(phone, NEW_PASSWORD).statusCode()).isEqualTo(200);
    }

    /** A code that is not the one that was issued, without assuming which digits those turned out to be. */
    private static String wrong(String issued) {
        return issued.equals("000000") ? "000001" : "000000";
    }
}
