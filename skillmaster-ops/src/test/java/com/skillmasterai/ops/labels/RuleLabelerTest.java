package com.skillmasterai.ops.labels;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * The trigger table from {@code docs/versions/v72-fixed-labeling/technical-design.md} §规则表,
 * one fixture per value.
 *
 * <p>Each positive fixture contains exactly one strong signal, so a failure names the rule that
 * broke rather than a fixture that happens to trip several.
 */
class RuleLabelerTest {

    private final RuleLabeler labeler = new RuleLabeler();

    @Test
    void everyNonDefaultValueHasARule() {
        List<SkillFacts> fixtures = List.of(
                facts("", "requirements.txt"),
                facts("", "package.json"),
                facts("", "pom.xml"),
                facts("", "go.mod"),
                facts("", "Cargo.toml"),
                facts("", "app.csproj"),
                facts("", "Gemfile"),
                facts("", "composer.json"),
                facts("", "Makefile"),
                facts("", "run.sh"),
                facts("compatibility: macOS only\n"),
                facts("compatibility: Windows only\n"),
                facts("compatibility: Linux only\n"),
                facts("", "command: npx playwright test"),
                facts("", "command: docker run --rm image"),
                facts("", "command: nvidia-smi"),
                facts("", "command: psql postgres://localhost/db"),
                facts("allowed-tools: [WebFetch]\n"),
                facts("", "command: sudo ./install.sh"),
                facts("", "command: export OPENAI_API_KEY=sk-test"),
                facts("", "command: gh auth login"));

        Set<String> found = fixtures.stream()
                .flatMap(fixture -> labeler.classify(fixture).stream())
                .map(LabelClaim::definitionId)
                .collect(Collectors.toSet());

        assertThat(found).contains("runtime:python", "runtime:node", "runtime:java",
                "runtime:go", "runtime:rust", "runtime:dotnet", "runtime:ruby", "runtime:php",
                "runtime:c-cpp", "runtime:shell", "runtime:macos", "runtime:windows",
                "runtime:linux", "runtime:browser", "runtime:docker", "runtime:gpu",
                "runtime:database", "runtime:online-service", "runtime:system-permission",
                "runtime:api-key", "runtime:account");
    }

    @Test
    void absentEvidenceWritesNothingNotEverything() {
        assertThat(ids(facts("compatibility: macOS, Linux, Windows\n")))
                .doesNotContain("runtime:macos", "runtime:linux", "runtime:windows");
        assertThat(ids(facts("", "text: Works on macOS and Linux.")))
                .doesNotContain("runtime:macos", "runtime:linux");
    }

    @Test
    void promptOnlyWhenThereIsNoExecutableContent() {
        assertThat(ids(facts(""))).containsExactly("runtime:prompt-only");
        assertThat(ids(facts("", "Dockerfile"))).isEmpty();
        assertThat(ids(facts("", "docker-compose.yml"))).isEmpty();
    }

    @Test
    void proseMentionsAreNotCommands() {
        assertThat(ids(facts("", "text: This skill writes a Dockerfile and explains SQL. "
                + "It talks about PyTorch on a GPU.")))
                .doesNotContain("runtime:docker", "runtime:database", "runtime:gpu");
    }

    @Test
    void evidenceIsFindableAndMutatingItFailsTheCheck() {
        SkillFacts fixture = facts("", "command: python scripts/run.py");
        LabelClaim claim = labeler.classify(fixture).stream()
                .filter(candidate -> candidate.definitionId().equals("runtime:python"))
                .findFirst().orElseThrow();

        assertThat(EvidenceCheck.verify(fixture, claim)).isTrue();
        assertThat(EvidenceCheck.verify(fixture,
                new LabelClaim(claim.definitionId(), claim.ruleId(),
                        new Evidence(claim.evidence().kind(), claim.evidence().file(),
                                claim.evidence().line(), claim.evidence().quote() + "x"))))
                .isFalse();
        assertThat(EvidenceCheck.verify(fixture,
                new LabelClaim(claim.definitionId(), claim.ruleId(),
                        new Evidence(Evidence.COMMAND, "OTHER.md", claim.evidence().line(),
                                claim.evidence().quote()))))
                .isFalse();
        assertThat(EvidenceCheck.verify(fixture,
                new LabelClaim(claim.definitionId(), claim.ruleId(),
                        new Evidence(Evidence.COMMAND, "SKILL.md", 999,
                                claim.evidence().quote()))))
                .isFalse();
    }

