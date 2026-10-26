package com.skillmasterai.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.ObjectMapper;
import com.skillmasterai.common.ApiError;
import com.skillmasterai.common.ErrorCode;
import com.skillmasterai.common.RequestException;
import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.modules.version.VersionPin;
import com.skillmasterai.usecase.PublishBatchUseCase;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * Publishes a batch of drafts, one row at a time (v18).
 *
 * <p><strong>NDJSON, because the page has to say "this one landed" while the others are still
 * going.</strong> One response would leave the sheet with a single spinner and then every row
 * changing colour at the same instant — the difference between watching a batch and watching a
 * result (TD §接口, PRD 做 #9).
 *
 * <p><strong>The request is judged before the stream opens.</strong> An empty batch, an incomplete
 * target, a duplicate skill and a bad pin are all answered as ordinary 4xx with an error body —
 * once the first line is written the status is already sent, so anything that belongs to the request
 * as a whole has to be decided first.
 *
 * <p><strong>A row that failed is a row's answer, not the end of the stream.</strong> The one case
 * that is not caught is a failure nobody named; that propagates out of the body and cuts the
 * connection, which is honest — the page then shows the rows it received and an incomplete result,
 * rather than a list that quietly claims to be whole.
 */
@RestController
@RequestMapping(path = WebRoutes.BASE)
class WebPublishBatchController {

    private static final MediaType NDJSON = MediaType.parseMediaType("application/x-ndjson");

    private final PublishBatchUseCase batch;
    private final ObjectMapper objectMapper;

    WebPublishBatchController(PublishBatchUseCase batch, ObjectMapper objectMapper) {
        this.batch = batch;
        this.objectMapper = objectMapper;
    }

    @PostMapping(path = WebRoutes.PUBLISH_BATCH, consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = "application/x-ndjson")
    ResponseEntity<StreamingResponseBody> publish(@RequestBody BatchBody body,
            Authentication caller, HttpServletResponse response) {
        List<PublishBatchUseCase.Target> targets = targetsOf(body);
        batch.validate(targets);

        AuthenticatedSubject subject = AuthenticatedSubject.ofSession(caller.getName());
        // Streaming and the session's transaction do not mix: each row opens its own, inside the
        // use case. Nothing here holds one open across the stream.
        StreamingResponseBody stream = out -> write(out, response, targets, subject);
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Accel-Buffering", "no");
        return ResponseEntity.ok().contentType(NDJSON).body(stream);
    }

    void write(OutputStream out, HttpServletResponse response,
            List<PublishBatchUseCase.Target> targets, AuthenticatedSubject subject)
            throws IOException {
        line(out, response, new Start("start", targets.size()));
        int ok = 0;
        int failed = 0;
        for (PublishBatchUseCase.Target target : targets) {
            PublishBatchUseCase.Outcome outcome = batch.publishOne(target, subject);
            if (outcome.ok()) {
                ok++;
            } else {
                failed++;
            }
            line(out, response, new Row("row", outcome.ok(), outcome.namespace(), outcome.name(),
                    outcome.version(), outcome.draftName(), outcome.predicted(), outcome.code(),
                    outcome.message()));
        }
        line(out, response, new Done("done", ok, failed));
    }

    /**
     * Writes one line and pushes it out, **by flushing the response rather than the stream**.
     *
     * <p>{@code out.flush()} here is a no-op on Spring Framework 7: the stream handed to a
     * {@link StreamingResponseBody} is {@code StreamUtils.nonFlushing(response.getOutputStream())}
     * unless {@code spring.http.response.flush.enabled} — a {@code SpringProperties} flag, false by
     * default — is set, and {@code NonFlushingOutputStream.flush()} returns without doing anything.
     * Every line then sat in the container's buffer until the handler finished, so a batch of ten
     * arrived as one chunk at the end: the sheet showed the first row spinning for the whole run and
     * then every row turning green at the same instant (2026-10-26). {@code flushBuffer()} is the
     * servlet API's own push and depends on no flag.
     */
    void line(OutputStream out, HttpServletResponse response, Object payload) throws IOException {
        try {
            out.write(objectMapper.writeValueAsString(payload).getBytes(StandardCharsets.UTF_8));
            out.write('\n');
            response.flushBuffer();
        } catch (IOException e) {
            // The client hung up. Rows already written committed in their own transactions; the
            // rest simply never happen (TD §接口: 客户端断了就停).
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Parses the pins here rather than in the use case: {@code SkillAddress} is this plane's
     * grammar, and the use case receives a pin it can act on or a 400 naming the field.
     */
    private static List<PublishBatchUseCase.Target> targetsOf(BatchBody body) {
        if (body == null || body.targets() == null || body.targets().isEmpty()) {
            throw new RequestException(ErrorCode.INVALID_REQUEST,
                    "a batch needs at least one target");
        }
        List<PublishBatchUseCase.Target> targets = new ArrayList<>(body.targets().size());
        for (TargetBody target : body.targets()) {
            // **A bad target is a 400, not a server fault.** `pinOf` takes a string that a path
            // segment always supplies; a request body can leave it out, and passing the null through
            // turned a 400 into a 500 that blamed us for the caller's mistake. A `null` element is
            // the same mistake one step earlier — `{"targets":[null]}` deserialises to a list with a
            // null in it, and every read below would have thrown before the guard ran.
            if (target == null) {
                throw new RequestException(ErrorCode.INVALID_REQUEST,
                        "every target needs a version",
                        List.of(new ApiError.Detail("targets", "invalid_version")));
            }
            if (target.version() == null || target.version().isBlank()) {
                throw new RequestException(ErrorCode.INVALID_REQUEST,
                        "every target needs a version",
                        List.of(new ApiError.Detail("targets", "invalid_version")));
            }
            VersionPin pin = SkillAddress.pinOf(target.version(), true)
                    .orElseThrow(() -> new RequestException(ErrorCode.INVALID_REQUEST,
                            "version '" + target.version() + "' names no draft",
                            List.of(new ApiError.Detail("targets", "invalid_version"))));
            targets.add(new PublishBatchUseCase.Target(target.namespace(), target.name(), pin,
                    target.version(), target.predicted()));
        }
        return targets;
    }

    record BatchBody(List<TargetBody> targets) {
    }

    record TargetBody(String namespace, String name, String version, String predicted) {
    }

    /** Row shapes carry only what applies: a success has no code, a failure has no version. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Start(String type, int total) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Row(String type, boolean ok, String namespace, String name, String version,
            String draftName, String predicted, String code, String message) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Done(String type, int ok, int failed) {
    }
}
