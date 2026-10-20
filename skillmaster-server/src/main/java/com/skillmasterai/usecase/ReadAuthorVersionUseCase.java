package com.skillmasterai.usecase;

import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.modules.namespace.NamespaceService;
import com.skillmasterai.modules.skill.Access;
import com.skillmasterai.modules.skill.GrantRole;
import com.skillmasterai.modules.skill.NotPermittedException;
import com.skillmasterai.modules.skill.SkillMetadataService;
import com.skillmasterai.modules.version.SkillVersionService;
import com.skillmasterai.modules.version.VersionPin;
import com.skillmasterai.modules.version.VersionSummary;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * One version's facts for the author API, at the rung that version needs.
 *
 * <p>The visibility rule is the list's, applied to one row: published is {@code browse}, a draft is
 * {@code write}, and revoked or discarded is {@code maintain}. An invisible pin is 404 — the same
 * answer as a pin that names nothing, because telling them apart would disclose the row.
 */
@Component
public class ReadAuthorVersionUseCase {

    private final NamespaceService namespaces;
    private final SkillVersionService versions;
    private final SkillMetadataService metadata;

    public ReadAuthorVersionUseCase(NamespaceService namespaces, SkillVersionService versions,
            SkillMetadataService metadata) {
        this.namespaces = namespaces;
        this.versions = versions;
        this.metadata = metadata;
    }

    @Transactional(readOnly = true)
    public Optional<View> show(String namespaceSlug, String name, VersionPin pin,
            AuthenticatedSubject subject) {
        Optional<Access> resolved = accessOf(namespaceSlug, name, subject);
        if (resolved.isEmpty()) {
            return Optional.empty();
        }
        Access access = resolved.get();
        return versions.authorVersions(access).stream()
                .filter(row -> matches(row, pin))
                .findFirst()
                .map(row -> new View(access, row));
    }

    /**
     * The caller's access to this skill's versions, resolved and checked at the plane's floor.
     *
     * <p>Separate from {@link #show} because the write routes need the access without the read's
     * visibility filter: discarding a draft makes it invisible to {@code write}, yet the write
     * itself was allowed and its response must still name the row it changed.
     */
    @Transactional(readOnly = true)
    public Optional<Access> accessOf(String namespaceSlug, String name,
            AuthenticatedSubject subject) {
        Optional<Access> resolved = namespaces.bySlug(namespaceSlug)
                .flatMap(namespace -> metadata.accessFor(namespace.id(), name,
                        Callers.of(namespaces, subject)));
        if (resolved.isEmpty()) {
            return Optional.empty();
        }
        Access access = resolved.get();
        if (!access.atLeast(GrantRole.BROWSE)) {
            throw new NotPermittedException("reading '" + name + "' versions needs the `browse` rung");
        }
        return Optional.of(access);
    }

    /**
     * The same row after a write, addressed by the pin the request used.
     *
     * <p>State-blind on purpose: the write has just moved the row into a state this caller's read
     * may no longer see — a {@code write} caller's own discard is the case — and answering 404 to a
     * write that succeeded would report the opposite of what happened. What the caller may act on
     * was decided by the write path itself.
     */
    public Optional<VersionSummary> rereadAfterWrite(Access access, VersionPin pin) {
        return versions.authorVersionIgnoringState(access, pin);
    }

    private static boolean matches(VersionSummary row, VersionPin pin) {
        return switch (pin) {
            case VersionPin.Draft(String id) -> row.id().equals(id);
            case VersionPin.Named(String version) -> version.equals(row.version());
            case VersionPin.Digest(String digest) -> digest.equals(row.digest());
            case VersionPin.Latest() -> row.isCurrent();
        };
    }

    /**
     * @param access the resolved access, so the controller names the skill without a second lookup
     */
    public record View(Access access, VersionSummary version) {
    }
}
