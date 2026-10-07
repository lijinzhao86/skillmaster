package com.skillmasterai.usecase;

import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.modules.gateway.GatewayService;
import com.skillmasterai.modules.gateway.GatewaySource;
import com.skillmasterai.modules.namespace.Namespace;
import com.skillmasterai.modules.namespace.NamespaceService;
import com.skillmasterai.modules.version.SkillVersionService;
import com.skillmasterai.modules.version.VersionNameTakenException;
import com.skillmasterai.modules.version.Caller;
import com.skillmasterai.modules.version.VersionPin;
import com.skillmasterai.usecase.model.SubmittedSkill;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.Set;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Publishes the gateway skill from the repository's source, as part of the running system.
 *
 * <p>This is the only skill published without a request behind it, so the reason is worth stating.
 * §1.5's discovery channel has no authentication, so the one artefact it serves has to be published
 * by something that needs no token; and §5.2 requires the published gateway to match the running
 * server, because the gateway body <em>is</em> the protocol — a stale one makes every client speak
 * the wrong dialect.
 *
 * <p><strong>This is the one place that submits and publishes in the same breath, and ADR 0031
 * records why.</strong> Publishing is otherwise a deliberate act a person performs in a browser,
 * because it changes what every consumer gets. Nobody can perform it for the gateway: it has to be
 * live before anyone has logged in, and {@code GET /gateway/SKILL.md} is what a machine reads to
 * find out where to log in at all. Submitting without publishing would leave it a draft, invisible
 * to the very channel that exists to serve it.
 *
 * <p>It goes through the ordinary submit path rather than writing rows directly. That means the
 * gateway obeys the same rules as any other skill (a frontmatter with no description is refused
 * here too), and its content is stored and digested by exactly one code path. Because submitting
 * identical content is idempotent (ADR 0005), running this on every start inserts nothing once the
 * content has settled — which is what lets §5.2's "the gateway must be updatable" hold without any
 * version bookkeeping of our own.
 *
 * <p>The same idempotence is what makes a <em>reverted</em> source work: the content that was
 * published three commits ago already exists as a version, so submitting it again adds nothing and
 * the publish that follows moves the pointer back to it. That is rollback, and the gateway is the
 * reason v1 has it at all.
 *
 * <p>Both steps are in this method's transaction, so a submit that cannot be published leaves
 * nothing behind — not a version, and not a blob reference.
 */
@Component
public class PublishGatewaySkillUseCase {

    /**
     * The user the gateway skill is attributed to: the {@code skillmaster} account seeded by
     * {@code V2__seed_owner_and_namespaces.sql}, which owns the reserved namespace.
     *
     * <p>Attribution is not incidental — every version records who submitted it, and the honest
     * answer here is the system account rather than whichever operator started the process.
     */
    private static final String SYSTEM_USER_ID = "01M3HTG7GDZGTDME9B136ZVAW4";

    private final SubmitSkillUseCase submitSkill;
    private final NamespaceService namespaces;
    private final SkillVersionService versions;

    public PublishGatewaySkillUseCase(SubmitSkillUseCase submitSkill, NamespaceService namespaces,
            SkillVersionService versions) {
        this.submitSkill = submitSkill;
        this.namespaces = namespaces;
        this.versions = versions;
    }

    /**
     * @param publicBaseUrl the address clients reach this deployment at, written into the source's
     *                      placeholder
     */
    @Transactional
    public SubmittedSkill publish(String publicBaseUrl) {
        // Resolved here rather than read back from the submission, because publishing is addressed
        // by name within a namespace and this is the namespace the system account owns. It is the
        // same call the submit path makes, so the two cannot disagree about where it lands.
        Namespace namespace = namespaces.personalNamespaceOf(SYSTEM_USER_ID);

        SubmittedSkill submitted;
        try {
            submitted = submitSkill.submit(zipOf(GatewaySource.read(publicBaseUrl)), systemSubject());
        } catch (VersionNameTakenException e) {
            // Reachable only if somebody adds a `version:` to gateway/skillmaster/SKILL.md and then
            // edits the body without bumping it — the ordinary consequence of naming a version at all
            // (ADR 0033). Dormant today: the gateway declares no version, precisely so that its
            // republish-on-every-start cannot be blocked by a forgotten bump.
            //
            // Refusing to start is still right — a server whose own gateway is stale serves the wrong
            // protocol to every client that bootstraps from it — but the failure arrives as a stack
            // trace about a version name, which says nothing about where to fix it. This one does.
            throw new IllegalStateException(
                    "gateway/skillmaster/SKILL.md declares a version its content has already used "
                            + "with different bytes — bump the `version` in that file, or remove it"
                            + " (the gateway is re-published on every start, so this blocks startup)",
                    e);
        }

        versions.publishVersion(namespace.id(), submitted.name(), publishedPin(submitted),
                        new Caller(SYSTEM_USER_ID, namespace.id()))
                .orElseThrow(() -> new IllegalStateException(
                        "the gateway skill vanished between submitting and publishing it"));

        return submitted;
    }

    /**
     * The version just submitted, named the way it can be named.
     *
     * <p>By digest in practice, and that is not a fallback: the gateway declares no {@code version}
     * (ADR 0033) — it is machine-addressed, and {@code metadata.platform_api_version} is what tracks
     * whether a client's copy is stale — so its digest is the only thing that names it. The name
     * branch is here so that adding a version to the source later keeps this working rather than
     * silently publishing by digest while an address says otherwise.
     */
    private static VersionPin publishedPin(SubmittedSkill submitted) {
        return submitted.version() == null
                ? new VersionPin.Digest(submitted.digest())
                : new VersionPin.Named(submitted.version());
    }

    private static AuthenticatedSubject systemSubject() {
        return new AuthenticatedSubject(SYSTEM_USER_ID, Set.of());
    }

    /**
     * Builds the archive the submit path expects.
     *
     * <p>A zip rather than a shortcut into M5's internals: the archive genuinely is that path's
     * input format, and one file is not a special case worth its own entry point.
     *
     * <p>The entry is nested under the skill's name because §1.3 requires a skill's directory to be
     * named after it and M5 enforces that — which also makes this look like every other upload,
     * {@code zip -r x.zip skillmaster/}.
     */
    private static byte[] zipOf(Map<String, byte[]> files) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipArchiveOutputStream out = new ZipArchiveOutputStream(bytes)) {
            for (Map.Entry<String, byte[]> file : files.entrySet()) {
                out.putArchiveEntry(new ZipArchiveEntry(
                        GatewayService.SKILL_NAME + "/" + file.getKey()));
                out.write(file.getValue());
                out.closeArchiveEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException("could not build the gateway archive", e);
        }
        return bytes.toByteArray();
    }
}