    @Test
    void aDollarPromptAndAnInlineSpanKeepTheWholeLineAsEvidence() {
        SkillFacts dollar = facts("", "text: $ docker run --rm image");
        assertThat(ids(dollar)).contains("runtime:docker");
        assertThat(labeler.classify(dollar).getFirst().evidence().quote())
                .isEqualTo("$ docker run --rm image");

        SkillFacts inline = facts("", "text: Run `python scripts/run.py` first.");
        assertThat(ids(inline)).contains("runtime:python");
        assertThat(labeler.classify(inline).getFirst().evidence().quote())
                .isEqualTo("Run `python scripts/run.py` first.");
    }

    @Test
    void multiSelectKeepsValuesThatReallyApply() {
        SkillFacts fixture = facts("", "command: docker run --gpus all image",
                "command: python train.py");
        assertThat(ids(fixture)).contains("runtime:docker", "runtime:gpu", "runtime:python");
    }

    @Test
    void theThreeAmbiguousValuesFollowTheirOwnRule() {
        // orchestra-training-llms-megatron: docker run --gpus all
        assertThat(ids(facts("", "command: docker run --gpus all image")))
                .contains("runtime:docker", "runtime:gpu");

        // jeffallan-devops-engineer: a Dockerfile and pipeline examples, but no docker command
        assertThat(ids(facts("", "Dockerfile", "command: make build")))
                .doesNotContain("runtime:docker");

        // jeffallan-sql-pro: the Verify step runs EXPLAIN ANALYZE
        assertThat(ids(facts("", "command: EXPLAIN ANALYZE SELECT 1")))
                .contains("runtime:database");

        // database-lookup: a public data API over the internet; not a database service
        assertThat(ids(facts("", "text: Requires internet access.",
                "command: export DATA_API_KEY=test")))
                .contains("runtime:online-service", "runtime:api-key")
                .doesNotContain("runtime:database");

        // orchestra-modal-serverless-gpu: cloud GPU is an online service, not a local GPU
        assertThat(ids(facts("", "command: modal run app.py")))
                .contains("runtime:online-service")
                .doesNotContain("runtime:gpu");
    }

    @Test
    void osIsOnlyWrittenWhenTheSkillRestrictsToIt() {
        assertThat(ids(facts("compatibility: macOS only\n"))).containsExactly("runtime:macos");
        assertThat(ids(facts("compatibility: '仅 macOS 与 Linux'\n")))
                .containsExactlyInAnyOrder("runtime:macos", "runtime:linux");
        assertThat(ids(facts("", "text: ## Requirements", "text: - macOS", "text: - Linux")))
                .doesNotContain("runtime:macos", "runtime:linux");
    }

    @Test
    void negationInRequirementsDoesNotWriteTheValue() {
        assertThat(ids(facts("", "text: ## Requirements", "text: - No Docker required")))
                .doesNotContain("runtime:docker");
    }

    @Test
    void refreshFactsWithoutShebangStillFindTheLanguage() {
        assertThat(ids(facts("", "text: Run the bundled script."))).doesNotContain(
                "runtime:python");
        assertThat(ids(facts("", "scripts/run.py"))).contains("runtime:python");
    }

    @Test
    void everyCommandInAPipelineIsRead() {
        // company-analysis: curl … | python3 -c … — the executable is the second segment
        assertThat(ids(facts("", "command: curl -s https://example.test/data.json "
                + "| python3 -c 'import sys,json; print(1)'")))
                .contains("runtime:python");
        // API_KEY=… python3 run.py — the assignment is not the executable
        assertThat(ids(facts("", "command: API_KEY=test python3 scripts/run.py")))
                .contains("runtime:python");
    }

