package com.skillmasterai.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.skillmasterai.common.Ulid;
import com.skillmasterai.support.AbstractAccountIT;
import com.skillmasterai.support.RecordingCaptchaRenderer;
import java.net.http.HttpResponse;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.jdbc.Sql;
import tools.jackson.databind.JsonNode;

/**
 * The captcha, which exists for one reason: the two endpoints that send an SMS spend money, and
 * without something in front of them an anonymous caller can spend it as fast as the network allows.
 *
 * <p>The tests substitute a renderer whose answer they know ({@code RecordingCaptchaRenderer}) —
 * an image cannot be read by a test. What that fake cannot check is whether the real image is
 * readable; {@code HutoolCaptchaRendererTest} is where that lives. Everything here is the part the
 * fake would otherwise hide: that the endpoints check the answer at all.
 *
 * <p><strong>A registration's first send from an address is not made to solve one</strong>, so most
 * of what follows spends that allowance first ({@link #spendFreeCodeSend}). Without it the free send
 * answers the request and a test about a refused answer passes because nothing ever asked for one.
 * What the free send itself does — and the one thing it deliberately does not do — is pinned by
 * {@code askingWhatTheNextSendCostsDoesNotSpendIt},
 * {@code aRegistrationCodeGoesOutWithNoCaptchaTheFirstTimeFromAnAddress} and
 * {@code theFreeSendDoesNotReadTheAnswerItWasGiven} — named rather than counted, because a count is
 * a pointer that goes stale the moment a test is inserted above it.
 */
@Sql("/sql/truncate-business-tables.sql")
class WebCaptchaIT extends AbstractAccountIT {

    @Test
    void aFreshAddressIsNotAskedForACaptcha() {
        assertThat(captchaRequired()).as("the first registration send is free").isFalse();
    }

    @Test
    void askingWhatTheNextSendCostsDoesNotSpendIt() {
        // The reason the question is a read. If asking were what spent the allowance, a form that asks
        // as it opens would hand every visitor a captcha to solve — the cost the free send exists to
        // remove, paid by the people it was meant for.
        captchaRequired();

        HttpResponse<String> response = webPost(REGISTER_CODE, json(Map.of("phone", randomPhone())));

        assertThat(response.statusCode()).as(response.body()).isEqualTo(204);
        assertThat(captchaRequired()).as("and the answer changes once one has gone out").isTrue();
    }

    @Test
    void theQuestionIsRateLimited() {
        assertThat(captchaRequired()).isFalse();
        jdbc.sql("UPDATE auth_throttle SET attempts = 120 WHERE scope = 'register:policy'").update();

        HttpResponse<String> refused = webGet(REGISTER_CODE + "/captcha-required");

        assertThat(refused.statusCode()).isEqualTo(429);
        assertThat(refused.headers().firstValue("Retry-After")).isPresent();
    }

    /** Whether the server says the next registration code send would be asked for a captcha. */
    private boolean captchaRequired() {
        HttpResponse<String> response = webGet(REGISTER_CODE + "/captcha-required");
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return body(response).get("required").asBoolean();
    }

    @Test
    void issuingDrawsAChallenge() {
        HttpResponse<String> response = webGet(CAPTCHA);

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = body(response);
        assertThat(body.get("captcha_id").asText()).isNotBlank();
        assertThat(Base64.getDecoder().decode(body.get("image").asText()))
                .as("the image must be a PNG")
                .startsWith((byte) 0x89, (byte) 'P', (byte) 'N', (byte) 'G');
    }

    @Test
    void theAnswerIsNotInTheResponse() {
        HttpResponse<String> response = webGet(CAPTCHA);

        // Two fields and no more: an endpoint that starts leaking the answer — under another name,
        // in another casing — would still be two fields, so this pins the count as well as the names.
        JsonNode body = body(response);
        assertThat(body.propertyNames()).containsExactlyInAnyOrder("captcha_id", "image");
        // An id that means something to the server, rather than "not the answer" — that could not
        // fail, since the answer is four characters and an id is a ULID.
        assertThat(Ulid.isValid(body.get("captcha_id").asText())).isTrue();
    }

