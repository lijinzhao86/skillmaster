package com.skillmasterai.modules.version;

/**
 * Everything M7 knows about one skill and one of its versions: the skill's identity, that version's
 * metadata, and its full manifest.
 *
 * <p>One object rather than three lookups at the call site, because the three are only meaningful
 * together — a version without its skill has no name to serve, and a skill without its version has
 * no content. Assembling it is M7's job; what to do with it is not.
 *
 * <p><strong>Which version this is depends on the {@link VersionPin} that was asked for</strong>, so
 * it is not necessarily the current one — that is the whole point of pinning, and {@code isLatest}
 * says which case this is. Both facts are M7's to derive, because {@code current_version_id} is a
 * column of a table M7 owns.
 *
 * <p><strong>The metadata is the version's, not the skill's.</strong> Since ADR 0031 a version
 * carries its own title, description and frontmatter, and this object reports those: a caller that
 * asked for {@code @1} is looking at {@code @1}, and describing it with the current version's
 * description would be the same mistake as serving the wrong bytes. The skill row keeps a copy of
 * the current version's, which is what search reads.
 *
 * <p>{@code frontmatterJson} stays a string here. M7 has no opinion about the shape of a
 * frontmatter document — it stores what M5 parsed and hands it back — and turning it into a tree
 * is the API layer's business, which is also what keeps unknown fields passing through untouched
 * (§3.3).
 *
 * @param number   the version's immutable alias (ADR 0012) — what {@code @N} in an address names
 * @param state    draft, published or discarded (ADR 0031). On the consumption plane it is always
 *                 published, because that is the predicate that got here
 * @param stateAt  when the version left draft — first published, or discarded. Null while draft,
 *                 which is every version the author plane shows before anyone has published one
 * @param isLatest whether this is the version the skill currently points at. Derived here rather
 *                 than compared at the edge, so that the pointer is read from one place
 * @param manifest that version's files, sorted by relpath, carrying no content
 */
public record SkillSnapshot(
        String skillId,
        String namespaceId,
        String name,
        String title,
        String description,
        String frontmatterJson,
        String visibility,
        int number,
        String digest,
        int fileCount,
        long totalBytes,
        String state,
        String stateAt,
        boolean isLatest,
        Manifest manifest) {
}
