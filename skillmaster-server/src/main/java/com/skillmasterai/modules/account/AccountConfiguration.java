package com.skillmasterai.modules.account;

import com.skillmasterai.modules.account.internal.AccountRepository;
import com.skillmasterai.modules.account.internal.AccountService;
import com.skillmasterai.modules.account.internal.CaptchaRepository;
import com.skillmasterai.modules.account.internal.CaptchaService;
import com.skillmasterai.modules.account.internal.CredentialRepository;
import com.skillmasterai.modules.account.internal.PgAuthThrottle;
import com.skillmasterai.modules.account.internal.PhoneVerificationRepository;
import com.skillmasterai.modules.account.internal.PhoneVerificationService;
import com.skillmasterai.modules.account.internal.SpringSessionRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;

/**
 * Wires M1.
 *
 * <p>It exposes the module's <em>interfaces</em> and never the repositories behind them, so a
 * caller cannot acquire a concrete type and reach past the seam — the beans declared here are
 * package-private types from {@code internal}, and only the interfaces are published. What keeps a
 * caller from reaching past them is not javac: those classes and their methods are public, and the
 * thing that stops another module using them is {@code ArchitectureTest}.
 *
 * <p>The two cryptographic primitives and the SMS sender arrive as parameters rather than being
 * built here, because they come from configuration and a module may not read it — see
 * {@code config.AccountSecurityConfig} and {@code config.SmsConfig}.
 */
@Configuration(proxyBeanMethods = false)
public class AccountConfiguration {

    private static final String BLOCKLIST_RESOURCE = "account/password-blocklist.txt";

    /**
     * What "plausibly the real list" means: the 10001 entries the vendored file has.
     *
     * <p>It was 1000, which is the wrong number for the guard to be worth anything — a file
     * truncated to a tenth of its length would have started normally and silently dropped nine
     * thousand of them. Exact rather than approximate because this is a vendored data file, not a
     * feed: the number changes only when somebody deliberately replaces it, and that change should
     * have to be made here too. {@code PasswordBlocklistTest} asserts the file's checksum, so the
     * two together mean a different list cannot arrive unnoticed.
     */
    private static final int MINIMUM_BLOCKLIST_ENTRIES = 10_001;

    /**
     * The one bean of M1 that leaves the module — M4 injects it as {@link AccountDirectory}, which
     * is the interface {@link AccountRepository} implements. There is deliberately no second bean
     * declared as {@code AccountDirectory}: two beans assignable to that type is an ambiguity, not
     * a seam.
     */
    @Bean
    AccountRepository accountRepository(JdbcClient jdbc) {
        return new AccountRepository(jdbc);
    }

    @Bean
    CredentialRepository credentialRepository(JdbcClient jdbc) {
        return new CredentialRepository(jdbc);
    }

    @Bean
    PhoneVerificationRepository phoneVerificationRepository(JdbcClient jdbc) {
        return new PhoneVerificationRepository(jdbc);
    }

    @Bean
    AccountRegistrar accountRegistrar(AccountRepository users, CredentialRepository credentials,
            PhoneCipher cipher, PasswordHasher hasher, PasswordBlocklist blocklist) {
        return new AccountService(users, credentials, cipher, hasher, blocklist);
    }

    /**
     * The common-password list, loaded once at startup.
     *
     * <p>Built here rather than from configuration for the reason the captcha renderer is: which
     * passwords are refused is the shape of the defence, not a deployment's business — and a
     * deployment that could empty this list is a deployment whose blocklist is off, silently.
     *
     * <p>Loading it here is also what makes a missing or truncated file a startup failure rather
     * than a 500 on somebody's first registration.
     */
    @Bean
    PasswordBlocklist passwordBlocklist() {
        return PasswordBlocklist.fromClasspath(BLOCKLIST_RESOURCE, MINIMUM_BLOCKLIST_ENTRIES);
    }

    @Bean
    AuthThrottle authThrottle(JdbcClient jdbc, PhoneCipher cipher) {
        return new PgAuthThrottle(jdbc, cipher);
    }

    @Bean
    PhoneVerification phoneVerification(PhoneVerificationRepository codes, SmsSender sms,
            AuthThrottle throttle, PhoneCipher cipher, CodeComparison comparison) {
        return new PhoneVerificationService(codes, sms, throttle, cipher, comparison);
    }

    @Bean
    WebSessionRegistry webSessionRegistry(
            FindByIndexNameSessionRepository<? extends Session> sessions) {
        return new SpringSessionRegistry(sessions);
    }

    /**
     * The captcha image. Built here rather than from configuration because nothing about it is a
     * deployment's business — the size, the alphabet and the number of attempts are the shape of the
     * defence, like the throttle rules.
     */
    @Bean
    CaptchaRenderer captchaRenderer() {
        return new HutoolCaptchaRenderer();
    }

    @Bean
    CaptchaRepository captchaRepository(JdbcClient jdbc) {
        return new CaptchaRepository(jdbc);
    }

    @Bean
    CaptchaChallenge captchaChallenge(CaptchaRepository captchaRepository,
            CaptchaRenderer captchaRenderer, AuthThrottle throttle) {
        return new CaptchaService(captchaRepository, captchaRenderer, throttle);
    }
}
