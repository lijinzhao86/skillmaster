package com.skillmasterai.common;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The module map of technical-design.md §2.5, expressed as data so tests can assert against
 * it rather than against a prose table that drifts.
 *
 * <p>Each module declares the tables it owns. The rule this encodes is §2.5 rule 1: a module
 * may only read and write its own tables, and reaching another module's data must go through
 * that module's interface. {@code TableOwnershipTest} enforces it as far as static analysis
 * can — see that class for the honest limits of the check.
 *
 * <p>M1 and M2 are listed although P0 has no code for them: their tables are part of the
 * baseline schema, and leaving them off this map would make "who owns {@code access_token}?"
 * unanswerable. Their packages appear in P1.
 */
public final class ModuleMap {

    private ModuleMap() {
    }

    public static final String BASE_PACKAGE = "com.skillmasterai";
    public static final String MODULES_PACKAGE = BASE_PACKAGE + ".modules";

    /**
     * @param id           the §2.5 identifier, e.g. {@code M6}
     * @param name         the module's role in one or two words
     * @param packageName  the package that embodies it; repositories live in {@code .internal}
     * @param tables       the tables it owns — and the only ones it may touch
     */
    public record Module(String id, String name, String packageName, Set<String> tables) {

        public String internalPackage() {
            return packageName + ".internal";
        }

        /**
         * The types this module publishes to other modules. The data lives in {@link #PUBLISHED_TYPES}
         * so the baseline is one table rather than thirteen constructor arguments; this accessor is
         * the shape the architecture rule and the documentation use.
         */
        public Set<String> publishedTypes() {
            return PUBLISHED_TYPES.getOrDefault(id, Set.of());
        }
    }

    private static final List<Module> MODULES = List.of(
            // The session tables are here because M1's login is what creates a session, and
            // because a table in a migration has to have an owner — TableOwnershipTest says so.
            // `browser_session` was dropped in V3 in favour of Spring Session's pair; nothing in
            // Java may spell either of them in SQL, since the framework is what reads and writes
            // them (see WebSessionRegistry).
            new Module("M1", "account", MODULES_PACKAGE + ".account",
                    Set.of("app_user", "credential", "identity", "phone_verification",
                            "legal_consent", "auth_throttle", "captcha", "spring_session",
                            "spring_session_attributes")),
            // V5 added `oauth2_authorization_consent` and `authorization_id` to each of the other
            // three (ADR 0023); it also carried `oauth_client.user_id`, for ADR 0022's unattended
            // client, and v3 took that back out along with the grant it existed for. V6 added
            // `oauth_authorization`, the parent row V5's `authorization_id` needed and the home for
            // the framework's attributes. The consent table is the framework's rather than ours, but
            // a table in a migration has to have an owner either way — TableOwnershipTest asserts the
            // two sets are equal, so this entry and those migrations have to move together.
            //
            // V13 added `api_key` (ADR 0038): the credential an account issues to itself, beside
            // the framework's authorization-code pair rather than inside it, because a key has no
            // authorization, consent or refresh chain for the validator's parent-row EXISTS to
            // find. Same module, because one seam above them validates both.
            //
            // V14 added `cli_sign_in_code` (ADR 0028): the one-time code that carries an
            // already-minted credential back to the CLI, so the credential itself never travels in a
            // URL. Here rather than in M1, because what it delivers is a token and the row it names
            // is this module's `oauth_authorization`.
            new Module("M2", "token", MODULES_PACKAGE + ".token",
                    Set.of("oauth_client", "oauth_authorization", "auth_code", "access_token",
                            "refresh_token", "oauth2_authorization_consent", "api_key",
                            "cli_sign_in_code")),
            new Module("M3", "auth", MODULES_PACKAGE + ".auth", Set.of()),
            new Module("M4", "namespace", MODULES_PACKAGE + ".namespace",
                    Set.of("namespace", "namespace_member")),
            new Module("M5", "ingest", MODULES_PACKAGE + ".ingest", Set.of()),
            new Module("M6", "blob", MODULES_PACKAGE + ".blob",
                    Set.of("blob", "blob_content", "blob_deletion")),
            // M7 is versions and bytes only since ADR 0053: `skill`, `skill_grant` and
            // `skill_access_request` moved to M12, so a content query never names the metadata
            // tables and a metadata query never names the content ones.
            new Module("M7", "version", MODULES_PACKAGE + ".version",
                    Set.of("skill_version", "version_file")),
            new Module("M8", "search", MODULES_PACKAGE + ".search", Set.of()),
            new Module("M9", "distribution", MODULES_PACKAGE + ".distribution", Set.of()),
            new Module("M10", "audit", MODULES_PACKAGE + ".audit", Set.of("audit_event")),
            // M13 is v19's semantic retrieval: the vectors, and nothing else. It owns no lifecycle
            // state — visibility is asked of M12 at search time — so a skill going offline or
            // private needs no row changed here. The one write is the embedding itself, on publish
            // (through `EmbeddingIndex`), and no second copy of the state exists to keep in step.
            new Module("M13", "retrieval", MODULES_PACKAGE + ".retrieval",
                    Set.of("skill_embedding")),
            // M11 (gateway) retired in v20: its one artefact, the startup journey, is the platform's
            // `skillmaster/skillmaster-startup` skill now (ADR 0096), so the package it named no longer exists.
            // M12 was carved out of M7 for v5 (ADR 0053): the skill's address and identity, the
            // ladder, grants and requests, visibility, deletion — and the V21 projections the
            // lists read. It owns no content; its one outgoing edge is M13's published
            // `EmbeddingTextSource`, implemented in `internal/SkillEmbeddingTextSource`.
            // V26-V28 added how people organize skills (v14, ADR 0082/0083): the library's join
            // references, both tag layers, and the category vocabulary. They belong here because a
            // listing, its filters, and the visibility predicate must be one statement — splitting
            // them would make every library page a cross-module join, which rule 1 forbids.
            new Module("M12", "skill", MODULES_PACKAGE + ".skill",
                    Set.of("skill", "skill_grant", "skill_access_request", "skill_invitation",
                            "skill_library_join", "skill_public_tag", "my_tag_definition",
                            "my_tag_attachment", "skill_category",
                            "skill_source", "skill_source_snapshot", "skill_version_license",
                            // v54: an internal copy is provenance about the skill row, not an
                            // external source; M12 writes and reads it, and no other module does.
                            "skill_copy")),
            // M14 is v45's notification floor (ADR 0139). It owns the event and the per-recipient
            // read state, and nothing else: the business object a notification points at stays in
            // its own module, which is what lets every other module use M14 without an edge back.
            new Module("M14", "notification", MODULES_PACKAGE + ".notification",
                    Set.of("notification", "notification_recipient")),
            // M15 is v49's usage metrics (ADR 0153): the two series that answer "is anybody using
            // it" and "how much". It knows nothing about visibility — the caller chooses a scope —
            // so the public read model can use it without M15 growing a public/private branch.
            new Module("M15", "metrics", MODULES_PACKAGE + ".metrics",
                    Set.of("skill_usage_day", "skill_usage_total")),
            // M16 is v52's service credentials (ADR 0168/0169/0172). It owns the value, its
            // bindings, the daily usage aggregate and the per-skill share grant. It publishes the
            // use-case entry and the value types only; the tables never leave the module, and the
            // API layer composes "does this run have credentials, and which declared ones are
            // missing" from M7's metadata and M16's `boundEnvNames` rather than giving M12 a
            // dependency on M16.
            new Module("M16", "secret", MODULES_PACKAGE + ".secret",
                    Set.of("service_credential", "credential_binding", "credential_use",
                            "credential_share")));

