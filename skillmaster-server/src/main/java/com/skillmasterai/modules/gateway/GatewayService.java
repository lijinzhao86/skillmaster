package com.skillmasterai.modules.gateway;

import com.skillmasterai.modules.blob.BlobStore;
import com.skillmasterai.modules.gateway.internal.WellKnownDigest;
import com.skillmasterai.modules.ingest.SkillUploadValidator;
import com.skillmasterai.modules.namespace.Namespace;
import com.skillmasterai.modules.namespace.NamespaceService;
import com.skillmasterai.modules.version.ManifestEntry;
import com.skillmasterai.modules.version.SkillSnapshot;
import com.skillmasterai.modules.version.SkillVersionService;
import com.skillmasterai.modules.version.Caller;
import com.skillmasterai.modules.version.VersionPin;
import java.util.List;
import java.util.Optional;

/**
 * M11: the anonymous discovery channel of §1.5, and the gateway skill's own endpoints.
 *
 * <p>Four paths, and the reason they are shaped the way they are is worth stating once. This
 * channel has <strong>no authentication and no gating</strong> — §1.5 verified both by search, and
 * that is what makes it the only way a machine that has never logged in can be told where to log
 * in. So it publishes exactly one artefact: the gateway skill, whose job is to describe how to
 * reach the authenticated API. Everything else stays behind the API's token.
 *
 * <p><strong>Nothing here is generated from a template at request time.</strong> The gateway skill
 * is an ordinary published skill with a version and a digest (§4.5); these endpoints read it back
 * out of M7 and M6 like any other. That is what makes §1.5's trap avoidable — a host that has not
 * published must answer a real 404, and a copy served from a classpath resource would have answered
 * 200 to everything.
 *
 * <p>The host substitution happens once, at publish time, and not here. Rewriting on every request
 * would mean the bytes served were not the bytes the digest was taken over, which is the one thing
 * §4.2 says must never happen.
 */
public final class GatewayService {

    /**
     * The namespace whose existence makes the reserved name unavailable through the schema rather
     * than through a deny list (§3.2, and the seed migration says the same).
     */
    public static final String RESERVED_NAMESPACE_SLUG = "skillmaster";

    /**
     * The gateway skill's name, which is also the path segment clients fetch it under.
     *
     * <p>§5.1 requires the source file's {@code name} to be this. The bootstrap checks the two
     * against each other at startup rather than letting them drift into a 404 on a URL that looks
     * right.
     */
    public static final String SKILL_NAME = "skillmaster";

    /** The three bases the file fetch is published under; see {@link #fileOf(String, String)}. */
    public static final List<String> FILE_BASE_ALIASES =
            List.of("gateway", "skills", "agent-skills");

    /*
     * The routes, here rather than in the controller, because the index advertises them. A path
     * written down twice is a path that can drift, and this particular drift is invisible: the
     * index would keep advertising a URL that 404s, and clients would keep failing to bootstrap
     * for no reason anyone could see from the server side. The controller references these
     * constants; the modules below them cannot see the API layer at all (nor should they).
     */
    public static final String INDEX_V1_PATH = "/.well-known/skills/index.json";
    public static final String INDEX_V2_PATH = "/.well-known/agent-skills/index.json";
    public static final String FILE_BASE_PATTERN =
            "/.well-known/{provider}/" + SKILL_NAME + "/{*relpath}";
    public static final String BODY_PATH = "/gateway/" + SkillUploadValidator.SKILL_MD;

    private final NamespaceService namespaces;
    private final SkillVersionService versions;
    private final BlobStore blobs;
    private final String publicBaseUrl;
    private final String schemaUrl;

    public GatewayService(NamespaceService namespaces, SkillVersionService versions, BlobStore blobs,
            String publicBaseUrl, String schemaUrl) {
        this.namespaces = namespaces;
        this.versions = versions;
        this.blobs = blobs;
        this.publicBaseUrl = publicBaseUrl;
        this.schemaUrl = schemaUrl;
    }

    /** @return the legacy index, or empty when the gateway skill has never been published */
    public Optional<GatewayIndex.V1> indexV1() {
        return gatewaySkill().map(snapshot -> new GatewayIndex.V1(List.of(
                new GatewayIndex.V1.Entry(snapshot.name(), snapshot.description(),
                        snapshot.manifest().entries().stream()
                                .map(ManifestEntry::relpath)
                                .toList()))));
    }

    /** @return the preferred index, or empty when the gateway skill has never been published */
    public Optional<GatewayIndex.V2> indexV2() {
        return gatewaySkill().map(snapshot -> new GatewayIndex.V2(schemaUrl, List.of(
                new GatewayIndex.V2.Entry(
                        snapshot.name(),
                        GatewayIndex.TYPE_SKILL_MD,
                        snapshot.description(),
                        publicBaseUrl + BODY_PATH,
                        WellKnownDigest.of(contentOf(snapshot))))));
    }

    /**
     * One file of the gateway skill, by exact {@code relpath}.
     *
     * <p>{@code provider} is checked against {@link #FILE_BASE_ALIASES} rather than matched by a
     * wildcard, because §1.5 found that the index's advertised base and the path clients actually
     * fetch from do not obviously agree — the resolution order is "relative to the path first,
     * then the root", and the file route is specified under a different base than the index. Rather
     * than guess which one a given client uses, the same bytes are served under all three; an
     * unknown base is a 404, so the aliases stay a stated set rather than "anything".
     *
     * @param relpath as captured from the path — the caller strips the routing separator
     */
    public Optional<GatewayFile> fileOf(String provider, String relpath) {
        if (!FILE_BASE_ALIASES.contains(provider)) {
            return Optional.empty();
        }
        return gatewaySkill()
                .flatMap(snapshot -> snapshot.manifest().find(relpath)
                        .map(entry -> new GatewayFile(entry.relpath(),
                                blobs.get(entry.blobSha256()))));
    }

    /** The gateway skill's own body — what the CLI's {@code setup} installs. */
    public Optional<GatewayFile> body() {
        return gatewaySkill()
                .flatMap(snapshot -> snapshot.manifest().find(SkillUploadValidator.SKILL_MD)
                        .map(entry -> new GatewayFile(entry.relpath(),
                                blobs.get(entry.blobSha256()))));
    }

    /** @param relpath the manifest path the bytes belong to, so the caller can type them */
    public record GatewayFile(String relpath, byte[] bytes) {
    }

    /**
     * The gateway's own published version.
     *
     * <p>The caller is the system account that owns the reserved namespace, because that is who the
     * gateway skill belongs to — not whoever is making the HTTP request, which for this path is
     * nobody at all ({@code /gateway/SKILL.md} is fetched before anyone has logged in). It is not a
     * bypass: it is the same predicate every other read goes through, satisfied by ownership rather
     * than by a grant (ADR 0034). Nothing here is caller-dependent, which is why the gateway does
     * not need to know who is asking.
     */
    private Optional<SkillSnapshot> gatewaySkill() {
        Namespace namespace = namespaces.namespaceOfSlug(RESERVED_NAMESPACE_SLUG);
        return versions.liveSnapshot(namespace.id(), SKILL_NAME, new VersionPin.Latest(),
                new Caller(namespace.ownerUserId(), namespace.id()));
    }

    private List<WellKnownDigest.File> contentOf(SkillSnapshot snapshot) {
        return snapshot.manifest().entries().stream()
                .map(entry -> new WellKnownDigest.File(entry.relpath(),
                        blobs.get(entry.blobSha256())))
                .toList();
    }
}
