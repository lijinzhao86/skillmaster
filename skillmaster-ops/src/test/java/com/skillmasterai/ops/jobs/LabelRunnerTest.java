package com.skillmasterai.ops.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import com.skillmasterai.ops.labels.LabelClaim;
import com.skillmasterai.ops.ports.LabelSource;
import com.skillmasterai.ops.ports.LabelStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The shared path between import and refresh: when it pushes, when it skips, and what it records
 * when the server refuses.
 */
class LabelRunnerTest {

    private final FakeStore store = new FakeStore();
    private final FakeSource source = new FakeSource();
    private final LabelRunner runner = new LabelRunner(store, source);

    private static LabelRunner.Target target() {
        return new LabelRunner.Target("skill-1", "skillmaster", "pdf-tools", "version-1");
    }

    private static List<LabelSource.FileFact> files() {
        return List.of(new LabelSource.FileFact("SKILL.md", 100, false));
    }

    @Test
    void pushesTheClassifiedSetAndRecordsIt() {
        LabelRunner.Result result = runner.evaluate("run-1", "import", target(),
                "---\nname: x\ndescription: y\n---\n```bash\ndocker run --rm image\n```\n",
                files(), false);

        assertThat(result.result()).isEqualTo("labeled");
        assertThat(source.pushed).containsExactly("runtime:docker");
        assertThat(store.last.result()).isEqualTo("labeled");
        assertThat(store.claims).extracting(LabelClaim::definitionId)
                .containsExactly("runtime:docker");
    }

    @Test
    void aRefusedPushIsRecordedAndNotSkippedNextTime() {
        source.failure = new IllegalStateException("server said 500");

        LabelRunner.Result result = runner.evaluate("run-1", "refresh", target(),
                "---\nname: x\ndescription: y\n---\n```bash\npython run.py\n```\n",
                files(), false);

        assertThat(result.result()).isEqualTo("push_failed");
        assertThat(store.last.result()).isEqualTo("push_failed");
        assertThat(store.claims).extracting(LabelClaim::definitionId)
                .containsExactly("runtime:python");
    }

    @Test
    void anUnchangedSkillIsSkippedEntirely() {
        store.evaluated = true;

        LabelRunner.Result result = runner.evaluate("run-2", "refresh", target(),
                "---\nname: x\ndescription: y\n---\n```bash\npython run.py\n```\n",
                files(), true);

        assertThat(result.result()).isEqualTo("unchanged");
        assertThat(source.pushed).isEmpty();
        assertThat(store.last).isNull();
    }

    @Test
    void aVersionThatIsNotLiveIsDeferredWithoutTouchingTheTable() {
        source.applied = false;

        LabelRunner.Result result = runner.evaluate("run-4", "import", target(),
                "---\nname: x\ndescription: y\n---\n```bash\npython run.py\n```\n",
                files(), false);

        assertThat(result.result()).isEqualTo("deferred");
        assertThat(store.last.result()).isEqualTo("deferred");
        assertThat(store.claims).extracting(LabelClaim::definitionId)
                .containsExactly("runtime:python");
    }

    @Test
    void noClaimsStillReplacesTheSetWithTheEmptyOne() {
        LabelRunner.Result result = runner.evaluate("run-3", "refresh", target(),
                "---\nname: x\ndescription: y\n---\nA skill that ships a compose file.\n",
                List.of(new LabelSource.FileFact("SKILL.md", 100, false),
                        new LabelSource.FileFact("docker-compose.yml", 10, false)),
                false);

        assertThat(result.result()).isEqualTo("labeled");
        assertThat(result.labels()).isZero();
        assertThat(source.pushed).isEmpty();
        assertThat(store.last.labels()).isZero();
    }

    private static final class FakeStore implements LabelStore {
        private boolean evaluated;
        private LabelStore.Evaluation last;
        private List<LabelClaim> claims = new ArrayList<>();

        @Override
        public boolean evaluated(String skillId, String versionId, String ruleVersion) {
            return evaluated;
        }

        @Override
        public void record(String runId, String origin, Evaluation evaluation,
                List<LabelClaim> claims) {
            this.last = evaluation;
            this.claims = new ArrayList<>(claims);
        }

        @Override
        public List<Entry> entries(String runId) {
            return List.of();
        }
    }

    private static final class FakeSource implements LabelSource {
        private List<String> pushed = new ArrayList<>();
        private RuntimeException failure;
        private boolean applied = true;

        @Override
        public Page publicSkills(String cursor, int limit) {
            return new Page(List.of(), null);
        }

        @Override
        public Optional<Content> content(String namespace, String name) {
            return Optional.empty();
        }

        @Override
        public boolean replaceLabels(String namespace, String name, String versionId,
                List<String> definitionIds) {
            if (failure != null) {
                throw failure;
            }
            if (!applied) {
                return false;
            }
            pushed = new ArrayList<>(definitionIds);
            return true;
        }
    }
}
