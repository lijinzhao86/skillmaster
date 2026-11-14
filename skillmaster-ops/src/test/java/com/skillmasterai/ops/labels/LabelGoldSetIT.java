package com.skillmasterai.ops.labels;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * v72 test-plan T13: run the rules over a facts snapshot of the real library and write review
 * files. It asserts nothing — the judgement is human, and the numbers are computed from the
 * reviewed file, not from the rules agreeing with themselves.
 *
 * <p><strong>Why the snapshot is a directory of JSON.</strong> The commands that read the library
 * are the CLI's, and the rules are Java's; keeping the two apart means the sample can be rebuilt
 * without touching this test. {@code artifacts/label-gold/} is ignored by git.
 */
class LabelGoldSetIT {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Broad, recall-oriented patterns. A hit the rules miss is a candidate false negative. */
    private static final Map<String, Pattern> BROAD = new LinkedHashMap<>();

    static {
        BROAD.put("runtime:python", Pattern.compile("(?i)\\bpython|requirements\\.txt|\\.py\\b"));
        BROAD.put("runtime:node", Pattern.compile("(?i)\\bnode|\\bnpm|package\\.json|\\.js\\b|\\.ts\\b"));
        BROAD.put("runtime:java", Pattern.compile("(?i)\\bjava\\b|pom\\.xml|\\.java\\b|maven|gradle"));
        BROAD.put("runtime:go", Pattern.compile("(?i)\\bgo (run|build|test)|go\\.mod|\\.go\\b"));
        BROAD.put("runtime:rust", Pattern.compile("(?i)cargo|\\.rs\\b"));
        BROAD.put("runtime:dotnet", Pattern.compile("(?i)dotnet|\\.csproj|\\.cs\\b"));
        BROAD.put("runtime:ruby", Pattern.compile("(?i)gemfile|\\.rb\\b|\\bruby\\b"));
        BROAD.put("runtime:php", Pattern.compile("(?i)composer\\.json|\\.php\\b|\\bphp\\b"));
        BROAD.put("runtime:c-cpp", Pattern.compile("(?i)makefile|cmakelists|\\.c\\b|\\.cpp\\b|gcc|clang"));
        BROAD.put("runtime:shell", Pattern.compile("(?i)\\.sh\\b|\\bbash\\b|\\bzsh\\b"));
        BROAD.put("runtime:docker", Pattern.compile("(?i)docker"));
        BROAD.put("runtime:gpu", Pattern.compile("(?i)gpu|cuda|nvidia"));
        BROAD.put("runtime:database", Pattern.compile("(?i)database|postgres|mysql|mongo|redis|\\bsql\\b|psql"));
        BROAD.put("runtime:browser", Pattern.compile("(?i)browser|playwright|puppeteer|selenium"));
        BROAD.put("runtime:api-key", Pattern.compile("(?i)api[ _-]?key|token|secret"));
        BROAD.put("runtime:online-service", Pattern.compile("(?i)internet|online service|network access|联网|在线服务"));
        BROAD.put("runtime:account", Pattern.compile("(?i)account|login|登录|账号"));
        BROAD.put("runtime:macos", Pattern.compile("(?i)macos|mac os|os x"));
        BROAD.put("runtime:windows", Pattern.compile("(?i)windows"));
        BROAD.put("runtime:linux", Pattern.compile("(?i)linux"));
    }

