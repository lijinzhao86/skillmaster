package com.skillmasterai.modules.secret.internal;

import com.skillmasterai.common.ConflictException;
import com.skillmasterai.common.ErrorCode;
import com.skillmasterai.common.Ulid;
import com.skillmasterai.modules.secret.BindingConflictException;
import com.skillmasterai.modules.secret.CredentialBinding;
import com.skillmasterai.modules.secret.CredentialLease;
import com.skillmasterai.modules.secret.CredentialRef;
import com.skillmasterai.modules.secret.CredentialValue;
import com.skillmasterai.modules.secret.IncomingBinding;
import com.skillmasterai.modules.secret.QuotaExceededException;
import com.skillmasterai.modules.secret.SecretNotFoundException;
import com.skillmasterai.modules.secret.SecretService;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;

/**
 * M16's implementation: BASE64 storage, one sealer call per write and per leased credential, and
 * the quota statement.
 *
 * <p>The one thing this class deliberately does not do is decide whether the caller may act. That
 * needs the subject and M12's access answer, both of which live in the use cases; by the time a
 * method here runs, the row has already been checked to be the caller's.
 */
public final class PgSecretService implements SecretService {

    /** The design's default; {@code service_credential.per_day_limit} carries the same value. */
    public static final int DEFAULT_PER_DAY = 500;

    public static final int MIN_PER_DAY = 1;
    public static final int MAX_PER_DAY = 10_000;

    private final SecretRepository repository;
    private final Sealer sealer;

    public PgSecretService(SecretRepository repository, Sealer sealer) {
        this.repository = repository;
        this.sealer = sealer;
    }

    @Override
    public CredentialRef create(String ownerUserId, String name, CredentialValue value, String at) {
        String id = Ulid.generate();
        byte[] sealed = sealer.encrypt(value.bytes());
        try {
            repository.insertCredential(id, ownerUserId, name,
                    Base64.getEncoder().encodeToString(sealed), value.byteLength(),
                    DEFAULT_PER_DAY, at);
        } catch (DuplicateKeyException e) {
            throw new ConflictException(ErrorCode.CREDENTIAL_NAME_CONFLICT,
                    "a credential named '" + name + "' already exists; pick another name");
        }
        return ref(repository.find(id).orElseThrow(SecretNotFoundException::new));
    }

    @Override
    public List<CredentialRef> list(String ownerUserId, String skillIdFilter) {
        return repository.listByOwner(ownerUserId, skillIdFilter).stream()
                .map(PgSecretService::ref)
                .toList();
    }

    @Override
    public Optional<CredentialRef> find(String credentialId) {
        return repository.find(credentialId).map(PgSecretService::ref);
    }

    @Override
    public CredentialRef rotate(String credentialId, CredentialValue value, String at) {
        byte[] sealed = sealer.encrypt(value.bytes());
        if (repository.updateValue(credentialId,
                Base64.getEncoder().encodeToString(sealed), value.byteLength(), at) == 0) {
            throw new SecretNotFoundException();
        }
        return ref(repository.find(credentialId).orElseThrow(SecretNotFoundException::new));
    }

    @Override
    public void delete(String credentialId) {
        repository.delete(credentialId);
    }

    @Override
    public CredentialRef setPerDayLimit(String credentialId, int perDayLimit) {
        if (perDayLimit < MIN_PER_DAY || perDayLimit > MAX_PER_DAY) {
            throw new ConflictException(ErrorCode.INVALID_REQUEST,
                    "per_day must be between " + MIN_PER_DAY + " and " + MAX_PER_DAY);
        }
        if (repository.updateLimit(credentialId, perDayLimit) == 0) {
            throw new SecretNotFoundException();
        }
        return ref(repository.find(credentialId).orElseThrow(SecretNotFoundException::new));
    }

    @Override
    public CredentialBinding bind(String credentialId, String skillId, String envName, String at) {
        SecretRepository.StoredCredential credential = repository.find(credentialId)
                .orElseThrow(SecretNotFoundException::new);
        String id = Ulid.generate();
        try {
            repository.insertBinding(id, credentialId, credential.ownerUserId(), skillId, envName,
                    at);
        } catch (DuplicateKeyException e) {
            // The owner column makes this the real rule, not just the service-layer check: two
            // concurrent binds of two different credentials to one (skill, variable) cannot both
            // land, and the loser is told which rule it hit.
            throw new BindingConflictException(
                    "this account already has a credential bound to that skill and variable");
        }
        return new CredentialBinding(id, credentialId, skillId, envName, at);
    }

    @Override
    public boolean unbind(String credentialId, String skillId, String envName) {
        return repository.deleteBinding(credentialId, skillId, envName) > 0;
    }

    @Override
    public List<CredentialBinding> bindings(String credentialId) {
        return repository.bindingsOf(credentialId).stream()
                .map(PgSecretService::binding)
                .toList();
    }

