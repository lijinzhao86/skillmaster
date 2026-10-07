package com.skillmasterai.support;

import com.skillmasterai.modules.account.SmsSender;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * The SMS provider, for tests that have to get past a code.
 *
 * <p>It does the one thing {@code LoggingSmsSender} cannot: it keeps the code. What reaches the
 * database is a hash of the code and nothing else — by design — so a test that could not read the
 * code back from here could not complete any flow past "request a code", which is every flow worth
 * testing.
 *
 * <p><strong>{@code @Primary} rather than replacing the production bean.</strong> Overriding a bean
 * definition needs {@code spring.main.allow-bean-definition-overriding=true}, which would be a
 * property set for the whole test suite so that one class could win a name collision. Being
 * pickable by type is the mechanism the framework has for this, and it applies to every context in
 * the suite because this class sits in the scanned package — the alternative, a separate profile
 * with its own {@code SmsConfig}, would test a wiring no deployment ever has.
 */
@Component
@Primary
public class RecordingSmsSender implements SmsSender {

    private final Map<String, String> codesByPhone = new ConcurrentHashMap<>();

    @Override
    public void send(String phone, String code) {
        codesByPhone.put(phone, code);
    }

    /** @return the most recent code sent to this number, or null when none was sent */
    public String lastCode(String phone) {
        return codesByPhone.get(phone);
    }
}
