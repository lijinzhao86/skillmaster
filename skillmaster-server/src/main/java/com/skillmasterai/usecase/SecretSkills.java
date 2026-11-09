package com.skillmasterai.usecase;

import com.skillmasterai.common.ErrorCode;
import com.skillmasterai.common.RequestException;
import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.modules.namespace.NamespaceService;
import com.skillmasterai.modules.secret.BindingConflictException;
import com.skillmasterai.modules.secret.CredentialRef;
import com.skillmasterai.modules.secret.SecretNotFoundException;
import com.skillmasterai.modules.secret.SecretService;
import com.skillmasterai.modules.skill.Access;
import com.skillmasterai.modules.skill.Caller;
import com.skillmasterai.modules.skill.GrantRole;
import com.skillmasterai.modules.skill.SkillAddress;
import com.skillmasterai.modules.skill.SkillMetadataService;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The meeting point between M4/M12 and M16: an address becomes a {@code skillId} only after the
 * caller has been shown to be able to invoke it.
 *
 * <p>Every credential operation that names a skill goes through here. It is deliberately in the
 * use-case layer — the same arrangement {@link Callers} has — because resolving an address needs M4
 * and M12 while the credential rules need M16, and none of those modules should learn about the
 * others.
 */
final class SecretSkills {

    /** The shape a POSIX environment variable name has to have. */
    private static final Pattern ENV_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private SecretSkills() {
    }

    /**
     * An environment variable name, checked on the server as well as in the CLI.
     *
     * <p>The CLI is not the only client: the console and anything holding an API key reach the same
     * endpoints. A name outside this shape would be stored happily and then fail at lease time,
     * turning one bad write into a run failure far from its cause.
     */
    static void requireEnvName(String env) {
        if (env == null || !ENV_NAME.matcher(env).matches()) {
            throw new RequestException(ErrorCode.INVALID_REQUEST,
                    "env must be a valid environment variable name"
                            + " (letters, digits and underscores, not starting with a digit)");
        }
    }

    /** An empty value is indistinguishable from "not set", so storing one only creates confusion. */
    static void requireNonEmptyValue(String value) {
        if (value == null || value.isEmpty()) {
            throw new RequestException(ErrorCode.INVALID_REQUEST, "value must not be empty");
        }
    }

    /** A resolved, invocable skill. {@code access} is the answer the credential write is rooted in. */
    record Resolved(String namespaceSlug, String namespaceId, String name, String skillId,
            Access access) {
    }

    /**
     * {@code namespace/name}, the address the secret APIs accept for their {@code skill} field.
     *
     * @return empty when the namespace does not exist or the caller cannot invoke the skill — both
     *         are the ordinary 404 and the controller decides that; a malformed address is a 400
     */
    static Optional<Resolved> byAddress(String address, AuthenticatedSubject subject,
            NamespaceService namespaces, SkillMetadataService metadata) {
        if (address == null) {
            throw new RequestException(ErrorCode.INVALID_REQUEST,
                    "skill must be an address like namespace/name");
        }
        int slash = address.indexOf('/');
        if (slash <= 0 || slash == address.length() - 1 || address.indexOf('/', slash + 1) >= 0) {
            throw new RequestException(ErrorCode.INVALID_REQUEST,
                    "skill must be an address like namespace/name");
        }
        return bySlugAndName(address.substring(0, slash), address.substring(slash + 1),
                subject, namespaces, metadata);
    }

    /** The lease route's separate path variables. */
    static Optional<Resolved> bySlugAndName(String namespaceSlug, String name,
            AuthenticatedSubject subject, NamespaceService namespaces,
            SkillMetadataService metadata) {
        return namespaces.bySlug(namespaceSlug)
                .flatMap(namespace -> {
                    Caller caller = new Caller(subject.userId(),
                            namespaces.personalNamespaceOf(subject.userId()).id());
                    return metadata.accessFor(namespace.id(), name, caller)
                            .filter(access -> access.atLeast(GrantRole.INVOKE))
                            .map(access -> new Resolved(namespaceSlug, namespace.id(), name,
                                    access.skillId(), access));
                });
    }

    /**
     * The credential, or the one 404: "no such credential" and "not yours" are the same answer.
     */
    static CredentialRef owned(SecretService secrets, String credentialId, String userId) {
        return secrets.find(credentialId)
                .filter(ref -> ref.ownerUserId().equals(userId))
                .orElseThrow(SecretNotFoundException::new);
    }

    /**
     * One owner may expose at most one credential through one {@code (skill, env)} — the database
     * encodes it too ({@code UNIQUE (owner_user_id, skill_id, env_name)}, which covers the case two
     * concurrent binds would race past). This check runs first only so the ordinary path answers
     * with a sentence instead of a constraint violation.
     */
    static void requireNoOwnBinding(boolean exists, String envName) {
        if (exists) {
            throw new BindingConflictException(
                    "this account already has a credential bound to that skill and '" + envName
                            + "'; remove that binding before adding another");
        }
    }

    /**
     * Skill ids to {@code namespace/name}.
     *
     * <p>M12 answers the name and the namespace's id; M4 turns the id into the slug the wire and
     * the CLI use. One query per distinct namespace, not per row, because the number of skills an
     * account has bound credentials to is the number that grows.
     */
    static Map<String, String> addressesOf(Collection<String> skillIds,
            SkillMetadataService metadata, NamespaceService namespaces) {
        if (skillIds.isEmpty()) {
            return Map.of();
        }
        Map<String, SkillAddress> byId = metadata.addressesOf(skillIds);
        Map<String, String> slugs = new HashMap<>();
        Map<String, String> addresses = new HashMap<>();
        for (Map.Entry<String, SkillAddress> entry : byId.entrySet()) {
            SkillAddress address = entry.getValue();
            String slug = slugs.computeIfAbsent(address.namespaceId(),
                    id -> namespaces.byId(id).map(namespace -> namespace.slug()).orElse(id));
            addresses.put(entry.getKey(), slug + "/" + address.name());
        }
        return addresses;
    }
}
