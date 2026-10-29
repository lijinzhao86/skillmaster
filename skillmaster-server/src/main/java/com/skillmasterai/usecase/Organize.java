package com.skillmasterai.usecase;

import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.modules.namespace.Namespace;
import com.skillmasterai.modules.namespace.NamespaceService;
import com.skillmasterai.modules.skill.Caller;
import com.skillmasterai.modules.skill.SkillMetadataService;
import com.skillmasterai.modules.skill.SkillStanding;
import java.util.Optional;

/**
 * Resolving an address for the v14 organize writes (v14).
 *
 * <p>Four use cases need the same first step: a namespace slug, the caller, and the row's library
 * standing. Keeping it here means the 404 rule — an address that names nothing, or names a skill
 * the caller cannot see, is one empty result — is written once.
 *
 * <p>Package-private on purpose: this is a use-case-layer composition detail, not a module seam.
 */
final class Organize {

    private Organize() {
    }

    record Target(Caller caller, SkillStanding standing) {
    }

    /** @return empty when the address resolves to nothing the caller may see (the controller's 404) */
    static Optional<Target> resolve(NamespaceService namespaces, SkillMetadataService metadata,
            String namespaceSlug, String name, AuthenticatedSubject subject) {
        Optional<Namespace> namespace = namespaces.bySlug(namespaceSlug);
        if (namespace.isEmpty()) {
            return Optional.empty();
        }
        Caller caller = Callers.of(namespaces, subject);
        return metadata.standing(namespace.get().id(), name, caller)
                .map(standing -> new Target(caller, standing));
    }

    /**
     * The refusal for a private-tag write on a visible skill that is not in the library.
     *
     * <p>Two sentences, because the caller's next step differs: a joinable skill gets the command
     * to join it; one that is offline or unpublished gets the reason there is no such command
     * (TD §错误形状). Both carry {@code reason=not_in_library}, so a client can branch without
     * reading prose.
     */
    static com.skillmasterai.modules.skill.NotPermittedException notInLibrary(SkillStanding standing,
            String namespaceSlug) {
        String address = namespaceSlug + "/" + standing.name();
        // `--yes` is part of the command: without it `skill join` only prints the consequence and
        // does nothing, so a next step that omits it would send the caller in a circle.
        String message = standing.discoverable()
                ? "先把 " + address + " 加入你的库：`skmr skill join " + address + " --yes`"
                : "这个 skill 现在加不进库；上线后可以再加入并贴标签";
        return new com.skillmasterai.modules.skill.NotPermittedException(message,
                java.util.List.of(new com.skillmasterai.common.ApiError.Detail(
                        "reason", "not_in_library")));
    }

    static com.skillmasterai.common.ConflictException dormant(String name) {
        return new com.skillmasterai.common.ConflictException(
                com.skillmasterai.common.ErrorCode.LIBRARY_ENTRY_DORMANT,
                "'" + name + "' is in your library but its entry is dormant; it becomes writable"
                        + " again when the skill is published, online and visible");
    }
}
