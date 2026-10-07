package com.skillmasterai.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.skillmasterai.support.AbstractAccountIT;
import java.net.http.HttpResponse;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.jdbc.Sql;

/**
 * Login: one answer for every way it can fail, and a limit on how many times it can be tried.
 *
 * <p>The first is a claim about bytes. Three failures — no such phone, wrong password, suspended
 * account — have to be indistinguishable in the response, or the endpoint can be sorted through to
 * find which numbers have accounts, which is the first step of every credential attack against a
 * phone-as-identifier system.
 *
 * <p>The second is a claim about the eleventh request. Without it this endpoint answers password
 * guesses as fast as the network allows.
 */
@Sql("/sql/truncate-business-tables.sql")
class WebLoginIT extends AbstractAccountIT {

    @Test
    void anUnknownPhoneAndAWrongPasswordAnswerTheSameWay() {
        String phone = randomPhone();
        registerAndSignIn(randomUsername(), phone);
        webPost(LOGOUT, "");

        HttpResponse<String> wrongPassword = signIn(phone, "not-the-password");
        HttpResponse<String> unknownPhone = signIn(randomPhone(), PASSWORD);

        assertThat(wrongPassword.statusCode()).isEqualTo(401);
        assertThat(unknownPhone.statusCode()).isEqualTo(401);
        assertThat(unknownPhone.body()).isEqualTo(wrongPassword.body());
        assertThat(body(unknownPhone).get("error").get("code").asText())
                .isEqualTo("invalid_credentials");
    }

    @Test
    void aSuspendedAccountAnswersTheSameWayAgain() {
        String phone = randomPhone();
        registerAndSignIn(randomUsername(), phone);
        String userId = body(webGet(SESSION)).get("user_id").asText();
        webPost(LOGOUT, "");
        HttpResponse<String> wrongPassword = signIn(phone, "not-the-password");

        jdbc.sql("UPDATE app_user SET status = 'suspended' WHERE id = :id")
                .param("id", userId).update();
        HttpResponse<String> suspended = signIn(phone, PASSWORD);

        // The password was right. The answer is the one a wrong password gets, because a suspended
        // account is a thing the caller has no business learning about from this endpoint.
        assertThat(suspended.statusCode()).isEqualTo(401);
        assertThat(suspended.body()).isEqualTo(wrongPassword.body());
    }

    @Test
    void aCorrectPasswordIsRefusedOnceThePhoneHasSpentItsBudget() {
        String phone = randomPhone();
        registerAndSignIn(randomUsername(), phone);
        for (int attempt = 0; attempt < 10; attempt++) {
            assertThat(signIn(phone, "not-the-password").statusCode())
                    .as("attempt %s should have been an ordinary 401", attempt + 1)
                    .isEqualTo(401);
        }

        HttpResponse<String> refused = signIn(phone, PASSWORD);

        // The budget is spent before the password is compared, so the right password is refused
        // too. The alternative — count only failures — lets an attacker keep guessing for as long
        // as it takes to be right once.
        assertThat(refused.statusCode()).isEqualTo(429);
        assertThat(refused.headers().firstValue("Retry-After")).isPresent();
        assertThat(body(refused).get("error").get("code").asText()).isEqualTo("too_many_requests");
        // Ten, from the ten wrong passwords above — and not eleven. The phone rule refused before
        // the password was compared, so this request never reached the rule that counts failures.
        // Otherwise anybody could burn a whole office's address budget by hammering one number
        // nobody owns, locking out everyone behind the same NAT.
        assertThat(count("SELECT max(attempts) FROM auth_throttle WHERE scope = 'login:ip'"))
                .as("a request refused by the phone rule must not spend the address budget")
                .isEqualTo(10L);
    }

    @Test
    void anExhaustedAddressDoesNotStopThePhoneBudgetFromCounting() {
        String phone = randomPhone();
        registerAndSignIn(randomUsername(), phone);
        webPost(LOGOUT, "");
        // One failure to create the address row — registration signs nobody in — and then the row is
        // put past its cap. The guesses below are refused for the address until the phone's own
        // counter catches up, which the tenth of them is.
        assertThat(signIn(phone, "not-the-password").statusCode()).isEqualTo(401);
        assertThat(count("SELECT count(*) FROM auth_throttle WHERE scope = 'login:ip'"))
                .as("one address has now failed once").isEqualTo(1);
        long countedSoFar = count("SELECT attempts FROM auth_throttle WHERE scope = 'login:phone'");
        jdbc.sql("UPDATE auth_throttle SET attempts = 51 WHERE scope = 'login:ip'").update();

        // Every one of these must be counted against the phone, including the nine the address
        // refuses and the tenth that the phone's own capped counter refuses. A refusal that rolled
        // back the attempt it belongs to would leave the phone's counter where it was, so it would
        // never reach its cap, and this endpoint would go on comparing passwords for one account
        // without limit — answering 200 for the right one.
        for (int guess = 1; guess <= 10; guess++) {
            assertThat(signIn(phone, "not-the-password").statusCode())
                    .as("guess %s", guess)
                    .isEqualTo(429);
        }

        assertThat(count("SELECT attempts FROM auth_throttle WHERE scope = 'login:phone'"))
                .as("every refused guess still has to move the phone's counter")
                .isEqualTo(countedSoFar + 10);
    }