    @Test
    void aRegistrationCodeGoesOutWithNoCaptchaTheFirstTimeFromAnAddress() {
        String phone = randomPhone();

        HttpResponse<String> response = webPost(REGISTER_CODE, json(Map.of("phone", phone)));

        // The point of the free send: an ordinary registration reads no image, and the request that
        // carries none is not a malformed one. Nothing this client's address has done stands in
        // front of it.
        assertThat(response.statusCode()).as(response.body()).isEqualTo(204);
        assertThat(code(phone)).isNotBlank();
    }

    @Test
    void theFreeSendDoesNotReadTheAnswerItWasGiven() {
        String phone = randomPhone();

        HttpResponse<String> response = webPost(REGISTER_CODE,
                codeRequestBody(phone, issueCaptcha(), "WRONG"));

        // Surprising, and deliberate. The free send asks nothing of the caller, so what the caller
        // put in the box is not consulted — and the challenge stays unspent, which is why one is
        // still listed. A client that offers a captcha anyway has not been told it was used, because
        // it was not.
        assertThat(response.statusCode()).as(response.body()).isEqualTo(204);
        assertThat(code(phone)).isNotBlank();
        assertThat(count("SELECT count(*) FROM captcha WHERE consumed_at IS NULL"))
                .as("the challenge it offered was not spent").isEqualTo(1);
    }

    @Test
    void theSecondSendFromAnAddressHasToSolveACaptcha() {
        spendFreeCodeSend();
        String phone = randomPhone();

        HttpResponse<String> response = webPost(REGISTER_CODE, json(Map.of("phone", phone)));

        // One per address per window, and the address has had its. This is what the concession costs:
        // every send after the first is back to being a toll, and what the caller gets back is the
        // signal that names the box they now have to fill.
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(field(response)).isEqualTo("captcha");
        assertThat(issue(response)).isEqualTo("required");
        assertThat(sms.lastCode(phone)).as("nothing may be sent without a captcha").isNull();
    }

    @Test
    void aCodeRequestWithTheWrongAnswerIsRefusedAndSendsNothing() {
        spendFreeCodeSend();
        String phone = randomPhone();

        HttpResponse<String> response = webPost(REGISTER_CODE,
                codeRequestBody(phone, issueCaptcha(), "WRONG"));

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(body(response).get("error").get("code").asText()).isEqualTo("invalid_request");
        // The half that costs money: a refused captcha must not have reached the sender.
        assertThat(sms.lastCode(phone)).isNull();
    }

    @Test
    void aSolvedCaptchaLetsTheCodeThrough() {
        spendFreeCodeSend();
        String phone = randomPhone();

        HttpResponse<String> response = webPost(REGISTER_CODE, codeRequestBody(phone));

        assertThat(response.statusCode()).as(response.body()).isEqualTo(204);
        assertThat(code(phone)).isNotBlank();
    }

    @Test
    void aCaptchaIsSpentByBeingUsed() {
        spendFreeCodeSend();
        String phone = randomPhone();
        String captcha = issueCaptcha();
        assertThat(webPost(REGISTER_CODE, codeRequestBody(phone, captcha))
                .statusCode()).isEqualTo(204);

        clearSmsCooldown();
        HttpResponse<String> again = webPost(REGISTER_CODE, codeRequestBody(phone, captcha));

        // Single use is what makes the image worth solving once rather than once per request: the
        // same answer would otherwise be replayable for as long as it has not expired.
        assertThat(again.statusCode()).isEqualTo(400);
        assertThat(body(again).get("error").get("details").get(0).get("issue").asText())
                .isEqualTo("invalid");
    }

    @Test
    void aCaptchaStopsBeingUsableAfterTooManyGuesses() {
        spendFreeCodeSend();
        String phone = randomPhone();
        String captcha = issueCaptcha();

        HttpResponse<String> firstGuess = webPost(REGISTER_CODE,
                codeRequestBody(phone, captcha, "WRONG"));
        for (int attempt = 1; attempt < 3; attempt++) {
            webPost(REGISTER_CODE, codeRequestBody(phone, captcha, "WRONG"));
        }
        HttpResponse<String> rightAtLast = webPost(REGISTER_CODE,
                codeRequestBody(phone, captcha, RecordingCaptchaRenderer.ANSWER));

        // Four characters are worth a few tries, not unlimited ones: without the cap the image is
        // worth nothing, because a million guesses is a few seconds of scripting.
        assertThat(rightAtLast.statusCode()).isEqualTo(400);
        // And the answer that ran out of tries is refused in exactly the words a wrong one gets. A
        // caller who could tell them apart could sort challenges by how close it had been.
        assertThat(rightAtLast.body()).isEqualTo(firstGuess.body());
        // Three, read back from the row: without this the cap could be lowered to two and every
        // assertion above would still hold, since the fourth request is refused either way.
        assertThat(count("SELECT attempts FROM captcha")).isEqualTo(3);
        assertThat(sms.lastCode(phone)).isNull();
    }