    /**
     * The types each module publishes to other modules (v36, ADR 0123).
     *
     * <p><strong>This is the current baseline, not the final shape.</strong> It was generated from
     * the cross-module imports that already existed when the rule was introduced; the rule stops
     * new ones, and the list may only shrink. Adding a type here is a contract change and needs the
     * same review as adding a public method.
     *
     * <p>Simple names are enough because no two modules currently define the same public simple
     * name. If that changes, switch these sets to fully qualified names.
     */
    private static final Map<String, Set<String>> PUBLISHED_TYPES = Map.ofEntries(
            Map.entry("M1", Set.of("AccountDirectory", "AuthThrottle", "Throttle",
                    "ThrottledException")),
            Map.entry("M2", Set.of()),
            Map.entry("M3", Set.of("AuthenticatedSubject", "TokenValidation", "TokenValidator")),
            Map.entry("M4", Set.of("Namespace")),
            Map.entry("M5", Set.of("ReservedPaths", "Utf8Probe")),
            Map.entry("M6", Set.of("BlobSettings", "BlobStore")),
            Map.entry("M7", Set.of("Manifest", "ManifestEntry", "SkillSnapshot",
                    "SkillVersionService", "VersionPin", "VersionState", "VersionSummary",
                    "VersionProjectionSink")),
            Map.entry("M8", Set.of()),
            Map.entry("M9", Set.of()),
            Map.entry("M10", Set.of("AuditEvent", "AuditLog")),
            Map.entry("M12", Set.of("Access", "Caller", "DraftProjection", "GrantRole",
                    "NotPermittedException", "SkillCatalogService", "SkillMetadata",
                    "SkillMetadataService", "VersionProjection")),
            Map.entry("M13", Set.of("EmbeddingTextSource")),
            Map.entry("M14", Set.of("Notification", "NotificationDraft", "NotificationKinds",
                    "NotificationOutcome", "NotificationPage", "NotificationQuery",
                    "NotificationService", "NotificationStateException",
                    "NotificationNotFoundException")),
            Map.entry("M15", Set.of("SkillLoad", "UsageQuery", "UsageRecorder", "UsageScope",
                    "UsageSnapshot")),
            Map.entry("M16", Set.of("SecretService", "CredentialRef", "CredentialBinding",
                    "CredentialLease", "CredentialValue", "IncomingBinding", "SecretSettings",
                    "SecretNotFoundException", "CredentialBindingNotFoundException",
                    "BindingConflictException", "SecretValueTooLargeException",
                    "QuotaExceededException", "NotShareableException", "ShareNotFoundException",
                    "CredentialVaultUnavailableException")));

    /**
     * The shared kernel (ADR 0123): cross-cutting domain words that every module may use. It is
     * deliberately small; a new entry here is an ADR, not an import.
     */
    public static final Set<String> SHARED_KERNEL =
            Set.of("Access", "GrantRole", "VersionPin", "VersionState");

    /** Whether {@code simpleName} is a published type of {@code module} or part of the kernel. */
    public static boolean publishes(Module module, String simpleName) {
        return SHARED_KERNEL.contains(simpleName) || module.publishedTypes().contains(simpleName);
    }

    public static List<Module> modules() {
        return MODULES;
    }

    public static Optional<Module> byId(String id) {
        return MODULES.stream().filter(m -> m.id().equals(id)).findFirst();
    }

    /** Every table the baseline schema defines, mapped to the module that owns it. */
    public static Map<String, Module> tableOwners() {
        Map<String, Module> owners = new LinkedHashMap<>();
        for (Module module : MODULES) {
            for (String table : module.tables()) {
                Module previous = owners.put(table, module);
                if (previous != null) {
                    throw new IllegalStateException(
                            "table '" + table + "' is claimed by both " + previous.id()
                                    + " and " + module.id());
                }
            }
        }
        return owners;
    }
}