    @Test
    void theAddressBudgetIsSpentByFailuresAndNeverBySuccesses() {
        String phone = randomPhone();
        registerAndSignIn(randomUsername(), phone);
        webPost(LOGOUT, "");
        assertThat(signIn(phone, "not-the-password").statusCode()).isEqualTo(401);

        // The address counter is written rather than spent fifty times: it is a fixed-window row
        // keyed by the caller's address, so moving it is the same statement as making the requests.
        // The failed sign-in above is what created it — a success does not.
        assertThat(count("SELECT count(*) FROM auth_throttle WHERE scope = 'login:ip'"))
                .as("one address has now failed once").isEqualTo(1);
        jdbc.sql("UPDATE auth_throttle SET attempts = 50 WHERE scope = 'login:ip'").update();

        // A sign-in that *succeeds* must not spend it. An address is not a person — a whole office
        // or a carrier's NAT arrives from one — so a budget that every attempt spent would end up
        // refusing somebody whose password was right, and no success could clear it without also
        // letting an attacker who owns one account reset the budget he spends on the others.
        assertThat(signIn(phone, PASSWORD).statusCode()).isEqualTo(200);
        webPost(LOGOUT, "");

        HttpResponse<String> refused = signIn(phone, "not-the-password");

        assertThat(refused.statusCode()).isEqualTo(429);
        assertThat(body(refused).get("error").get("code").asText()).isEqualTo("too_many_requests");
    }

    @Test
    void anUnknownPhoneIsThrottledTheSameWay() {
        String phone = randomPhone();
        for (int attempt = 0; attempt < 10; attempt++) {
            signIn(phone, PASSWORD);
        }

        // A number nobody registered is limited exactly like one that is. If it were not, the 429
        // would be precisely the account-existence oracle the identical response bodies exist to
        // avoid.
        assertThat(signIn(phone, PASSWORD).statusCode()).isEqualTo(429);
    }

    @Test
    void aCorrectPasswordSignsIn() {
        String phone = randomPhone();
        registerAndSignIn(randomUsername(), phone);
        String userId = body(webGet(SESSION)).get("user_id").asText();
        webPost(LOGOUT, "");

        HttpResponse<String> response = signIn(phone, PASSWORD);

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(body(response).get("user_id").asText()).isEqualTo(userId);
        assertThat(webGet(SESSION).statusCode()).isEqualTo(200);
    }

    @Test
    void aPhoneThatCouldNotBeOneIsRefusedAsAFieldError() {
        // Told apart from the three indistinguishable credential failures on purpose. The shape of a
        // number is not a secret — it is in the request — and "check what you typed" is a different
        // instruction from "those credentials are wrong". The password is not checked this way; see
        // the next test.
        HttpResponse<String> malformed =
                webPost(LOGIN, json(Map.of("phone", "12800138000", "password", PASSWORD)));
        HttpResponse<String> absent = webPost(LOGIN, json(Map.of("password", PASSWORD)));

        assertThat(malformed.statusCode()).as(malformed.body()).isEqualTo(400);
        assertThat(field(malformed)).isEqualTo("phone");
        assertThat(issue(malformed)).isEqualTo("invalid_format");
        assertThat(absent.statusCode()).as(absent.body()).isEqualTo(400);
        assertThat(issue(absent)).isEqualTo("required");
    }

    @Test
    void anEmptyOrMissingPasswordIsRefusedAsBadCredentialsRatherThanAsAFieldError() {
        // The password is never checked for shape here, and this is the reason: the account it would
        // be describing may belong to somebody else, and "too short" says something about a password
        // that this endpoint otherwise refuses to say anything about. The client refuses an empty box
        // before it is sent, so no person meets this branch — but it is reachable with curl, and a
        // 500 in place of a 401 would be a defect with the shape of a working answer.
        String phone = randomPhone();
        registerAndSignIn(randomUsername(), phone);
        webPost(LOGOUT, "");

        HttpResponse<String> empty = webPost(LOGIN, json(Map.of("phone", phone, "password", "")));
        HttpResponse<String> absent = webPost(LOGIN, json(Map.of("phone", phone)));

        assertThat(empty.statusCode()).as(empty.body()).isEqualTo(401);
        assertThat(absent.statusCode()).as(absent.body()).isEqualTo(401);
        assertThat(empty.body()).isEqualTo(absent.body());
        assertThat(body(empty).get("error").get("code").asText()).isEqualTo("invalid_credentials");
        assertThat(body(empty).get("error").get("details")).isEmpty();
    }

    @Test
    void aSuccessfulSignInClearsTheFailuresCountedAgainstTheNumber() {
        String phone = randomPhone();
        registerAndSignIn(randomUsername(), phone);
        webPost(LOGOUT, "");
        for (int attempt = 0; attempt < 5; attempt++) {
            assertThat(signIn(phone, "not-the-password").statusCode()).isEqualTo(401);
        }

        assertThat(signIn(phone, PASSWORD).statusCode()).isEqualTo(200);

        // The whole budget is available again: without the clear, these ten would be the sixth
        // through fifteenth attempts of a window that allows ten, and the last few would be 429.
        // What it buys is a person who mistyped a few times and then got it right not carrying
        // those failures towards a lockout they did nothing to deserve.
        for (int attempt = 1; attempt <= 10; attempt++) {
            assertThat(signIn(phone, "not-the-password").statusCode())
                    .as("attempt %s after the successful sign-in", attempt)
                    .isEqualTo(401);
        }

        // And the counter really is the thing being tested here: the eleventh is refused.
        assertThat(signIn(phone, "not-the-password").statusCode()).isEqualTo(429);
    }
}
