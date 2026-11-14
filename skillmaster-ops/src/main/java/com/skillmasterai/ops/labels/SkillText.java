package com.skillmasterai.ops.labels;

import com.skillmasterai.ops.imports.FrontmatterParser;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One {@code SKILL.md} seen the way the rules need to see it: lines, fences, commands, the
 * frontmatter, and the lines that look like requirements.
 *
 * <p><strong>What counts as a command, and what deliberately does not.</strong> A line is a command
 * only inside a shell-ish fence, after a {@code $} prompt, or as an inline code span that starts
 * with something executable. A sentence that merely mentions Docker is not one — that distinction
 * is the whole precision story of this version, and it lives here rather than in the rules.
 */
final class SkillText {

    /** One command-shaped line; {@code quote} is the line as written. */
    record Command(int line, String text, String quote) {
    }

    /** A requirement line, with its 1-based position. */
    record Line(int line, String text, String quote) {
    }

    /** A fenced block's opening line and its language tag (empty when untagged). */
    record Fence(int line, String info, String quote) {
    }

    private static final Pattern FENCE_OPEN = Pattern.compile("^\\s*(`{3,}|~{3,})(.*)$");
    private static final Pattern INLINE = Pattern.compile("`([^`\\n]{1,200})`");
    private static final Pattern REQUIREMENT_HEADING = Pattern.compile(
            "(?i)^#{1,6}\\s*(requirements?|prerequisites?|dependencies|before you (start|begin)"
                    + "|安装|前置|依赖|要求)");
    private static final Pattern REQUIREMENT_START = Pattern.compile(
            "(?i)^(requires?|needs?|prerequisites?|前提|前置|需要|要求)");
    private static final Pattern FRONTMATTER_KEY = Pattern.compile("^([A-Za-z0-9_.-]+)\\s*:");
    /** A line that cites an external source; a command in it is being quoted, not run. */
    private static final Pattern EXTERNAL_LINK = Pattern.compile("\\]\\(https?://");
    private static final Pattern DELEGATION = Pattern.compile(
            "skill_view\\s*\\(|\\bskmr\\s+skill\\s+(read|invoke)\\b");

    private static final Set<String> SHELL_FENCES = Set.of("", "bash", "sh", "shell", "zsh",
            "console", "terminal", "powershell", "ps1", "bat", "cmd");

    private static final Set<String> KNOWN_EXECUTABLES = Set.of(
            "pip", "pip3", "pipx", "uv", "poetry", "conda",
            "node", "npm", "npx", "pnpm", "yarn", "bun",
            "java", "mvn", "mvnw", "gradle", "gradlew",
            "go", "cargo", "dotnet", "ruby", "bundle", "gem", "php", "composer",
            "gcc", "g++", "clang", "clang++", "make", "cmake", "ninja",
            "bash", "zsh", "docker", "docker-compose",
            "psql", "mysql", "mariadb", "mongosh", "redis-cli", "sqlcmd",
            "flyway", "liquibase", "alembic",
            "playwright", "puppeteer", "selenium", "chromedriver",
            "sudo", "modal", "runpod", "replicate",
            "gh", "az", "aws", "gcloud", "vercel", "netlify",
            "lark-cli", "skmr", "curl", "wget", "xurl");

    private final List<String> lines;
    private final Map<String, Object> frontmatter;
    private final Map<String, Integer> frontmatterLines;
    private final List<Command> commands = new ArrayList<>();
    private final List<Line> requirementLines = new ArrayList<>();
    private final List<Line> codeLines = new ArrayList<>();
    private final Set<String> fenceLanguages = new LinkedHashSet<>();
    private final List<Fence> fences = new ArrayList<>();

    private SkillText(String skillMd) {
        this.lines = List.of(skillMd.split("\n", -1)).stream()
                .map(line -> line.endsWith("\r") ? line.substring(0, line.length() - 1) : line)
                .toList();
        Map<String, Object> parsed = Map.of();
        try {
            parsed = FrontmatterParser.parse(skillMd.getBytes(StandardCharsets.UTF_8));
        } catch (RuntimeException unreadable) {
            // The labeler must not fail a run because a published file's frontmatter is odd; the
            // facts that need it simply contribute nothing.
        }
        this.frontmatter = parsed;
        this.frontmatterLines = new LinkedHashMap<>();
        int close = frontmatterClose();
        this.frontmatterLines.putAll(keyLines(close));
        scanBody(close + 1);
    }

    static SkillText of(String skillMd) {
        return new SkillText(skillMd);
    }

