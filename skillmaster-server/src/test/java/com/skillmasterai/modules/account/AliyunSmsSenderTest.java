package com.skillmasterai.modules.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * What must be true of the sender, now that the SDK call itself sits behind a seam.
 *
 * <p>The call cannot be tested here — it needs credentials, a network and an approved template, and
 * the SDK is not even imported by this module. What is tested is everything on this side of it, and
 * one of these matters far more than the rest: Aliyun answers HTTP 200 for a message it refused, so
 * a sender that reads only "the call returned" reports success for codes that were never sent.
 */
class AliyunSmsSenderTest {

    private final RecordingGateway gateway = new RecordingGateway();

    @Test
    void sendsTheTemplateParametersATemplateExpects() {
        new AliyunSmsSender(gateway, "SkillMaster", "SMS_123456").send("13800138000", "042913");

        assertThat(gateway.calls).containsExactly(
                new Sent("13800138000", "SkillMaster", "SMS_123456", "{\"code\":\"042913\"}"));
    }

    @Test
    void aMessageTheProviderRefusedIsNotReportedAsSent() {
        gateway.verdict = SmsGateway.Receipt.refused("isv.SMS_SIGNATURE_ILLEGAL: 签名未通过审核");

        assertThatThrownBy(() -> new AliyunSmsSender(gateway, "SkillMaster", "SMS_123456")
                .send("13800138000", "042913"))
                // The provider's own words, because "the send failed" is not actionable: the operator
                // has to be able to tell an unapproved signature from a number that does not exist.
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("isv.SMS_SIGNATURE_ILLEGAL");
    }

    @Test
    void anAcceptedMessageQuietlySucceeds() {
        gateway.verdict = SmsGateway.Receipt.ok();

        new AliyunSmsSender(gateway, "SkillMaster", "SMS_123456").send("13800138000", "042913");

        assertThat(gateway.calls).hasSize(1);
    }

    private record Sent(String phone, String signName, String templateCode, String templateParam) {
    }

    private static final class RecordingGateway implements SmsGateway {

        private final List<Sent> calls = new ArrayList<>();
        private Receipt verdict = Receipt.ok();

        @Override
        public Receipt send(String phone, String signName, String templateCode, String templateParam) {
            calls.add(new Sent(phone, signName, templateCode, templateParam));
            return verdict;
        }
    }
}