    @Override
    public boolean hasOwnBinding(String ownerUserId, String skillId, String envName) {
        return repository.hasOwnBinding(ownerUserId, skillId, envName);
    }

    @Override
    public List<CredentialBinding.Share> shares(String credentialId, String skillIdFilter) {
        return repository.shares(credentialId, skillIdFilter).stream()
                .map(row -> new CredentialBinding.Share(row.granteeUserId(), row.skillId(),
                        row.envName(), row.createdAt()))
                .toList();
    }

    @Override
    public List<CredentialBinding.Share> share(String credentialId, String skillId,
            String granteeUserId, String grantedBy, String at) {
        List<SecretRepository.StoredBinding> bindings =
                repository.bindingsOfSkill(credentialId, skillId);
        if (bindings.isEmpty()) {
            throw new SecretNotFoundException(
                    "this credential is not bound to that skill, so there is nothing to share");
        }
        if (repository.activeShareExists(credentialId, skillId, granteeUserId)) {
            throw BindingConflictException.share(
                    "this credential is already shared with that person on this skill");
        }
        try {
            repository.insertShare(Ulid.generate(), credentialId, skillId, granteeUserId, grantedBy,
                    at);
        } catch (DuplicateKeyException e) {
            throw BindingConflictException.share(
                    "this credential is already shared with that person on this skill");
        }
        // The grant is one row; what it hands over is every variable this credential has here,
        // including the ones bound after today.
        return bindings.stream()
                .map(binding -> new CredentialBinding.Share(granteeUserId, skillId,
                        binding.envName(), at))
                .toList();
    }

    @Override
    public boolean unshare(String credentialId, String skillId, String granteeUserId, String at) {
        return repository.revokeShares(credentialId, skillId, granteeUserId, at) > 0;
    }

    @Override
    public List<IncomingBinding> incoming(String userId, String skillId, String day) {
        return repository.incoming(userId, skillId, day).stream()
                .map(row -> new IncomingBinding(row.credentialId(), row.name(), row.ownerUserId(),
                        row.skillId(), row.envName(), row.perDay(), row.used()))
                .toList();
    }

    @Override
    public java.util.Set<String> boundEnvNames(String userId, String skillId,
            boolean sharesAreValid) {
        return java.util.Set.copyOf(repository.boundEnvNames(userId, skillId, sharesAreValid));
    }

    @Override
    public int usedToday(String credentialId, String day) {
        return repository.usedToday(credentialId, day);
    }

    @Override
    public CredentialLease lease(String userId, String skillId, String day, String at,
            String expiresAt, boolean sharesAreValid) {
        // Own bindings first, then shared ones only where the same variable is not already served.
        Map<String, SecretRepository.StoredBinding> effective = new LinkedHashMap<>();
        for (SecretRepository.StoredBinding binding : repository.ownBindings(userId, skillId)) {
            effective.putIfAbsent(binding.envName(), binding);
        }
        if (sharesAreValid) {
            for (SecretRepository.StoredBinding binding
                    : repository.sharedBindings(userId, skillId)) {
                effective.putIfAbsent(binding.envName(), binding);
            }
        }
        if (effective.isEmpty()) {
            return CredentialLease.empty(expiresAt);
        }

        // One credential may serve several variables; it is charged once and decrypted once.
        LinkedHashSet<String> credentialIds = new LinkedHashSet<>();
        for (SecretRepository.StoredBinding binding : effective.values()) {
            credentialIds.add(binding.credentialId());
        }
        Map<String, String> plaintexts = new LinkedHashMap<>();
        for (String credentialId : credentialIds) {
            SecretRepository.StoredCredential credential = repository.find(credentialId)
                    .orElseThrow(SecretNotFoundException::new);
            if (repository.incrementUse(credentialId, day, at).isEmpty()) {
                throw new QuotaExceededException(credential.id(), credential.name(),
                        credential.perDayLimit());
            }
            repository.touchLastUsed(credentialId, at);
            plaintexts.put(credentialId, new String(
                    sealer.decrypt(Base64.getDecoder().decode(credential.ciphertext())),
                    StandardCharsets.UTF_8));
        }

        List<CredentialLease.Value> values = effective.values().stream()
                .map(binding -> new CredentialLease.Value(binding.envName(),
                        plaintexts.get(binding.credentialId())))
                .toList();
        return new CredentialLease(values, expiresAt);
    }

    private static CredentialRef ref(SecretRepository.StoredCredential row) {
        return new CredentialRef(row.id(), row.ownerUserId(), row.name(), row.perDayLimit(),
                row.createdAt(), row.rotatedAt(), row.lastUsedAt());
    }

    private static CredentialBinding binding(SecretRepository.StoredBinding row) {
        return new CredentialBinding(row.id(), row.credentialId(), row.skillId(), row.envName(),
                row.createdAt());
    }
}