    Map<String, Object> frontmatter() {
        return frontmatter;
    }

    /** A frontmatter value as text; a list is joined with {@code ", "}. */
    String text(String key) {
        Object value = frontmatter.get(key);
        if (value == null) {
            return "";
        }
        if (value instanceof List<?> list) {
            return String.join(", ", list.stream().map(String::valueOf).toList());
        }
        return String.valueOf(value);
    }

    Integer lineOf(String key) {
        return frontmatterLines.get(key);
    }

    List<Command> commands() {
        return List.copyOf(commands);
    }

    /**
     * Every line inside any fence, plus every inline code span. Used by the rules that must see a
     * code-shaped mention but not prose — an environment variable named in prose is a mention, the
     * same name in a fence is a requirement.
     */
    List<Line> codeLines() {
        return List.copyOf(codeLines);
    }

    /** A list-valued frontmatter field, looked up along a path; empty when absent. */
    List<String> listAt(String... path) {
        Object value = frontmatter;
        for (String key : path) {
            if (!(value instanceof Map<?, ?> map)) {
                return List.of();
            }
            value = map.get(key);
        }
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        if (value instanceof String single) {
            return List.of(single);
        }
        return List.of();
    }

    /** Top-level {@code platforms}, the author's explicit supported-OS list. */
    List<String> platforms() {
        return listAt("platforms");
    }

    /** {@code prerequisites.commands} when nested, or a flat {@code prerequisites} list. */
    List<String> prerequisites() {
        List<String> nested = listAt("prerequisites", "commands");
        return nested.isEmpty() ? listAt("prerequisites") : nested;
    }

    /** {@code metadata.requires.credentials}: named credentials the skill says it needs. */
    List<String> credentials() {
        return listAt("metadata", "requires", "credentials");
    }

    /** {@code metadata.requires.bins}: executables the skill declares it runs. */
    List<String> bins() {
        return listAt("metadata", "requires", "bins");
    }

    /** {@code metadata.requires.skills}: another skill this one delegates to. */
    List<String> requiredSkills() {
        return listAt("metadata", "requires", "skills");
    }

    /**
     * The first frontmatter line at or after {@code keyLine} that matches, stopping at the next
     * top-level key. Evidence must point at the line that actually says it, not at the key.
     */
    Line frontmatterItemLine(Integer keyLine, Pattern pattern) {
        int close = frontmatterClose();
        if (keyLine == null || close < 0 || keyLine < 1 || keyLine > close) {
            return null;
        }
        for (int i = keyLine - 1; i < close; i++) {
            if (i > keyLine - 1 && FRONTMATTER_KEY.matcher(lines.get(i)).find()) {
                break;
            }
            String stripped = lines.get(i).strip();
            if (pattern.matcher(stripped).find()) {
                return new Line(i + 1, stripped, stripped);
            }
        }
        return null;
    }

    List<Line> requirementLines() {
        return List.copyOf(requirementLines);
    }

    Set<String> fenceLanguages() {
        return Set.copyOf(fenceLanguages);
    }

    List<Fence> fences() {
        return List.copyOf(fences);
    }

    /** The raw text of a 1-based line, or the empty string when the line does not exist. */
    String lineText(int line) {
        return line >= 1 && line <= lines.size() ? lines.get(line - 1).strip() : "";
    }

    /**
     * True when the body tells the agent to load another skill. Such a skill is a router: its own
     * file is prose, but what it does depends on the skill it points at, so it is not a pure
     * prompt.
     */
    boolean delegatesToAnotherSkill() {
        return lines.stream().anyMatch(line -> DELEGATION.matcher(line).find());
    }

    /** The first whitespace token, prompt markers and quotes removed, lowercased. */
    static String firstToken(String text) {
        return token(text, 0);
    }

    static String secondToken(String text) {
        return token(text, 1);
    }

    static String thirdToken(String text) {
        return token(text, 2);
    }

    private static String token(String text, int index) {
        String stripped = stripPrompt(text);
        String[] parts = stripped.split("\\s+");
        if (index >= parts.length) {
            return "";
        }
        String token = parts[index].replaceAll("[\"'`]", "");
        int slash = token.lastIndexOf('/');
        if (slash >= 0) {
            token = token.substring(slash + 1);
        }
        return token.toLowerCase(Locale.ROOT);
    }

    private static String stripPrompt(String text) {
        String stripped = text.strip();
        while (stripped.startsWith("$") || stripped.startsWith(">") || stripped.startsWith("!")) {
            stripped = stripped.substring(1).strip();
        }
        return stripped;
    }

