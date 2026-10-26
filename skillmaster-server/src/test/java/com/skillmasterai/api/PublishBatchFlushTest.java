package com.skillmasterai.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.usecase.PublishBatchUseCase;
import com.skillmasterai.usecase.PublishBatchUseCase.Outcome;
import com.skillmasterai.usecase.PublishBatchUseCase.Target;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;

/**
 * Each NDJSON line is pushed out when it is written, not when the batch ends (v18, 2026-10-26).
 *
 * <p><strong>The push has to be the response's, not the stream's.</strong> The stream Spring hands a
 * {@code StreamingResponseBody} is a non-flushing wrapper by default, so `out.flush()` pushed
 * nothing and every line waited for the end of the batch. The root cause, the measurement and the
 * decision are in {@code docs/versions/v18-batch-publish/iterations/0001}; what this test pins is the
 * shape: the bytes, and one push per line.
 */
class PublishBatchFlushTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void everyLineIsWrittenAndPushedOutOnItsOwn() throws IOException {
        Counting response = new Counting();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        WebPublishBatchController controller = new WebPublishBatchController(null, MAPPER);

        controller.line(out, response, new WebPublishBatchController.Start("start", 2));
        controller.line(out, response, new WebPublishBatchController.Done("done", 2, 0));

        // Two lines, each closed by its own newline — the newline is what makes the line complete
        // on the wire, so it is asserted rather than left to `lines()`, which would pass either way.
        assertThat(out.toString(StandardCharsets.UTF_8)).isEqualTo(
                "{\"type\":\"start\",\"total\":2}\n{\"type\":\"done\",\"ok\":2,\"failed\":0}\n");
        assertThat(response.pushes)
                .as("one push per line: a batch has to be watchable, not just readable at the end")
                .isEqualTo(2);
    }

    /**
     * **The loop, not only the one line.** Two targets means four lines — `start`, one `row` each,
     * `done` — and the point is that there are four pushes too, **and that they happen as the loop
     * goes rather than after it**.
     *
     * <p>That second half is why the fake reads the push count as it is called: by the time the
     * first publish runs, `start` must already be out (one push), and by the second, the first row
     * as well (two). Gathering the rows and writing them out at the end fails those two numbers even
     * when it happens to push the right total.
     *
     * <p>The use case is answered from a script, which is the only seam the loop needs: `write()`
     * owns the ordering and the pushing, and `publishOne` owning the transaction is the use case's
     * own test's business.
     */
    @Test
    void everyRowIsPushedAsTheLoopReachesIt() throws IOException {
        Counting response = new Counting();
        ScriptedBatch batch = new ScriptedBatch(response, List.of(
                new PublishBatchUseCase.Outcome(true, "dev", "a-skill", "1.0.0", "第一份", null, null,
                        null),
                new PublishBatchUseCase.Outcome(false, "dev", "b-skill", null, "第二份", null,
                        "version_already_exists", "1.0.0 已经有人用过了")));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        WebPublishBatchController controller = new WebPublishBatchController(batch, MAPPER);

        controller.write(out, response, List.of(target("a-skill"), target("b-skill")), null);

        List<String> lines = out.toString(StandardCharsets.UTF_8).lines().toList();
        assertThat(lines).hasSize(4);
        assertThat(lines.get(0)).isEqualTo("{\"type\":\"start\",\"total\":2}");
        assertThat(lines.get(1)).contains("\"type\":\"row\"").contains("\"ok\":true")
                .contains("\"name\":\"a-skill\"");
        assertThat(lines.get(2)).contains("\"type\":\"row\"").contains("\"ok\":false")
                .contains("\"name\":\"b-skill\"");
        assertThat(lines.get(3)).isEqualTo("{\"type\":\"done\",\"ok\":1,\"failed\":1}");
        assertThat(batch.pushesSeenWhilePublishing)
                .as("the start line and then the first row are already out when each publish runs")
                .containsExactly(1, 2);
        assertThat(response.pushes)
                .as("four lines written as the loop went, so four pushes")
                .isEqualTo(4);
    }

    /** A target the controller accepts; the pin only has to parse, nothing resolves it here. */
    private static PublishBatchUseCase.Target target(String name) {
        return new PublishBatchUseCase.Target("dev", name,
                SkillAddress.pinOf("9F2T4HXK", true).orElseThrow(), "9F2T4HXK", null);
    }

    /**
     * The use case, answering from a script. Hand-written rather than mocked, as every other double
     * in this suite is (`support/RecordingSmsSender`): the repository has no mocking framework in
     * use, and one class is cheaper than introducing a second idiom.
     */
    private static final class ScriptedBatch extends PublishBatchUseCase {

        private final Counting response;
        private final ArrayDeque<Outcome> answers;
        /** How many lines had been pushed when each publish ran — the loop's ordering, observed. */
        private final List<Integer> pushesSeenWhilePublishing = new ArrayList<>();

        ScriptedBatch(Counting response, List<Outcome> answers) {
            // Neither collaborator is touched: `publishOne` is the only method `write()` calls, and
            // it is overridden below. The constructor only assigns the two fields.
            super(null, null);
            this.response = response;
            this.answers = new ArrayDeque<>(answers);
        }

        @Override
        public Outcome publishOne(Target target, AuthenticatedSubject subject) {
            pushesSeenWhilePublishing.add(response.pushes);
            return answers.removeFirst();
        }
    }

    /** The flush is the only thing this test needs from a response; the rest is the framework's. */
    private static final class Counting extends MockHttpServletResponse {

        private int pushes;

        @Override
        public void flushBuffer() {
            pushes++;
            super.flushBuffer();
        }
    }
}
