package com.skillmasterai.modules.token.internal;

import java.util.List;
import java.util.Optional;
import com.skillmasterai.modules.token.TokenPolicy;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.OAuth2TokenFormat;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads {@code oauth_client} — M2's table — and answers the framework's client lookups from it.
 *
 * <p><strong>Behind the framework's interface from the first day, not hardcoded to "there is only
 * one client".</strong> §4.4 makes this an implementation constraint, and the reason is the cost of
 * the alternative: reaching P2's CIMD by replacing a constant means rewriting the authorization flow,
 * while reaching it here means adding an implementation.
 *
 * <p><strong>One invariant worth stating: a client's id is its {@code client_id}.</strong> The
 * framework keys everything by {@code RegisteredClient.getId()} — {@code oauth_authorization.
 * registered_client_id}, the consent table's first column — while our foreign keys
 * ({@code auth_code.client_id}) point at {@code oauth_client.client_id}. Making them the same string
 * is what keeps a single client from having two identities in two tables that nothing reconciles.
 *
 * <p><strong>What is not in the table is the settings.</strong> {@code oauth_client} has no columns
 * for lifetimes or for the token format, so they are constants here rather than rows. The module doc
 * has this as an open question, and it stays one: a second client wanting a different lifetime is the
 * thing that will force a decision, and there is no second client yet.
 */
public final class ClientRegistry implements RegisteredClientRepository {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String COLUMNS =
            "SELECT client_id, name, redirect_uris, grant_types, client_secret_hash, metadata"
                    + " FROM oauth_client";

    private final JdbcClient jdbc;
    private final TokenPolicy policy;

    public ClientRegistry(JdbcClient jdbc, TokenPolicy policy) {
        this.jdbc = jdbc;
        this.policy = policy;
    }

    /**
     * Refuses, loudly, because v1 has no way to register a client.
     *
     * <p>The design's answer is "no entry point; seed a row by hand at deployment" — there is no
     * administration API, and the handful of clients v1 has do not justify writing one. DCR is never
     * enabled and CIMD arrives with P2, so nothing in a running server calls this. A silent no-op
     * would be worse than an exception: a client that appeared to register and did not would fail
     * later, at the authorization endpoint, with a message about an unknown client.
     */
    @Override
    public void save(RegisteredClient registeredClient) {
        throw new UnsupportedOperationException(
                "v1 registers clients by seeding an oauth_client row at deployment; there is no"
                        + " registration endpoint (see ADR 0011 and the M02 module doc)");
    }

    @Override
    public RegisteredClient findById(String id) {
        // Valid because of the invariant above: the framework's id is our client_id.
        return find(" WHERE client_id = :id", "id", id);
    }

    @Override
    public RegisteredClient findByClientId(String clientId) {
        return find(" WHERE client_id = :clientId", "clientId", clientId);
    }

    private RegisteredClient find(String where, String name, String value) {
        Optional<ClientRow> row = jdbc.sql(COLUMNS + where)
                .param(name, value)
                .query((rs, rowNum) -> new ClientRow(
                        rs.getString("client_id"),
                        rs.getString("name"),
                        rs.getString("redirect_uris"),
                        rs.getString("grant_types"),
                        rs.getString("client_secret_hash"),
                        rs.getString("metadata")))
                .optional();
        return row.map(this::toRegisteredClient).orElse(null);
    }

    private RegisteredClient toRegisteredClient(ClientRow row) {
        RegisteredClient.Builder builder = RegisteredClient.withId(row.clientId())
                .clientId(row.clientId())
                .clientName(row.name());

        boolean isPublic = row.clientSecretHash() == null;
        if (isPublic) {
            builder.clientAuthenticationMethod(ClientAuthenticationMethod.NONE);
        } else {
            // Stored already encoded, `{id}value` and all — the column is a hash, so the prefix that
            // says which encoder produced it has to travel with it.
            builder.clientSecret(row.clientSecretHash())
                    .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC);
        }

        strings(row.redirectUris()).forEach(builder::redirectUri);
        strings(row.grantTypes())
                .forEach(grant -> builder.authorizationGrantType(new AuthorizationGrantType(grant)));
        scopes(row.metadata()).forEach(builder::scope);

        return builder
                .clientSettings(ClientSettings.builder()
                        // PKCE for the client that has nothing else, which is the CLI. A confidential
                        // client is not asked for a proof key it would have no use for, and a
                        // client_credentials grant has no authorization request to carry one.
                        .requireProofKey(isPublic)
                        // Consent is asked once per (client, user) and then remembered — the whole of
                        // "one consent, then every later login goes straight through" rests on the
                        // framework reading that record back. Turning this off would skip the page
                        // and quietly drop the product's only moment of disclosure.
                        .requireAuthorizationConsent(true)
                        .build())
                .tokenSettings(TokenSettings.builder()
                        .accessTokenFormat(OAuth2TokenFormat.REFERENCE)
                        .accessTokenTimeToLive(policy.accessToken())
                        // Rotate on every use: RFC 9700 §2.2.2 requires it of a public client, and the
                        // whole replay story (ADR 0024) is downstream of this one setting.
                        .reuseRefreshTokens(false)
                        // The **idle** lifetime, which is only half of ADR 0024's rule. The ceiling on
                        // the whole authorization is not a framework setting — it is enforced in
                        // TokenStore, because it is a fact about the authorization rather than about
                        // the token this generator is minting.
                        .refreshTokenTimeToLive(policy.refreshTokenIdle())
                        .authorizationCodeTimeToLive(policy.authorizationCode())
                        .build())
                .build();
    }

    private static List<String> strings(String jsonArray) {
        JsonNode node = JSON.readTree(jsonArray);
        if (!node.isArray()) {
            throw new IllegalStateException("oauth_client column expected a JSON array, found: " + jsonArray);
        }
        return node.valueStream().map(JsonNode::asText).toList();
    }

    /**
     * The scopes this client may ask for, which have no column of their own.
     *
     * <p>{@code metadata} is documented as "the registration, as it was registered", and the scope
     * list is part of it. A row without one is an error rather than an empty set: a client that may
     * ask for nothing would fail at the authorization endpoint with {@code invalid_scope}, which
     * reads like the client's mistake rather than the seed row's.
     */
    private static List<String> scopes(String metadata) {
        JsonNode scopes = JSON.readTree(metadata).get("scopes");
        if (scopes == null || !scopes.isArray()) {
            throw new IllegalStateException(
                    "oauth_client.metadata needs a \"scopes\" array; this row has: " + metadata);
        }
        return scopes.valueStream().map(JsonNode::asText).toList();
    }

    private record ClientRow(String clientId, String name, String redirectUris, String grantTypes,
            String clientSecretHash, String metadata) {
    }
}