    private static boolean isKnownExecutable(String token) {
        return token.startsWith("python") || KNOWN_EXECUTABLES.contains(token);
    }

    private int frontmatterClose() {
        if (lines.isEmpty() || !lines.getFirst().strip().equals("---")) {
            return -1;
        }
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i).strip();
            if (line.equals("---") || line.equals("...")) {
                return i;
            }
        }
        return -1;
    }

    private Map<String, Integer> keyLines(int close) {
        if (close < 0) {
            return Map.of();
        }
        Map<String, Integer> result = new LinkedHashMap<>();
        for (int i = 1; i < close; i++) {
            Matcher key = FRONTMATTER_KEY.matcher(lines.get(i));
            if (key.find()) {
                result.putIfAbsent(key.group(1), i + 1);
            }
        }
        return result;
    }

    private void scanBody(int start) {
        boolean inFence = false;
        String fenceChar = "";
        int fenceLength = 0;
        String fenceInfo = "";
        boolean inRequirements = false;
        for (int i = Math.max(start, 0); i < lines.size(); i++) {
            String raw = lines.get(i);
            String trimmed = raw.strip();
            if (inFence) {
                if (isClosingFence(trimmed, fenceChar, fenceLength)) {
                    inFence = false;
                    continue;
                }
                if (!trimmed.isEmpty()) {
                    codeLines.add(new Line(i + 1, trimmed, trimmed));
                }
                boolean shellFence = SHELL_FENCES.contains(fenceInfo);
                // An untagged fence is often prose or example output. Only a line that starts
                // with a known executable is a command there; a tagged bash fence is trusted.
                boolean commandShaped = !fenceInfo.isEmpty()
                        || isKnownExecutable(firstToken(trimmed));
                if (shellFence && commandShaped && !trimmed.isEmpty()
                        && !EXTERNAL_LINK.matcher(trimmed).find()
                        && !trimmed.startsWith("#")) {
                    addCommand(i + 1, trimmed, trimmed);
                }
                continue;
            }
            Matcher open = FENCE_OPEN.matcher(raw);
            if (open.find()) {
                inFence = true;
                fenceChar = open.group(1).substring(0, 1);
                fenceLength = open.group(1).length();
                fenceInfo = open.group(2).strip().split("\\s+")[0].toLowerCase(Locale.ROOT);
                fenceLanguages.add(fenceInfo);
                fences.add(new Fence(i + 1, fenceInfo, trimmed));
                continue;
            }
            if (trimmed.startsWith("#")) {
                inRequirements = REQUIREMENT_HEADING.matcher(trimmed).find();
                continue;
            }
            if (trimmed.startsWith("$ ")) {
                addCommand(i + 1, trimmed, trimmed);
            } else if (!EXTERNAL_LINK.matcher(raw).find()) {
                Matcher inline = INLINE.matcher(raw);
                while (inline.find()) {
                    String span = inline.group(1).strip();
                    codeLines.add(new Line(i + 1, span, raw.strip()));
                    // A bare `python3` in prose ("or pipe through `python3`") is a suggestion, not
                    // a step. An inline command names an executable *and* what to run.
                    if (isKnownExecutable(firstToken(span)) && !secondToken(span).isEmpty()) {
                        // The quote is the whole line: that is what the evidence check can find.
                        addCommand(i + 1, span, raw.strip());
                    }
                }
            }
            if (inRequirements && !trimmed.isEmpty()) {
                requirementLines.add(new Line(i + 1, trimmed, trimmed));
            } else if (REQUIREMENT_START.matcher(trimmed).find()) {
                requirementLines.add(new Line(i + 1, trimmed, trimmed));
            }
        }
    }

    /**
     * @param text  the command with any {@code $} prompt removed — what the predicates read
     * @param quote the line as written — what the evidence check must find again
     */
    private void addCommand(int line, String text, String quote) {
        String command = stripPrompt(text);
        if (!command.isEmpty()) {
            commands.add(new Command(line, command, quote.strip()));
        }
    }

    private static boolean isClosingFence(String trimmed, String fenceChar, int fenceLength) {
        if (fenceChar.isEmpty() || trimmed.length() < fenceLength) {
            return false;
        }
        for (int i = 0; i < trimmed.length(); i++) {
            if (trimmed.charAt(i) != fenceChar.charAt(0)) {
                return false;
            }
        }
        return true;
    }
}