    @Test
    void writeTheReviewFiles() throws IOException {
        Path input = Path.of(System.getenv().getOrDefault("LABEL_GOLD_INPUT",
                "../artifacts/label-gold/library"));
        assumeTrue(Files.isDirectory(input), "no facts snapshot at " + input);
        List<Path> files;
        try (var stream = Files.list(input)) {
            files = stream.filter(path -> path.toString().endsWith(".json")).sorted().toList();
        }
        assumeTrue(!files.isEmpty(), "the facts snapshot is empty");

        Path output = Path.of(System.getenv().getOrDefault("LABEL_GOLD_OUTPUT",
                "../artifacts/label-gold"));
        Files.createDirectories(output);

        RuleLabeler labeler = new RuleLabeler();
        List<String> hits = new ArrayList<>();
        List<String> review = new ArrayList<>();
        List<String> discrepancies = new ArrayList<>();
        hits.add("address,definition_id,rule_id,evidence_kind,evidence_file,evidence_line,evidence_quote");
        discrepancies.add("address,definition_id,broad_hit,rule_hit,matched_lines");

        for (Path file : files) {
            JsonNode root = JSON.readTree(Files.readString(file));
            String address = root.path("address").asText("");
            SkillFacts facts = facts(root);
            List<LabelClaim> claims = labeler.classify(facts);
            List<String> ids = claims.stream().map(LabelClaim::definitionId).sorted().toList();

            for (LabelClaim claim : claims) {
                Evidence evidence = claim.evidence();
                hits.add(String.join(",", csv(address), csv(claim.definitionId()),
                        csv(claim.ruleId()), csv(evidence.kind()), csv(evidence.file()),
                        String.valueOf(evidence.line()), csv(evidence.quote())));
            }

            review.add("## " + address);
            review.add("");
            review.add("规则：" + (ids.isEmpty() ? "（无）" : String.join(" ", ids)));
            review.add("");
            for (LabelClaim claim : claims) {
                review.add("- `" + claim.definitionId() + "` ← " + claim.evidence().file() + ":"
                        + claim.evidence().line() + " `" + claim.evidence().quote() + "`");
            }
            review.add("");
            review.add("```");
            review.add(bodyHead(facts.skillMd(), 80));
            review.add("```");
            review.add("");

            String corpus = facts.skillMd() + "\n" + facts.files().stream()
                    .map(SkillFacts.FileFact::relpath).collect(Collectors.joining("\n"));
            for (Map.Entry<String, Pattern> entry : BROAD.entrySet()) {
                boolean broad = entry.getValue().matcher(corpus).find();
                boolean rule = ids.contains(entry.getKey());
                if (broad != rule) {
                    discrepancies.add(String.join(",", csv(address), csv(entry.getKey()),
                            String.valueOf(broad), String.valueOf(rule),
                            csv(matchedLines(entry.getValue(), corpus))));
                }
            }
        }

        Files.write(output.resolve("hits.csv"), hits);
        Files.write(output.resolve("review.md"), review);
        Files.write(output.resolve("discrepancies.csv"), discrepancies);
    }

    private static SkillFacts facts(JsonNode root) {
        List<SkillFacts.FileFact> files = new ArrayList<>();
        for (JsonNode file : root.path("files")) {
            files.add(new SkillFacts.FileFact(file.path("relpath").asText(""),
                    file.path("size").asLong(0), file.path("binary").asBoolean(false)));
        }
        return new SkillFacts(root.path("skill_md").asText(""), files);
    }

    private static String bodyHead(String markdown, int lines) {
        String[] all = markdown.split("\n", -1);
        StringBuilder body = new StringBuilder();
        boolean inFrontmatter = all.length > 0 && all[0].strip().equals("---");
        int index = 0;
        if (inFrontmatter) {
            for (index = 1; index < all.length; index++) {
                if (all[index].strip().equals("---")) {
                    index++;
                    break;
                }
            }
        }
        int end = Math.min(all.length, index + lines);
        for (int i = index; i < end; i++) {
            body.append(all[i]).append('\n');
        }
        return body.toString();
    }

    private static String matchedLines(Pattern pattern, String corpus) {
        List<String> found = new ArrayList<>();
        for (String line : corpus.split("\n")) {
            if (pattern.matcher(line).find()) {
                found.add(line.strip());
                if (found.size() == 3) {
                    break;
                }
            }
        }
        return String.join(" | ", found);
    }

    private static String csv(String value) {
        String text = value == null ? "" : value;
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }
}