    @Test
    void anExpiredCaptchaIsRefusedExactlyLikeAWrongAnswer() {
        spendFreeCodeSend();
        String phone = randomPhone();
        HttpResponse<String> wrongAnswer = webPost(REGISTER_CODE,
                codeRequestBody(phone, issueCaptcha(), "WRONG"));

        String stale = issueCaptcha();
        jdbc.sql("UPDATE captcha SET expires_at = '2000-01-01T00:00:00Z' WHERE id = :id")
                .param("id", stale)
                .update();
        HttpResponse<String> tooLate = webPost(REGISTER_CODE,
                codeRequestBody(phone, stale, RecordingCaptchaRenderer.ANSWER));

        assertThat(tooLate.statusCode()).isEqualTo(400);
        assertThat(tooLate.body()).isEqualTo(wrongAnswer.body());
        assertThat(sms.lastCode(phone)).isNull();
    }

    @Test
    void aChallengeIsIssuedWithFiveMinutesToLive() {
        issueCaptcha();

        // Read back from the row, and as a difference rather than against a clock: the lifetime is
        // what decides how long a solved-but-unused answer stays good, and no other test pins it.
        assertThat(lifetimeSecondsOf("captcha"))
                .as("a challenge should be good for five minutes")
                .isBetween(299L, 300L);
    }

    @Test
    void challengesNobodyCanAnswerAreSweptAway() {
        String dead = issueCaptcha();
        jdbc.sql("UPDATE captcha SET expires_at = '2000-01-01T00:00:00Z' WHERE id = :id")
                .param("id", dead)
                .update();

        issueCaptcha();

        // Issuing is the one thing here an anonymous caller can do without spending anything, so
        // its rows have to be trimmed by something. The sweep runs on the insert path — and it has
        // to leave the challenge that was just issued alone, which is the other half of this.
        assertThat(count("SELECT count(*) FROM captcha WHERE id = :id", Map.of("id", dead)))
                .as("the challenge that expired")
                .isZero();
        assertThat(count("SELECT count(*) FROM captcha")).as("the one just issued").isEqualTo(1);
    }

    @Test
    void issuingIsRateLimited() {
        issueCaptcha();
        // The counter is moved rather than spent a hundred and twenty times, and moved to one below
        // the cap first: that is what makes the number itself the thing under test. Preset to
        // exactly the cap, the refusal below would come out the same for 119 or for 120, and the
        // request would look refused whether or not it spent anything.
        jdbc.sql("UPDATE auth_throttle SET attempts = 119 WHERE scope = 'captcha:ip'").update();

        HttpResponse<String> lastOne = webGet(CAPTCHA);

        assertThat(lastOne.statusCode()).as("the hundred and twentieth is still allowed").isEqualTo(200);

        HttpResponse<String> refused = webGet(CAPTCHA);

        assertThat(refused.statusCode()).isEqualTo(429);
        assertThat(body(refused).get("error").get("code").asText()).isEqualTo("too_many_requests");
        assertThat(refused.headers().firstValue("Retry-After")).isPresent();
    }

    @Test
    void theCallersAddressIsTheOneTheProxyForwarded() {
        // Without `server.forward-headers-strategy`, `getRemoteAddr()` is the proxy's address for
        // every caller, and all three address-scoped rules become one bucket shared by the whole
        // user base — the 31st message of an hour would refuse the 32nd person to register. Two
        // forwarded addresses have to be two callers, and this is the only assertion in the suite
        // that can tell.
        send(request(CAPTCHA, null).header("X-Forwarded-For", "203.0.113.7").GET().build());
        send(request(CAPTCHA, null).header("X-Forwarded-For", "203.0.113.8").GET().build());

        assertThat(count("SELECT count(*) FROM auth_throttle WHERE scope = 'captcha:ip'"))
                .as("two forwarded addresses are two buckets")
                .isEqualTo(2);
    }
}