    @Test
    void aBrowserScriptOnTheCommandLineCountsAsABrowser() {
        // web-tools: node /opt/data/scripts/browser.js
        assertThat(ids(facts("", "command: node /opt/data/scripts/browser.js goto URL")))
                .contains("runtime:browser");
        // lark-charts: a .js file inside an HTML example is not node or a browser
        assertThat(ids(facts("", "text: Use chart.js in the page.", "lark-charts/chart.js")))
                .doesNotContain("runtime:browser");
    }

    @Test
    void declaredCredentialsAndPrerequisitesAreRead() {
        // byted-web-search / web-tools: metadata.requires.credentials
        assertThat(ids(facts("metadata:\n  requires:\n    credentials:\n"
                + "      - SERPER_API_KEY\n      - HTTPS_PROXY\n")))
                .contains("runtime:api-key");
        // xurl: explicit platform list means those are the supported systems
        assertThat(ids(facts("platforms: [linux, macos]\n")))
                .containsExactlyInAnyOrder("runtime:linux", "runtime:macos");
        assertThat(ids(facts("platforms: [linux, macos, windows]\n")))
                .doesNotContain("runtime:linux", "runtime:macos", "runtime:windows");
        // prerequisites.commands states a runtime directly
        assertThat(ids(facts("prerequisites:\n  commands:\n    - python3\n")))
                .contains("runtime:python");
        assertThat(ids(facts("prerequisites:\n  commands:\n    - xurl\n")))
                .doesNotContain("runtime:python", "runtime:node", "runtime:shell");
    }

    @Test
    void aDescriptionThatSaysItSearchesOnlineIsAnOnlineService() {
        SkillFacts fixture = new SkillFacts(
                "---\nname: test\ndescription: 基于联网搜索 API 的检索技能\n---\nbody\n",
                List.of(new SkillFacts.FileFact("SKILL.md", 60, false)));
        assertThat(ids(fixture)).contains("runtime:online-service");
    }

    @Test
    void anEnvKeyInsideAFenceIsAnApiKeyEvenWithoutACommand() {
        // byted-web-search: WEB_SEARCH_API_KEY named in a fence, not on an executable line
        assertThat(ids(facts("", "text: ```env", "text: WEB_SEARCH_API_KEY=xxx",
                "text: ```"))).contains("runtime:api-key");
    }

    @Test
    void aPayloadFieldNamedTokenIsNotAnApiKey() {
        // bilibili: refresh_token is a JSON field; lark-*: doc_token / page_token / tenant_access_token
        assertThat(ids(facts("", "text: ```python",
                "text: ac = d.get(\"refresh_token\", \"\")", "text: ```")))
                .doesNotContain("runtime:api-key");
        assertThat(ids(facts("", "command: lark-cli drive +get --file-token <doc_token>")))
                .doesNotContain("runtime:api-key");
        assertThat(ids(facts("", "command: export SERPER_API_KEY=test")))
                .contains("runtime:api-key");
    }

    @Test
    void aCommandThatCallsARemoteServiceNeedsOne() {
        // the lark-* family calls Feishu through lark-cli; arxiv calls its REST API with curl
        assertThat(ids(facts("", "command: lark-cli calendar +list-events --as user")))
                .contains("runtime:online-service");
        assertThat(ids(facts("", "command: curl -s 'https://export.arxiv.org/api/query?q=x'")))
                .contains("runtime:online-service");
        assertThat(ids(facts("", "command: open http://localhost:8080/health")))
                .doesNotContain("runtime:online-service");
        // reading one's own instructions through the CLI is not a runtime requirement
        assertThat(ids(facts("", "text: Read it with `skmr skill read ns/name SKILL.md`.")))
                .doesNotContain("runtime:online-service");
    }

    @Test
    void aLoginCommandMeansAnAccount() {
        assertThat(ids(facts("", "command: lark-cli auth login --domain calendar")))
                .contains("runtime:account");
        // lark-*: acting as the user needs a logged-in Feishu account
        assertThat(ids(facts("", "command: lark-cli calendar +list-events --as user")))
                .contains("runtime:account");
        // --as bot is the app's identity, not the reader's account
        assertThat(ids(facts("", "command: lark-cli im +messages-send --as bot")))
                .doesNotContain("runtime:account");
    }

