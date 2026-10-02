package com.skillmasterai.config;

import com.skillmasterai.modules.auth.Scopes;
import com.skillmasterai.modules.search.RelevanceWeights;
import java.util.Objects;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Everything this deployment configures, under {@code skillmaster.*}.
 *
 * <p>Bound by constructor, so a missing required value fails at startup with the property name
 * rather than turning into a null somewhere on a request path.
 *
 * <p>Note where these values are <em>used</em>: a module's wiring class cannot read this record.
 * The architecture rule forbids any module from depending on {@code config}, because "what this
 * deployment configures" is not a thing a module should be able to reach for. So a value a module
 * needs is built into a bean by a class in this package and passed in — see
 * {@link RankingConfig}.
 *
 * @param publicBaseUrl the base URL the outside world reaches this server at. Used for absolute
 *                      URLs we publish — the {@code resource_metadata} parameter in the 401/403
 *                      challenges, and the address written into the gateway skill. It cannot be
 *                      derived from a request: behind a proxy the request's own host is not the
 *                      client's.
 * @param auth          how P0 authenticates (see {@code modules/auth})
 * @param search        how results are ranked (see {@code modules/search})
 * @param gateway       what the discovery channel publishes (see {@code modules/gateway})
 */
@ConfigurationProperties("skillmaster")
public record SkillmasterProperties(String publicBaseUrl, Auth auth, Search search, Gateway gateway,
        Account account, Sms sms) {

    public SkillmasterProperties {
        Objects.requireNonNull(publicBaseUrl,
                "skillmaster.public-base-url is required: absolute URLs we publish depend on it");
        // Normalised here, at the one point the property enters the application, because every
        // consumer appends a path that already begins with a slash. With a trailing one left in,
        // the gateway index advertises "https://host//gateway/SKILL.md" — and "//" is rejected
        // outright by StrictHttpFirewall, so the URL a client was told to fetch is unfetchable.
        publicBaseUrl = stripTrailingSlashes(publicBaseUrl);
        Objects.requireNonNull(auth, "skillmaster.auth is required");
        Objects.requireNonNull(search, "skillmaster.search is required");
        Objects.requireNonNull(gateway, "skillmaster.gateway is required");
        Objects.requireNonNull(account, "skillmaster.account is required");
        Objects.requireNonNull(sms, "skillmaster.sms is required");
    }

    private static String stripTrailingSlashes(String baseUrl) {
        int end = baseUrl.length();
        while (end > 0 && baseUrl.charAt(end - 1) == '/') {
            end--;
        }
        return baseUrl.substring(0, end);
    }

    /**
     * @param indexSchemaUrl the {@code $schema} for the V2 discovery index. <strong>Empty by
     *                       default, and that is the honest value</strong>: §1.5 records that the
     *                       URL lives under {@code agentskills.io} as the "v0.2.0 draft" and that
     *                       it does not currently resolve, so the exact string cannot be verified
     *                       from here. Blank means the field is omitted from the document rather
     *                       than filled with a guess. P0b's CLI check is what settles it.
     */
    public record Gateway(@DefaultValue String indexSchemaUrl) {
    }

    /** @param weights the per-field values a text hit is worth; see {@link RelevanceWeights} */
    public record Search(RelevanceWeights weights) {
        public Search {
            Objects.requireNonNull(weights, "skillmaster.search.weights is required");
        }
    }

    /**
     * @param staticToken   P0's single bearer token. No default: a default would be a working
     *                      credential committed to a public repository.
     * @param subjectUserId the ULID of the {@code app_user} this token acts as. An id, not a
     *                      handle, so renaming the user cannot silently change who the token is.
     * @param scopes        the scopes the token carries
     */
    public record Auth(String staticToken, String subjectUserId,
            @DefaultValue({Scopes.SKILLS_WRITE, Scopes.SKILLS_READ}) Set<String> scopes) {
    }

    /**
     * How M1 stores phone numbers and passwords (see {@code modules/account}).
     *
     * <p>The two keys have no usable default and the class that consumes them fails at startup
     * without them. A default key would be a key anyone who reads this repository knows, and
     * {@code phone_hash} is indexed and UNIQUE — a known key turns that column back into a list of
     * phone numbers, which is the whole thing the blind index exists to prevent.
     *
     * @param phoneHmacKey   the key behind {@code app_user.phone_hash}. At least 32 bytes.
     * @param phoneEncKey    the key behind {@code app_user.phone_enc}. Exactly 32 bytes — AES-256.
     * @param bcryptStrength the BCrypt cost. Configuration rather than a constant because the test
     *                       suite hashes hundreds of passwords and cannot pay the production price
     *                       for each one; the production default is the thing that matters.
     */
    public record Account(String phoneHmacKey, String phoneEncKey,
            @DefaultValue("10") int bcryptStrength) {
    }

    /**
     * The SMS provider (see {@code modules/account}).
     *
     * <p>All four credential fields blank means none is configured, which is a state the
     * application runs in on purpose: {@link com.skillmasterai.modules.account.internal.LoggingSmsSender}
     * then either prints the code where a person can read it or refuses to send at all. It never
     * quietly reports success.
     *
     * @param logCodes      whether the fallback sender writes the code into the log. False by
     *                      default, because a verification code in a production log is a code an
     *                      operator — or anyone who can read logs — can use to take over an account
     *                      while it is valid.
     * @param acceptAnyCode whether a presented code is compared at all. False by default, and it
     *                      must stay false wherever anybody is being let in: with it on, six
     *                      arbitrary digits register an account. It exists for the wait before a
     *                      signature and template are approved, when no code can be sent and the
     *                      rest of the flow — the send, the wait, the expiry, the single use — would
     *                      otherwise be untestable. Starting with it on <em>and</em> credentials
     *                      configured is refused outright; see {@code config.SmsConfig}.
     */
    public record Sms(@DefaultValue String accessKeyId, @DefaultValue String accessKeySecret,
            @DefaultValue String signName, @DefaultValue String templateCode,
            @DefaultValue("false") boolean logCodes,
            @DefaultValue("false") boolean acceptAnyCode) {
    }
}
