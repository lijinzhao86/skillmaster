package com.skillmasterai.config;

import com.aliyun.dysmsapi20170525.Client;
import com.aliyun.dysmsapi20170525.models.SendSmsRequest;
import com.aliyun.dysmsapi20170525.models.SendSmsResponseBody;
import com.aliyun.teaopenapi.models.Config;
import com.skillmasterai.modules.account.AliyunSmsSender;
import com.skillmasterai.modules.account.LoggingSmsSender;
import com.skillmasterai.modules.account.SmsGateway;
import com.skillmasterai.modules.account.SmsSender;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Chooses the {@link SmsSender} this deployment runs with, and holds the only mention of the Aliyun
 * SDK in this codebase.
 *
 * <p>Three states now, and the third is deliberate rather than a fallback:
 *
 * <ul>
 *   <li><strong>No credentials</strong> — {@link LoggingSmsSender}, which prints the code where a
 *       person can read it or refuses to send, depending on {@code log-codes}. See that class for
 *       why refusing is its default.</li>
 *   <li><strong>Credentials</strong> — the real API.</li>
 *   <li><strong>Credentials without a signature or a template</strong> — a startup failure. Those
 *       two are not optional extras: Aliyun will refuse every send without them (TD §8, question
 *       12), and a deployment that starts and then fails on every message is discovered by a user
 *       who never receives a code.</li>
 * </ul>
 *
 * <p>The SDK is confined to this class. Nothing in {@code modules} imports it, which is what keeps
 * the module's own tests free of it — and what would make swapping providers a change here and
 * nowhere else.
 */
@Configuration(proxyBeanMethods = false)
class SmsConfig {

    /**
     * The API's address. Not configurable: mainland numbers are the only ones
     * {@code PhoneNumberPolicy} accepts, so the regional endpoints for other areas have no caller.
     */
    private static final String ENDPOINT = "dysmsapi.aliyuncs.com";

    @Bean
    SmsSender smsSender(SkillmasterProperties properties) {
        SkillmasterProperties.Sms sms = properties.sms();
        if (sms.accessKeyId().isBlank()) {
            return new LoggingSmsSender(sms.logCodes());
        }
        requireApprovedTemplate(sms);
        return new AliyunSmsSender(gateway(sms), sms.signName(), sms.templateCode());
    }

    /**
     * The call to Aliyun, in one place.
     *
     * <p>Aliyun answers {@code 200} for a message it refused, with the reason in the body, so the
     * HTTP status is not the answer to "did it go out" — the body's {@code code} is. Reading it here
     * and reporting a verdict upward is what stops a failed send from looking like a delivered one.
     *
     * <p>{@code Exception} is caught rather than declared, because the SDK's generated methods throw
     * it: a network failure and a rejected message end up as the same kind of fact to the caller,
     * and the difference — the provider's own words — is kept in the message.
     */
    private static SmsGateway gateway(SkillmasterProperties.Sms sms) {
        Client client;
        try {
            client = new Client(new Config()
                    .setAccessKeyId(sms.accessKeyId())
                    .setAccessKeySecret(sms.accessKeySecret())
                    .setEndpoint(ENDPOINT));
        } catch (Exception e) {
            // Only reachable if the SDK rejects the configuration itself, which it does before any
            // request is made — so this is a startup failure, not a send-time one.
            throw new IllegalStateException("could not build the Aliyun SMS client", e);
        }

        return (phone, signName, templateCode, templateParam) -> {
            try {
                SendSmsResponseBody body = client.sendSms(new SendSmsRequest()
                                .setPhoneNumbers(phone)
                                .setSignName(signName)
                                .setTemplateCode(templateCode)
                                .setTemplateParam(templateParam))
                        .getBody();
                if (body != null && "OK".equals(body.getCode())) {
                    return SmsGateway.Receipt.ok();
                }
                return SmsGateway.Receipt.refused(body == null
                        ? "the provider returned no response body"
                        : body.getCode() + ": " + body.getMessage());
            } catch (Exception e) {
                return SmsGateway.Receipt.refused("the request failed: " + e.getMessage());
            }
        };
    }

    private static void requireApprovedTemplate(SkillmasterProperties.Sms sms) {
        if (sms.signName().isBlank() || sms.templateCode().isBlank()) {
            throw new IllegalStateException(
                    "skillmaster.sms.access-key-id is set, so this deployment intends to send real "
                            + "messages — but the sign name or the template code is missing. Both "
                            + "have to be approved in the Aliyun console first (TD §8, question 12), "
                            + "and every send is refused without them.");
        }
    }
}
