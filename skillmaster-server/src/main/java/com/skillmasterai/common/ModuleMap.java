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
    }

    private static final List<Module> MODULES = List.of(
            // The session tables are here because M1's login is what creates a session, and
            // because a table in a migration has to have an owner — TableOwnershipTest says so.
            // `browser_session` was dropped in V3 in favour of Spring Session's pair; nothing in
            // Java may spell either of them in SQL, since the framework is what reads and writes
            // them (see WebSessionRegistry).
            new Module("M1", "account", MODULES_PACKAGE + ".account",
                    Set.of("app_user", "credential", "identity", "phone_verification",
                            "auth_throttle", "captcha", "spring_session",
                            "spring_session_attributes")),
            // V5 added `oauth2_authorization_consent` and one column each to the other three
            // (ADR 0022, ADR 0023); V6 added `oauth_authorization`, the parent row V5's
            // `authorization_id` needed and the home for the framework's attributes. The consent
            // table is the framework's rather than ours, but a table in a migration has to have an
            // owner either way — TableOwnershipTest asserts the two sets are equal, so this entry
            // and those migrations have to move together.
            new Module("M2", "token", MODULES_PACKAGE + ".token",
                    Set.of("oauth_client", "oauth_authorization", "auth_code", "access_token",
                            "refresh_token", "oauth2_authorization_consent")),
            new Module("M3", "auth", MODULES_PACKAGE + ".auth", Set.of()),
            new Module("M4", "namespace", MODULES_PACKAGE + ".namespace",
                    Set.of("namespace", "namespace_member")),
            new Module("M5", "ingest", MODULES_PACKAGE + ".ingest", Set.of()),
            new Module("M6", "blob", MODULES_PACKAGE + ".blob",
                    Set.of("blob", "blob_content")),
            new Module("M7", "version", MODULES_PACKAGE + ".version",
                    Set.of("skill", "skill_version", "version_file", "skill_grant")),
            new Module("M8", "search", MODULES_PACKAGE + ".search", Set.of()),
            new Module("M9", "distribution", MODULES_PACKAGE + ".distribution", Set.of()),
            new Module("M10", "audit", MODULES_PACKAGE + ".audit",
                    Set.of("audit_event", "skill_stat")),
            new Module("M11", "gateway", MODULES_PACKAGE + ".gateway", Set.of()));

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
