package com.skillmasterai.modules.token.internal;

import com.skillmasterai.common.Sha256Hex;
import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.jackson.SecurityJacksonModules;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import tools.jackson.databind.json.JsonMapper;

/** The row shapes {@link TokenStore} reads and writes, and the translations to and from them. */
final class TokenRowMapper {

    /**
     * The framework's own composition, not a hand-assembled one.
     *
     * <p>{@code attributes} carries live objects, not strings: {@code OAuth2AuthorizationRequest} is
     * what the PKCE check reads back, and the principal is an {@code Authentication}. Serializing them
     * takes the mixins the framework ships, and asking {@code SecurityJacksonModules} for them is how
     * the framework's own JDBC store configures its mapper — so an upgrade that changes the mixins
     * changes both together.
     */
    static final JsonMapper JSON = JsonMapper.builder()
            .addModules(SecurityJacksonModules.getModules(TokenStore.class.getClassLoader()))
            .build();

    private static final String INVALIDATED = OAuth2Authorization.Token.INVALIDATED_METADATA_NAME;

    private TokenRowMapper() {
    }

    /**
     * The attributes as stored, with one substitution the framework's own deserializer requires.
     *
     * <p>The framework writes the authorizing principal in as an {@code Authentication} — and on this
     * deployment that object is M1's {@code WebAuthentication}, which the framework's polymorphic type
     * validator refuses to read back. Refusing is the validator doing its job: it allows Spring
     * Security's own types and nothing else, because deserializing an arbitrary type named by stored
     * JSON is a gadget surface. Widening that allowlist to reach a class of ours would trade a real
     * protection for a convenience.
     *
     * <p>So the value is normalised instead, to the framework's canonical principal,
     * {@code UsernamePasswordAuthenticationToken} — the type form login puts there and the one the
     * framework ships a deserializer for. What the reader needs is the name, which is the userId in
     * both, and nothing else about the object is read.
     *
     * <p>This is the one place attributes are not stored verbatim, and it is not pruning: no key is
     * dropped and no key's meaning changes.
     */
    static Map<String, Object> attributesToStore(OAuth2Authorization authorization) {
        Map<String, Object> attributes = new LinkedHashMap<>(authorization.getAttributes());
        Object principal = attributes.get(Principal.class.getName());
        if (principal instanceof Authentication authentication
                && !(principal instanceof UsernamePasswordAuthenticationToken)) {
            attributes.put(Principal.class.getName(), UsernamePasswordAuthenticationToken.authenticated(
                    authentication.getName(), null, authentication.getAuthorities()));
        }
        return attributes;
    }

    /**
     * The state the framework will look this authorization up by, from wherever it put it.
     *
     * <p>Two places, depending on how far the request has got: the {@code STATE} attribute once the
     * authorization endpoint has handled it, and the authorization request's own state before that.
     * The attribute is what the consent step looks up, so it is preferred — see V7.
     */
    static String stateOf(OAuth2Authorization authorization) {
        Object attribute = authorization.getAttribute(OAuth2ParameterNames.STATE);
        if (attribute != null) {
            return String.valueOf(attribute);
        }
        Object request = authorization.getAttribute(OAuth2AuthorizationRequest.class.getName());
        return request instanceof OAuth2AuthorizationRequest oauthRequest
                ? oauthRequest.getState()
                : null;
    }

    static void invalidated(Map<String, Object> metadata, String revokedOrUsedAt) {
        invalidated(metadata, revokedOrUsedAt != null);
    }

    /**
     * The same, said directly — and the boolean form is the one this store now reaches for first.
     *
     * <p>Its authority is the authorization's own row, not the token's: a token row that escaped the
     * sweep a revocation performs is still a token under an authorization that is over, and the
     * framework has to see it that way or it will happily issue a successor (V9).
     */
    static void invalidated(Map<String, Object> metadata, boolean revoked) {
        if (revoked) {
            metadata.put(INVALIDATED, true);
        }
    }

    /**
     * Puts the presented value back where its hash belongs, and leaves every other value alone.
     *
     * <p>{@code presentedValue} is null on the {@link TokenStore#findById} path, where there is
     * nothing to put back and the hashes stand as they are.
     */
    static String value(String storedHash, String presentedValue, String presentedHash) {
        return storedHash.equals(presentedHash) ? presentedValue : storedHash;
    }

    /**
     * Hashes a value on the way in — unless we stored it ourselves, which is the read-modify-write
     * case.
     *
     * <p>Without this the consent flow corrupts the authorization: it reads with
     * {@link TokenStore#findById}, gets hashes, and saves them back, and a second hash is not a hash
     * of anything. Membership in this authorization's own stored values is the test, and it cannot
     * produce a false positive — a freshly generated token is 96 bytes of base64url, never the 64
     * lowercase hex characters a digest is.
     */
    static String toHash(String value, Set<String> stored) {
        return stored.contains(value)
                ? value
                : Sha256Hex.of(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    static Set<String> scopesOf(String spaceSeparated) {
        return spaceSeparated.isBlank() ? Set.of() : new LinkedHashSet<>(List.of(spaceSeparated.split(" ")));
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> readAttributes(String json) {
        return JSON.readValue(json, Map.class);
    }

    /**
     * The parts of the authorization request the code's own columns record.
     *
     * <p>They are read from {@code attributes} rather than passed in, because that is where the
     * framework put them and the framework is what validates against them. A missing request is an
     * error rather than an empty row: without it PKCE cannot be checked at all, and a code written
     * with empty challenge columns would look like a code nobody can redeem.
     */
    static OAuth2AuthorizationRequestFields authorizationRequestFields(
            OAuth2Authorization authorization) {
        Object request = authorization.getAttribute(
                org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest.class.getName());
        if (!(request instanceof org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest oauthRequest)) {
            throw new IllegalStateException("authorization " + authorization.getId()
                    + " carries a code but no OAuth2AuthorizationRequest attribute; PKCE cannot be"
                    + " checked without it, so the code must not be written");
        }
        Map<String, Object> parameters = oauthRequest.getAdditionalParameters();
        return new OAuth2AuthorizationRequestFields(
                oauthRequest.getRedirectUri(),
                String.valueOf(parameters.get("code_challenge")),
                String.valueOf(parameters.get("code_challenge_method")),
                resource(parameters));
    }

    static String resource(Map<String, Object> parameters) {
        Object resource = parameters.get(org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames.RESOURCE);
        if (resource == null) {
            return null;
        }
        // RFC 8707 allows several; the column is one string, and the stored value is for the record —
        // the framework validates the audience from the request, not from here.
        return resource instanceof List<?> list && !list.isEmpty()
                ? String.valueOf(list.getFirst())
                : String.valueOf(resource);
    }

    record AuthorizationRow(String id, String registeredClientId, String principalName,
            String grantType, String authorizedScopes, String attributes, String createdAt,
            String revokedAt) {

        /** Whether this authorization has been ended — the authority for everything below it. */
        boolean revoked() {
            return revokedAt != null;
        }
    }

    record CodeRow(String hash, String expiresAt, String usedAt) {
    }

    record AccessRow(String hash, String scope, String expiresAt, String revokedAt,
            String createdAt) {
    }

    record RefreshRow(String hash, String expiresAt, String revokedAt, String createdAt) {
    }

    record OAuth2AuthorizationRequestFields(String redirectUri, String codeChallenge,
            String codeChallengeMethod, String resource) {
    }
}
