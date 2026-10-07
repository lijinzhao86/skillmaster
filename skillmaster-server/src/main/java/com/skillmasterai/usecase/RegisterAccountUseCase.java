package com.skillmasterai.usecase;

import com.skillmasterai.modules.account.Account;
import com.skillmasterai.modules.account.AccountRegistrar;
import com.skillmasterai.modules.account.AccountRequestException;
import com.skillmasterai.modules.account.PasswordBlocklist;
import com.skillmasterai.modules.account.PasswordPolicy;
import com.skillmasterai.modules.account.PhoneNumberPolicy;
import com.skillmasterai.modules.account.PhoneVerification;
import com.skillmasterai.modules.account.UsernamePolicy;
import com.skillmasterai.modules.account.VerificationCodeException;
import com.skillmasterai.modules.account.VerificationPurpose;
import com.skillmasterai.modules.audit.AuditEvent;
import com.skillmasterai.modules.audit.AuditLog;
import com.skillmasterai.modules.namespace.NamespaceService;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Registers an account: the one use case that spans two modules and therefore the one place the
 * transaction can be drawn (§2.5 rule 2).
 *
 * <p>Order is deliberate. The cheap format checks come first, so a typo comes back as a field error
 * rather than costing the user an SMS. The code is checked before anything is written, so a
 * registration refused afterwards — for a username somebody else has, say — rolls back and leaves
 * the code usable. Making somebody ask for a fresh message because their username was taken is a
 * cost with nothing behind it.
 *
 * <p><strong>{@link VerificationCodeException} is the exception to that rollback, and it has to
 * be.</strong> A refused code is a guess, and the count of guesses is the only thing that makes six
 * digits hard to enumerate: six digits are a few thousand requests, and the lifetime alone does not
 * bound that. Rolling the count back would leave the code guessable for as long as it is valid,
 * which is the vulnerability, not a nuance. The commit that does this is harmless only because the
 * check is ahead of every write in this method — an invariant the integration test
 * {@code WebRegistrationIT} pins.
 */
@Component
public class RegisterAccountUseCase {

    private final PhoneVerification codes;
    private final AccountRegistrar accounts;
    private final NamespaceService namespaces;
    private final AuditLog audit;
    private final PasswordBlocklist blocklist;

    public RegisterAccountUseCase(PhoneVerification codes, AccountRegistrar accounts,
            NamespaceService namespaces, AuditLog audit, PasswordBlocklist blocklist) {
        this.codes = codes;
        this.accounts = accounts;
        this.namespaces = namespaces;
        this.audit = audit;
        this.blocklist = blocklist;
    }

    /**
     * @throws AccountRequestException when a field is malformed or already taken
     * @throws com.skillmasterai.modules.account.VerificationCodeException when the code is not valid
     */
    @Transactional(noRollbackFor = VerificationCodeException.class)
    public Account register(Request request) {
        PhoneNumberPolicy.problemWith(request.phone()).ifPresent(issue -> {
            throw new AccountRequestException("phone", issue);
        });
        UsernamePolicy.problemWith(request.username()).ifPresent(issue -> {
            throw new AccountRequestException("username", issue);
        });
        PasswordPolicy.problemWith(request.password(), request.username(), request.phone(), blocklist)
                .ifPresent(issue -> {
                    throw new AccountRequestException("password", issue);
                });

        codes.consume(request.phone(), VerificationPurpose.REGISTER, request.code());

        Account account = accounts.register(request.username(), request.phone(), request.password());
        if (!namespaces.createPersonalNamespace(account.userId(), account.handle())) {
            // The slug is the handle, so this is the username being taken — seen from the other
            // table, in the window where two registrations raced past the check inside M1.
            throw new AccountRequestException("username", "already_taken");
        }

        // In this transaction on purpose: an audit row that survives the rollback of the change it
        // describes is worse than none, because it reads as evidence.
        audit.record(new AuditEvent(account.userId(), "register", "user", account.userId(),
                Map.of("handle", account.handle())));

        return account;
    }

    /**
     * @param code the SMS code: spent by a call that succeeds, and left usable by one refused after
     *             it was checked — see the class note on why that is the right way round
     */
    public record Request(String phone, String code, String password, String username) {
    }
}
