package com.skillmasterai.modules.account.internal;

import com.skillmasterai.modules.account.Account;
import com.skillmasterai.modules.account.AccountRegistrar;
import com.skillmasterai.modules.account.AccountRequestException;
import com.skillmasterai.modules.account.PasswordBlocklist;
import com.skillmasterai.modules.account.PasswordHasher;
import com.skillmasterai.modules.account.PasswordPolicy;
import com.skillmasterai.modules.account.PhoneCipher;
import com.skillmasterai.modules.account.UsernamePolicy;
import java.util.Optional;

/**
 * M1's implementation of accounts and passwords.
 *
 * <p>The policies are enforced here rather than at the call site so that no path can store a
 * password or a username that breaks them. A caller that forgot would otherwise write a row the
 * rest of the system assumes cannot exist.
 */
public final class AccountService implements AccountRegistrar {

    private static final String ACTIVE = "active";

    private final AccountRepository users;
    private final CredentialRepository credentials;
    private final PhoneCipher cipher;
    private final PasswordHasher hasher;
    private final PasswordBlocklist blocklist;

    public AccountService(AccountRepository users, CredentialRepository credentials,
            PhoneCipher cipher, PasswordHasher hasher, PasswordBlocklist blocklist) {
        this.users = users;
        this.credentials = credentials;
        this.cipher = cipher;
        this.hasher = hasher;
        this.blocklist = blocklist;
    }

    @Override
    public Account register(String handle, String phone, String rawPassword) {
        UsernamePolicy.problemWith(handle)
                .ifPresent(issue -> {
                    throw new AccountRequestException("username", issue);
                });
        PasswordPolicy.problemWith(rawPassword, handle, phone, blocklist)
                .ifPresent(issue -> {
                    throw new AccountRequestException("password", issue);
                });

        // A courtesy check, so an obviously taken name is refused before anything is encrypted or
        // hashed. It is not the guard — the guard is the UNIQUE constraint, because two people can
        // pass this check at the same moment.
        if (users.handleExists(handle)) {
            throw new AccountRequestException("username", "already_taken");
        }
        String phoneHash = cipher.hash(phone);
        if (users.findByPhoneHash(phoneHash).isPresent()) {
            throw new AccountRequestException("phone", "already_registered");
        }

        String userId = users.insert(handle, phoneHash, cipher.encrypt(phone), "")
                .orElseThrow(() -> whichColumnCollided(handle, phoneHash));
        credentials.putPassword(userId, hasher.hash(rawPassword));
        return new Account(userId, handle);
    }

    @Override
    public Optional<Account> authenticate(String phone, String rawPassword) {
        Optional<AccountRow> found = users.findByPhoneHash(cipher.hash(phone));
        if (found.isEmpty()) {
            // Spend the same work a real comparison would. Without this, the endpoint answers
            // faster for phone numbers nobody registered, and that timing is an account-existence
            // oracle no amount of identical response bodies hides.
            hasher.spendComparison(rawPassword);
            return Optional.empty();
        }

        AccountRow user = found.get();
        Optional<String> stored = credentials.passwordHash(user.id());
        if (stored.isEmpty() || !hasher.matches(rawPassword, stored.get())) {
            return Optional.empty();
        }
        // Checked after the password, so that a suspended account answers in the same time and the
        // same way as a wrong password. All three give the caller nothing to act on.
        return ACTIVE.equals(user.status())
                ? Optional.of(new Account(user.id(), user.handle()))
                : Optional.empty();
    }

    @Override
    public Optional<Account> resetPassword(String phone, String rawPassword) {
        Optional<AccountRow> found = users.findByPhoneHash(cipher.hash(phone));
        if (found.isEmpty()) {
            return Optional.empty();
        }
        AccountRow user = found.get();
        PasswordPolicy.problemWith(rawPassword, user.handle(), phone, blocklist)
                .ifPresent(issue -> {
                    throw new AccountRequestException("password", issue);
                });
        credentials.putPassword(user.id(), hasher.hash(rawPassword));
        return Optional.of(new Account(user.id(), user.handle()));
    }

    /**
     * Which UNIQUE constraint stopped the insert.
     *
     * <p>Reached only after absorbing a conflict, never after an exception: a raised constraint
     * violation aborts the transaction, and these two queries would then fail with "current
     * transaction is aborted" instead of answering.
     *
     * <p>Looking rather than assuming, because the column the client has to change is the entire
     * value of this error — "try another username" is useless advice to somebody whose phone number
     * is already registered.
     */
    private AccountRequestException whichColumnCollided(String handle, String phoneHash) {
        if (users.handleExists(handle)) {
            return new AccountRequestException("username", "already_taken");
        }
        if (users.findByPhoneHash(phoneHash).isPresent()) {
            return new AccountRequestException("phone", "already_registered");
        }
        // Neither is there now, so the other transaction rolled back after all. Report the
        // username: the client retries and gets a different answer rather than a stuck one.
        return new AccountRequestException("username", "already_taken");
    }
}