    @Test
    void declaredBinsAreRequirements() {
        // every lark-* skill declares metadata.requires.bins: [lark-cli]
        assertThat(ids(facts("metadata:\n  requires:\n    bins: [\"lark-cli\"]\n")))
                .contains("runtime:online-service")
                .doesNotContain("runtime:prompt-only");
        assertThat(ids(facts("metadata:\n  requires:\n    bins: [\"python3\"]\n")))
                .contains("runtime:python");
    }

    @Test
    void aRouterWithNestedInstructionsIsNotAPurePrompt() {
        assertThat(ids(facts("", "references/guide.md"))).doesNotContain("runtime:prompt-only");
        // life: every capability comes from the `tools` skill it points at
        assertThat(ids(facts("", "text: 读 `skill_view(name=\"tools\", "
                + "file_path=\"amap/README.md\")`。")))
                .doesNotContain("runtime:prompt-only");
    }

    @Test
    void anAssetScriptOrAJavaScriptFenceIsNotANodeRuntime() {
        // lark-apps: starter-components/*.jsx and deck-stage.js are browser view assets
        assertThat(ids(facts("", "starter-components/chart.jsx",
                "starter-components/deck-stage.js"))).doesNotContain("runtime:node");
        // stock-chart: an ECharts option object in a js fence
        assertThat(ids(facts("", "text: ```javascript", "text: var ohlc = [[1,2,3,4]];",
                "text: ```"))).doesNotContain("runtime:node");
        assertThat(ids(facts("", "scripts/main.ts"))).contains("runtime:node");
    }

    @Test
    void aCommandQuotedInACitationIsNotAStep() {
        // darwin-skill: a reference bullet links to another project's `pip install skillopt`
        assertThat(ids(facts("", "text: - **SkillOpt**: code "
                + "[github.com/x/y](https://github.com/x/y) (`pip install skillopt`).")))
                .doesNotContain("runtime:python");
    }

    @Test
    void anUntaggedFenceFullOfProseIsNotCommands() {
        // byted-web-search: an untagged fence holds the text the agent shows the user
        assertThat(ids(facts("", "text: ```", "text: 新用户直接开通在[官网](https://example.test)",
                "text: ```"))).doesNotContain("runtime:online-service");
    }

    @Test
    void aBareExecutableInProseIsASuggestionNotAStep() {
        // arxiv: "Parse with grep/sed or pipe through `python3` for clean output"
        assertThat(ids(facts("", "text: Parse with `grep` or pipe through `python3`.")))
                .doesNotContain("runtime:python");
        assertThat(ids(facts("", "text: Run `python3 scripts/run.py` first.")))
                .contains("runtime:python");
    }

    private List<String> ids(SkillFacts facts) {
        return labeler.classify(facts).stream().map(LabelClaim::definitionId).sorted().toList();
    }

    /**
     * @param frontmatter YAML lines placed between the delimiters (empty for none)
     * @param args        {@code command: …} is a line in a shell fence, {@code text: …} is a body
     *                    line, anything else is a relpath in the manifest
     */
    private static SkillFacts facts(String frontmatter, String... args) {
        List<SkillFacts.FileFact> files = new ArrayList<>();
        files.add(new SkillFacts.FileFact("SKILL.md", 100, false));
        List<String> body = new ArrayList<>();
        for (String arg : args) {
            if (arg.startsWith("command: ")) {
                body.add("```bash");
                body.add(arg.substring("command: ".length()));
                body.add("```");
            } else if (arg.startsWith("text: ")) {
                body.add(arg.substring("text: ".length()));
            } else if (!arg.isEmpty()) {
                files.add(new SkillFacts.FileFact(arg, 10, false));
            }
        }
        if (body.isEmpty()) {
            body.add("A skill described in prose.");
        }
        String markdown = frontmatter.isEmpty() ? String.join("\n", body) + "\n"
                : "---\nname: test\ndescription: test\n" + frontmatter + "---\n"
                        + String.join("\n", body) + "\n";
        return new SkillFacts(markdown, files);
    }
}
