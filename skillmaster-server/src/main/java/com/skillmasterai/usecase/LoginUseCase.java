package com.skillmasterai.usecase;

import com.skillmasterai.modules.account.Account;
import com.skillmasterai.modules.account.AccountRegistrar;
import com.skillmasterai.modules.account.AccountRequestException;
import com.skillmasterai.modules.account.AuthThrottle;
import com.skillmasterai.modules.account.PhoneNumberPolicy;
import com.skillmasterai.modules.account.Throttle;
import com.skillmasterai.modules.account.ThrottledException;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Checks a password and says who the caller is.
 *
 * <p>Not read-only, although it reads: it writes the attempt counter, and a counter that is not
 * written is not a limit.
 *
 * <p><strong>The phone's budget is spent before the password is checked.</strong> The alternative —
 * count only failures — means every guess costs the attacker one password comparison and the limiter
 * notices afterwards, which is the wrong way round: the point is to stop the guessing, not to
 * describe it. The cost of this order is that someone who knows a phone number can lock its owner
 * out for the length of the window, which is why the window is short and the cap is small. A
 * successful sign-in clears it, so what accumulates is consecutive attempts.
 *
 * <p><strong>The caller's address is spent the other way round: only by a failure.</strong> An
 * address is not a person — a whole office or a carrier's NAT arrives from one — so a budget every
 * attempt spent would end up refusing somebody whose password was right. Spending it on failure is
 * also what makes it un-resettable: an attacker who owns one account must not be able to clear the
 * budget he is spending on everybody else's.
 *
 * <p><strong>A refusal commits the counts it already made, and that is not a detail.</strong> Both
 * rules are checked in this one transaction, and the address refusal comes after the password has
 * been compared — so rolling the transaction back there would undo the phone's count as well. The
 * phone's counter would then never advance past whatever it was when the address ran out, its own
 * cap would never be reached, and this endpoint would go on comparing passwords for one account
 * without limit. Committing leaves the counters one over a cap that has already refused them, which
 * costs nothing: {@code attempts > cap} stays true.
 */
@Component
public class LoginUseCase {

    private final AccountRegistrar accounts;
    private final AuthThrottle throttle;

    public LoginUseCase(AccountRegistrar accounts, AuthThrottle throttle) {
        this.accounts = accounts;
        this.throttle = throttle;
    }

    /**
     * @return the account, or empty for every way this can fail — no such phone, wrong password,
     *         suspended. One answer for all three, so the endpoint cannot be used to enumerate
     *         accounts; see {@link com.skillmasterai.common.ErrorCode#INVALID_CREDENTIALS}
     * @throws AccountRequestException when the number is not one anybody could have registered
     * @throws ThrottledException when the caller has spent its budget for this window
     */
    @Transactional(noRollbackFor = ThrottledException.class)
    public Optional<Account> login(String phone, String rawPassword, String clientIp) {
        PhoneNumberPolicy.problemWith(phone).ifPresent(issue -> {
            throw new AccountRequestException("phone", issue);
        });
        if (throttle.countLoginAttempt(phone) instanceof Throttle.Refused refused) {
            throw new ThrottledException(refused.retryAfterSeconds());
        }
        Optional<Account> account = accounts.authenticate(phone, rawPassword);
        if (account.isPresent()) {
            // Cleared only on success: this is what stops a person who mistyped twice and then got
            // it right from carrying two failures into tomorrow.
            throttle.clearLoginFailures(phone);
            return account;
        }
        if (throttle.recordLoginFailure(clientIp) instanceof Throttle.Refused refused) {
            // Refused for the address rather than for the phone. It only looks like a worse answer
            // than the usual 401 for this one caller — and it is the only thing that ends the
            // guessing of somebody working through a list of numbers.
            throw new ThrottledException(refused.retryAfterSeconds());
        }
        return Optional.empty();
    }
}
