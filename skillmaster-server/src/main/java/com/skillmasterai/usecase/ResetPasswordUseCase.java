package com.skillmasterai.usecase;

import com.skillmasterai.modules.account.Account;
import com.skillmasterai.modules.account.AccountRegistrar;
import com.skillmasterai.modules.account.AccountRequestException;
import com.skillmasterai.modules.account.PhoneNumberPolicy;
import com.skillmasterai.modules.account.PhoneVerification;
import com.skillmasterai.modules.account.VerificationCodeException;
import com.skillmasterai.modules.account.VerificationPurpose;
import com.skillmasterai.modules.account.WebSessionRegistry;
import com.skillmasterai.modules.audit.AuditEvent;
import com.skillmasterai.modules.audit.AuditLog;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Replaces a password for somebody who has proved they hold the phone.
 *
 * <p>{@link VerificationCodeException} is declared as the one exception that does not roll this
 * back, for the reason {@code RegisterAccountUseCase} gives at length: a refused guess has to leave
 * its count behind, or six digits are a few thousand requests away from any account.
 *
 * <p><strong>Ending every session is not a finishing touch, it is half of what "reset" means.</strong>
 * A password change that leaves the old sessions alive leaves whoever had the old password logged
 * in, which is the situation the person resetting is usually trying to get out of. When M2 exists
 * this use case grows a second call on the same seam — tokens — and that is why the revocation is
 * here rather than inside M1.
 */
@Component
public class ResetPasswordUseCase {

    private final PhoneVerification codes;
    private final AccountRegistrar accounts;
    private final WebSessionRegistry sessions;
    private final AuditLog audit;

    public ResetPasswordUseCase(PhoneVerification codes, AccountRegistrar accounts,
            WebSessionRegistry sessions, AuditLog audit) {
        this.codes = codes;
        this.accounts = accounts;
        this.sessions = sessions;
        this.audit = audit;
    }

    /**
     * @throws AccountRequestException when a field is malformed, or when no account owns the number
     * @throws com.skillmasterai.modules.account.VerificationCodeException when the code is not valid
     */
    @Transactional(noRollbackFor = VerificationCodeException.class)
    public void reset(Request request) {
        PhoneNumberPolicy.problemWith(request.phone()).ifPresent(issue -> {
            throw new AccountRequestException("phone", issue);
        });
        codes.consume(request.phone(), VerificationPurpose.RESET, request.code());

        Account account = accounts.resetPassword(request.phone(), request.password())
                // Reachable only by somebody holding the phone a code was just delivered to, so
                // saying "no account here" tells them nothing they did not already control. The
                // alternative — answering as though it worked — sends them to a login that fails
                // for a reason nothing explained.
                .orElseThrow(() -> new AccountRequestException("phone", "no_account"));

        sessions.revokeAllFor(account.userId());
        audit.record(new AuditEvent(account.userId(), "password_reset", "user", account.userId(),
                Map.of()));
    }

    public record Request(String phone, String code, String password) {
    }
}
