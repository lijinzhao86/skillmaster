package com.skillmasterai.config;

import com.skillmasterai.modules.account.SessionPolicy;
import com.skillmasterai.modules.auth.Scopes;
import com.skillmasterai.modules.blob.BlobSettings;
import com.skillmasterai.modules.distribution.DiffLimits;
import com.skillmasterai.modules.token.ApiKeyPolicy;
import com.skillmasterai.modules.search.RelevanceWeights;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
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
 *                      challenges and the authorization server's {@code issuer}. It cannot be
 *                      derived from a request: behind a proxy the request's own host is not the
 *                      client's.
 * @param search        how results are ranked (see {@code modules/search})
 * @param sessions      how long a browser session lives, on its two rulers (ADR 0043). M1's own
 *                      record, for the reason {@code diff} and {@code apiKeys} are M9's and M2's:
 *                      there is then no second copy of either number to keep in step. One of the two
 *                      is also the framework's idle timeout — {@code spring.session.timeout} is
 *                      written as a reference to {@code skillmaster.sessions.idle} in
 *                      {@code application.yml}, so the two readers share one property rather than
 *                      agreeing by convention
 * @param tokens        how long tokens live, and how much of a refresh race is forgiven
 *                      (see {@code modules/token})
 * @param diff          how much of a comparison between two versions may be rendered. Typed as the
 *                      module's own record rather than as a nested config record, so that M9's
 *                      limits exist in exactly one place — the shape {@link Search} already has, and
 *                      the reason {@code DiffConfig} is a one-line bean
 * @param apiKeys       M2's dials for the keys an account issues to itself: how long one may live
 *                      here — the lifetime a request that says nothing gets **and the latest date a
 *                      request that says something may name** — whether they get a date at all, and
 *                      how often the request path may stamp one as used (ADR 0038 §决定 3, ADR 0039,
 *                      ADR 0042). M2's own record again, for the same reason as {@code diff}
 * @param welcome       the skills a new account's library starts with (ADR 0144)
 */
@ConfigurationProperties("skillmaster")
public record SkillmasterProperties(String publicBaseUrl, Search search,
        Account account, SessionPolicy sessions, Sms sms, Tokens tokens, DiffLimits diff,
        ApiKeyPolicy apiKeys, BlobSettings blobs, Welcome welcome, Sync sync) {

    public SkillmasterProperties {
        Objects.requireNonNull(publicBaseUrl,
                "skillmaster.public-base-url is required: absolute URLs we publish depend on it");
        // Normalised here, at the one point the property enters the application, because every
        // consumer appends a path that already begins with a slash. With a trailing one left in,
        // the authorization server's metadata address reads
        // "https://host//.well-known/oauth-authorization-server" — and "//" is rejected outright by
        // StrictHttpFirewall, so the URL a client was told to fetch is unfetchable.
        publicBaseUrl = stripTrailingSlashes(publicBaseUrl);
        Objects.requireNonNull(search, "skillmaster.search is required");
        Objects.requireNonNull(account, "skillmaster.account is required");
        Objects.requireNonNull(sessions, "skillmaster.sessions is required");
        Objects.requireNonNull(sms, "skillmaster.sms is required");
        Objects.requireNonNull(tokens, "skillmaster.tokens is required");
        Objects.requireNonNull(diff, "skillmaster.diff is required");
        Objects.requireNonNull(blobs, "skillmaster.blobs is required");
        Objects.requireNonNull(apiKeys, "skillmaster.api-keys is required");
        Objects.requireNonNull(welcome, "skillmaster.welcome is required");
        Objects.requireNonNull(sync, "skillmaster.sync is required");
    }

    private static String stripTrailingSlashes(String baseUrl) {
        int end = baseUrl.length();
        while (end > 0 && baseUrl.charAt(end - 1) == '/') {
            end--;
        }
        return baseUrl.substring(0, end);
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
     * @param activityWindow      how stale a login credential's recorded expiry must be before a
     *                            request rewrites it — the sampling window of the sliding expiry v4
     *                            gave the CLI's login, and therefore how early its idle ruler may fire
     */
    public record Tokens(
            @DefaultValue("PT1H") Duration accessToken,
            @DefaultValue("P30D") Duration refreshTokenIdle,
            @DefaultValue("P180D") Duration refreshTokenAbsolute,
            @DefaultValue("PT5M") Duration authorizationCode,
            @DefaultValue("PT60S") Duration refreshReplayGrace,
            @DefaultValue("PT5M") Duration activityWindow) {
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

    /**
     * v50's open-source sync (ADR 0155–0159).
     *
     * <p>Off by default. Turning it on requires a sources file with at least one entry and a GitHub
     * token: unauthenticated GitHub answers 60 requests an hour, which one run of a modest list can
     * exhaust. {@code concurrency} bounds fetch/scan work only — writes stay serial because a
     * submission takes a table lock.
     */
    public record Sync(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("classpath:sync-sources.yml") String sourcesFile,
            @DefaultValue String githubToken,
            @DefaultValue String triggerToken,
            @DefaultValue("5") int concurrency,
            @DefaultValue("0 0 2 * * *") String cron,
            @DefaultValue("67108864") long maxRepoBytes,
            @DefaultValue("512") int maxFiles,
            @DefaultValue("104857600") long maxFileBytes,
            @DefaultValue("104857600") long maxTotalBytes,
            @DefaultValue("268435456") long maxUnpackedBytes) {
    }

    /**
     * The skills a new account's library starts with (ADR 0144).
     *
     * <p><strong>A malformed address is refused here, not later.</strong> The runtime treats an
     * absent or unjoinable skill as "skip it", which is right for a deployment that has not
     * published one yet — but it would also turn a typo into a silent "every new account is missing
     * this skill". Startup fails with the value instead.
     *
     * @param skills each {@code namespace/name}; no version, no {@code @}
     */
    public record Welcome(List<String> skills) {

        public Welcome {
            List<String> checked = new ArrayList<>();
            for (String raw : skills == null ? List.<String>of() : skills) {
                String skill = raw.trim();
                if (skill.isEmpty()) {
                    continue;
                }
                // A space here is a deployment typo, not a name: the runtime would skip the address
                // as "no such namespace" and every new account would quietly miss the skill.
                if (skill.chars().anyMatch(Character::isWhitespace)) {
                    throw new IllegalArgumentException(
                            "skillmaster.welcome.skills: '" + skill + "' contains whitespace");
                }
                String[] parts = skill.split("/", -1);
                if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()
                        || skill.contains("@")) {
                    throw new IllegalArgumentException(
                            "skillmaster.welcome.skills: '" + skill + "' is not namespace/name");
                }
                checked.add(skill);
            }
            skills = List.copyOf(checked);
        }
    }

}
