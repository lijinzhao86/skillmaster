package com.skillmasterai.modules.account;

/**
 * Verification codes through Aliyun's SMS API.
 *
 * <p>Holds no SDK types: the request is built here, and the call itself is {@link SmsGateway}'s. That
 * split is what lets the two things worth testing be tested — that the template parameter is shaped
 * the way a template expects, and that <strong>a message the provider refused is never reported as
 * sent</strong>. The second one is the reason this class exists rather than a lambda in the wiring:
 * an SMS API answers HTTP 200 with a failure in the body, so "the call returned" and "the message
 * went out" are different facts, and only one of them is worth believing.
 */
public final class AliyunSmsSender implements SmsSender {

    private static final String CODE_PARAMETER = "code";

    private final SmsGateway gateway;
    private final String signName;
    private final String templateCode;

    public AliyunSmsSender(SmsGateway gateway, String signName, String templateCode) {
        this.gateway = gateway;
        this.signName = signName;
        this.templateCode = templateCode;
    }

    @Override
    public void send(String phone, String code) {
        SmsGateway.Receipt receipt = gateway.send(phone, signName, templateCode, templateParam(code));
        if (!receipt.accepted()) {
            // Out, not swallowed: the caller turns this into a 500, and a user waiting for a code
            // that was never accepted is worse off than one who is told to try again.
            throw new IllegalStateException("the SMS provider refused the message: " + receipt.detail());
        }
    }

    /**
     * The template's variables, as the JSON object a template expects.
     *
     * <p>Built by hand rather than through a serializer, which is safe here for a reason worth
     * stating: the only caller is {@code PhoneVerificationService}, whose six digits are produced by
     * {@code String.format("%06d", …)}, and {@link SmsSender#send} says the argument is those digits.
     * A caller that passed something else would produce a request Aliyun refuses — a visible failure,
     * not a silent one.
     */
    private static String templateParam(String code) {
        return "{\"" + CODE_PARAMETER + "\":\"" + code + "\"}";
    }
}
