package com.skillmasterai.config;

import com.skillmasterai.modules.auth.Scopes;
import com.skillmasterai.modules.search.RelevanceWeights;
import java.time.Duration;
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
 * @param search        how results are ranked (see {@code modules/search})
 * @param gateway       what the discovery channel publishes (see {@code modules/gateway})
 * @param tokens        how long tokens live, and how much of a refresh race is forgiven
 *                      (see {@code modules/token})
 */
@ConfigurationProperties("skillmaster")
public record SkillmasterProperties(String publicBaseUrl, Search search, Gateway gateway,
        Account account, Sms sms, Tokens tokens) {

    public SkillmasterProperties {
        Objects.requireNonNull(publicBaseUrl,
                "skillmaster.public-base-url is required: absolute URLs we publish depend on it");
        // Normalised here, at the one point the property enters the application, because every
        // consumer appends a path that already begins with a slash. With a trailing one left in,
        // the gateway index advertises "https://host//gateway/SKILL.md" — and "//" is rejected
        // outright by StrictHttpFirewall, so the URL a client was told to fetch is unfetchable.
        publicBaseUrl = stripTrailingSlashes(publicBaseUrl);
        Objects.requireNonNull(search, "skillmaster.search is required");
        Objects.requireNonNull(gateway, "skillmaster.gateway is required");
        Objects.requireNonNull(account, "skillmaster.account is required");
        Objects.requireNonNull(sms, "skillmaster.sms is required");
        Objects.requireNonNull(tokens, "skillmaster.tokens is required");
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
     * The five numbers M2 runs on. §3.1 asks for these to be configuration rather than constants,
     * and the defaults here are the design's values, so a deployment that says nothing gets them.
     *
     * <p>ISO-8601 durations, which is what {@link Duration} binds from — {@code PT1H} rather than
     * {@code 1h}. The verbose form is the one that cannot be misread: {@code 1m} is a minute to
     * Spring and would read as a month to somebody skimming.
     *
     * @param accessToken         the short-lived credential every request carries
     * @param refreshTokenIdle    how long a refresh token survives unused
     * @param refreshTokenAbsolute the ceiling on the whole authorization, however much it is used
     * @param authorizationCode   how long an authorization code is good for
     * @param refreshReplayGrace  how long after a rotation a spent refresh token counts as a race
     */
    public record Tokens(
            @DefaultValue("PT1H") Duration accessToken,
            @DefaultValue("P30D") Duration refreshTokenIdle,
            @DefaultValue("P180D") Duration refreshTokenAbsolute,
            @DefaultValue("PT5M") Duration authorizationCode,
            @DefaultValue("PT60S") Duration refreshReplayGrace) {
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
