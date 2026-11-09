package com.skillmasterai.modules.account.internal;

import com.skillmasterai.modules.account.AccountDeletion;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * M1's half of an account deletion (v56, ADR 0175).
 *
 * <p>Only M1's own tables are touched here: {@code app_user}, {@code credential},
 * {@code legal_consent}, and {@code phone_verification} — the last one by the blind index, since it
 * carries no foreign key to the account. Everything else the purge removes — the namespace and its
 * skills, API keys, notifications, service credentials — belongs to other modules and is the use
 * case's to ask for, in this order and this transaction.
 */
public final class AccountDeletionService implements AccountDeletion {

    private final AccountRepository users;
    private final JdbcClient jdbc;

    public AccountDeletionService(AccountRepository users, JdbcClient jdbc) {
        this.users = users;
        this.jdbc = jdbc;
    }

    @Override
    public boolean markDeleted(String userId, String at) {
        return users.markDeleted(userId, at);
    }

    @Override
    public List<String> dueForPurge(String cutoff) {
        return users.dueForPurge(cutoff);
    }

    /**
     * <strong>Read the phone hash before nulling it.</strong> {@code phone_verification} has no
     * foreign key, so once the column is gone there is no other way to find the codes this account
     * asked for — and a residual code row is a phone number left behind.
     */
    @Override
    public boolean claimForPurge(String userId, String at) {
        return users.claimForPurge(userId, at);
    }

    @Override
    public boolean isDeleted(String userId) {
        return users.isDeleted(userId);
    }

    @Override
    public void purgePersonalData(String userId) {
        users.phoneHashOf(userId).ifPresent(phoneHash ->
                jdbc.sql("DELETE FROM phone_verification WHERE phone_hash = :hash")
                        .param("hash", phoneHash)
                        .update());
        jdbc.sql("DELETE FROM credential WHERE user_id = :id").param("id", userId).update();
        jdbc.sql("DELETE FROM legal_consent WHERE user_id = :id").param("id", userId).update();
        // Reserved for SSO, so normally empty — deleted anyway: an external identity is personal
        // data, and the account row survives the purge, so its cascade never fires.
        jdbc.sql("DELETE FROM identity WHERE user_id = :id").param("id", userId).update();
        // `purged_at` was already written by the claim; this only removes what was about a person.
        users.deidentify(userId);
    }
}
