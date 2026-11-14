package com.skillmasterai.ops.labels;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * The fixed rules: {@code SKILL.md} and a file manifest in, {@code runtime} claims out.
 *
 * <p><strong>Default negative.</strong> A value is written only when a strong signal names it. An
 * absent operating system writes no operating system; a mention of Docker in prose is not a Docker
 * requirement. The two mistakes are not symmetric: a missing label leaves a gap, a wrong label tells
 * the reader to install something they do not need.
 *
 * <p><strong>Pure.</strong> No IO, no clock, no randomness. The same facts and the same
 * {@link #RULE_VERSION} always produce the same list, which is what makes the rules testable by
 * fixture and the refresh safe to rerun.
 *
 * <p>The trigger table this implements is {@code docs/versions/v72-fixed-labeling/
 * technical-design.md} §规则表. Change the rules there first; a rule that exists only here is a
 * rule nobody reviewed.
 */
public final class RuleLabeler {

    /** Bumped whenever a rule changes; stored with every claim so old labels are explainable. */
    public static final String RULE_VERSION = "fixed-r2";

    private static final String RUNTIME = "runtime:";
    private static final String SKILL_MD = "SKILL.md";

    private static final Pattern NEGATION =
            Pattern.compile("(?i)\\b(no|not|without)\\b|无需|不需要|不用");

    private static final Pattern PYTHON_REQUIREMENT = Pattern.compile(
            "(?i)\\bpython(3(\\.[0-9]+)?)?\\b|\\bpip3?\\b|\\buv\\b|\\bpoetry\\b|\\bconda\\b");
    private static final Pattern NODE_REQUIREMENT = Pattern.compile(
            "(?i)\\bnode(\\.js)?\\b|\\bnpm\\b|\\bpnpm\\b|\\byarn\\b|\\bbun\\b");
    private static final Pattern JAVA_REQUIREMENT = Pattern.compile(
            "(?i)\\bjava\\b|\\bjdk\\b|\\bmaven\\b|\\bgradle\\b");
    private static final Pattern GO_REQUIREMENT = Pattern.compile(
            "(?i)\\bgolang\\b|\\bgo\\s+1\\.[0-9]+\\b");
    private static final Pattern RUST_REQUIREMENT = Pattern.compile("(?i)\\brustc?\\b");
    private static final Pattern DOTNET_REQUIREMENT = Pattern.compile(
            "(?i)\\bdotnet\\b|\\.net\\b");
    private static final Pattern RUBY_REQUIREMENT = Pattern.compile("(?i)\\bruby\\b|\\brails\\b");
    private static final Pattern PHP_REQUIREMENT = Pattern.compile("(?i)\\bphp\\b");
    private static final Pattern CPP_REQUIREMENT = Pattern.compile(
            "(?i)\\bgcc\\b|\\bclang\\b|\\bc\\+\\+\\b|\\bc compiler\\b");
    private static final Pattern SHELL_REQUIREMENT = Pattern.compile(
            "(?i)\\b(bash|zsh|shell)\\b");
    private static final Pattern DOCKER_REQUIREMENT = Pattern.compile("(?i)\\bdocker\\b");
    private static final Pattern GPU_REQUIREMENT = Pattern.compile(
            "(?i)\\b(nvidia\\s+)?gpu\\b|\\bcuda\\b");
    private static final Pattern DATABASE_REQUIREMENT = Pattern.compile(
            "(?i)\\b(database|postgres(ql)?|mysql|maria(db)?|mongo(db)?|redis|sql\\s*server)\\b");
    private static final Pattern BROWSER_REQUIREMENT = Pattern.compile(
            "(?i)\\b(browser|chromium|chrome)\\b");
    private static final Pattern ONLINE_REQUIREMENT = Pattern.compile(
            "(?i)\\b(internet|online\\s+service|network\\s+access|web\\s+access)\\b|联网|在线服务|网络访问");
    private static final Pattern ONLINE_DESCRIPTION = Pattern.compile(
            "(?i)(联网|在线).{0,12}(搜索|检索|抓取|访问)"
                    + "|\\b(online|web)\\s+(search|access|api)\\b"
                    + "|\\brequires?\\s+internet\\b|\\bneeds?\\s+internet\\b");
    private static final Pattern BROWSER_SCRIPT = Pattern.compile(
            "(?i)\\b(browser|playwright|puppeteer|selenium|chromedriver|webdriver)[\\w-]*"
                    + "\\.(js|mjs|cjs|ts|py)\\b");
    private static final Pattern SYSTEM_REQUIREMENT = Pattern.compile(
            "(?i)\\b(administrator|root|system\\s+permission)\\b|系统权限|无障碍");
    private static final Pattern API_KEY_REQUIREMENT = Pattern.compile(
            "(?i)\\bapi[\\s-]?key\\b|密钥");
    private static final Pattern ACCOUNT_REQUIREMENT = Pattern.compile(
            "(?i)\\baccount\\b|账号");
    /**
     * A credential <em>variable</em>, not any word ending in "token": environment-variable names
     * are upper case. {@code WEB_SEARCH_API_KEY} counts; {@code refresh_token}, {@code doc_token}
     * and {@code user_access_token} are field names in payloads and do not.
     */
    private static final Pattern ENV_KEY = Pattern.compile(
            "\\b[A-Z][A-Z0-9_]*(API_KEY|APIKEY|TOKEN|SECRET|ACCESS_KEY)\\b");
    private static final Pattern WEB_TOOLS = Pattern.compile("(?i)\\b(WebFetch|WebSearch)\\b");
    private static final Pattern REMOTE_URL = Pattern.compile(
            "https?://(?!localhost[/:]|127\\.0\\.0\\.1|0\\.0\\.0\\.0)[^\\s'\"<>]+");
    private static final Pattern BROWSER_TOOLS = Pattern.compile(
            "(?i)\\b(browser|playwright|puppeteer)\\b");

    private static final Pattern MACOS_ONLY = Pattern.compile(
            "(?i)(only\\s+(on\\s+)?mac\\s?os|mac\\s?os\\s+only"
                    + "|(仅|只支持|只适用于|requires?|needs?)\\s*(支持|适用于)?\\s*(mac\\s?os|os\\s?x))");
    private static final Pattern WINDOWS_ONLY = Pattern.compile(
            "(?i)(only\\s+(on\\s+)?windows|windows\\s+only"
                    + "|(仅|只支持|只适用于|requires?|needs?)\\s*(支持|适用于)?\\s*windows)");
    private static final Pattern LINUX_ONLY = Pattern.compile(
            "(?i)(only\\s+(on\\s+)?linux|linux\\s+only"
                    + "|(仅|只支持|只适用于|requires?|needs?)\\s*(支持|适用于)?\\s*linux)");

    private static final Set<String> PYTHON_FENCES = Set.of("python", "py", "python3");
    /**
     * A {@code js} fence is not evidence of a Node runtime: ECharts options and browser snippets
     * are written in the same language. Node comes from {@code package.json}, a script file, or a
     * node/npm command.
     */
    private static final Set<String> NODE_FENCES = Set.of();
    private static final Set<String> JAVA_FENCES = Set.of("java");
    private static final Set<String> GO_FENCES = Set.of("go", "golang");
    private static final Set<String> RUST_FENCES = Set.of("rust");
    private static final Set<String> DOTNET_FENCES =
            Set.of("csharp", "c#", "cs", "fsharp", "vb");
    private static final Set<String> RUBY_FENCES = Set.of("ruby", "rb");
    private static final Set<String> PHP_FENCES = Set.of("php");
    private static final Set<String> CPP_FENCES = Set.of("c", "cpp", "c++", "h", "hpp");

    private static final Set<String> PROGRAMMING_FENCES = Set.of(
            "python", "py", "python3", "js", "javascript", "ts", "typescript", "jsx", "tsx",
            "java", "go", "golang", "rust", "csharp", "c#", "cs", "fsharp", "vb", "ruby", "rb",
            "php", "c", "cpp", "c++", "h", "hpp", "bash", "sh", "shell", "zsh");

    /** A declared prerequisite command, mapped to the runtime value it implies. */
    /** A declared bin that only works against a remote service. */
    private static final Set<String> ONLINE_BINS = Set.of("lark-cli", "curl", "wget", "gh", "az",
            "aws", "gcloud", "kubectl", "skmr", "modal", "runpod", "replicate", "vercel",
            "netlify");

    private static final java.util.Map<String, String> PREREQUISITE_VALUES = java.util.Map.ofEntries(
            java.util.Map.entry("python", "python"), java.util.Map.entry("python3", "python"),
            java.util.Map.entry("pip", "python"), java.util.Map.entry("pip3", "python"),
            java.util.Map.entry("pipx", "python"), java.util.Map.entry("uv", "python"),
            java.util.Map.entry("poetry", "python"), java.util.Map.entry("conda", "python"),
            java.util.Map.entry("node", "node"), java.util.Map.entry("npm", "node"),
            java.util.Map.entry("npx", "node"), java.util.Map.entry("pnpm", "node"),
            java.util.Map.entry("yarn", "node"), java.util.Map.entry("bun", "node"),
            java.util.Map.entry("java", "java"), java.util.Map.entry("javac", "java"),
            java.util.Map.entry("mvn", "java"), java.util.Map.entry("gradle", "java"),
            java.util.Map.entry("go", "go"), java.util.Map.entry("cargo", "rust"),
            java.util.Map.entry("rustc", "rust"), java.util.Map.entry("dotnet", "dotnet"),
            java.util.Map.entry("ruby", "ruby"), java.util.Map.entry("bundle", "ruby"),
            java.util.Map.entry("gem", "ruby"), java.util.Map.entry("php", "php"),
            java.util.Map.entry("composer", "php"), java.util.Map.entry("gcc", "c-cpp"),
            java.util.Map.entry("g++", "c-cpp"), java.util.Map.entry("clang", "c-cpp"),
            java.util.Map.entry("make", "c-cpp"), java.util.Map.entry("cmake", "c-cpp"),
            java.util.Map.entry("ninja", "c-cpp"), java.util.Map.entry("bash", "shell"),
            java.util.Map.entry("zsh", "shell"), java.util.Map.entry("sh", "shell"),
            java.util.Map.entry("docker", "docker"), java.util.Map.entry("docker-compose", "docker"),
            java.util.Map.entry("psql", "database"), java.util.Map.entry("mysql", "database"),
            java.util.Map.entry("mongosh", "database"), java.util.Map.entry("redis-cli", "database"),
            java.util.Map.entry("playwright", "browser"), java.util.Map.entry("puppeteer", "browser"),
            java.util.Map.entry("selenium", "browser"), java.util.Map.entry("chromedriver", "browser"));

    /** The 21 non-default values, in the vocabulary's order; one rule each. */
    public List<LabelClaim> classify(SkillFacts facts) {
        SkillText text = SkillText.of(facts.skillMd());
        List<LabelClaim> claims = new ArrayList<>();

        add(claims, "python", language(facts, text, "python", RuleLabeler::isPython,
                PYTHON_REQUIREMENT, RuleLabeler::isPythonFile, PYTHON_FENCES));
        add(claims, "node", language(facts, text, "node", RuleLabeler::isNode,
                NODE_REQUIREMENT, RuleLabeler::isNodeFile, NODE_FENCES));
        add(claims, "java", language(facts, text, "java", RuleLabeler::isJava,
                JAVA_REQUIREMENT, RuleLabeler::isJavaFile, JAVA_FENCES));
        add(claims, "go", language(facts, text, "go", RuleLabeler::isGo,
                GO_REQUIREMENT, RuleLabeler::isGoFile, GO_FENCES));
        add(claims, "rust", language(facts, text, "rust", RuleLabeler::isRust,
                RUST_REQUIREMENT, RuleLabeler::isRustFile, RUST_FENCES));
        add(claims, "dotnet", language(facts, text, "dotnet", RuleLabeler::isDotnet,
                DOTNET_REQUIREMENT, RuleLabeler::isDotnetFile, DOTNET_FENCES));
        add(claims, "ruby", language(facts, text, "ruby", RuleLabeler::isRuby,
                RUBY_REQUIREMENT, RuleLabeler::isRubyFile, RUBY_FENCES));
        add(claims, "php", language(facts, text, "php", RuleLabeler::isPhp,
                PHP_REQUIREMENT, RuleLabeler::isPhpFile, PHP_FENCES));
        add(claims, "c-cpp", language(facts, text, "c-cpp", RuleLabeler::isCpp,
                CPP_REQUIREMENT, RuleLabeler::isCppFile, CPP_FENCES));
        add(claims, "shell", language(facts, text, "shell", RuleLabeler::isShell,
                SHELL_REQUIREMENT, RuleLabeler::isShellFile, Set.of()));

        add(claims, "macos", os(text, "macos", MACOS_ONLY));
        add(claims, "windows", os(text, "windows", WINDOWS_ONLY));
        add(claims, "linux", os(text, "linux", LINUX_ONLY));

        add(claims, "docker", tool(text, "docker", RuleLabeler::isDocker, DOCKER_REQUIREMENT));
        add(claims, "gpu", tool(text, "gpu", RuleLabeler::isGpu, GPU_REQUIREMENT));
        add(claims, "database", tool(text, "database", RuleLabeler::isDatabase,
                DATABASE_REQUIREMENT));
        add(claims, "browser", browser(text));
        add(claims, "online-service", online(text));
        add(claims, "system-permission", tool(text, "system-permission", RuleLabeler::isSudo,
                SYSTEM_REQUIREMENT));

        add(claims, "api-key", apiKey(text));
        add(claims, "account", tool(text, "account", RuleLabeler::isLogin, ACCOUNT_REQUIREMENT));

        addPrerequisites(text, claims);

        if (claims.isEmpty() && !hasExecutableContent(facts, text)) {
            claims.add(new LabelClaim(RUNTIME + "prompt-only", "prompt-only.default",
                    new Evidence(Evidence.FILE, SKILL_MD, 0, SKILL_MD)));
        }
        return claims.stream().filter(claim -> EvidenceCheck.verify(facts, claim)).toList();
    }

    private static void add(List<LabelClaim> claims, String value, Found found) {
        if (found != null) {
            claims.add(new LabelClaim(RUNTIME + value, found.ruleId(), found.evidence()));
        }
    }

    private record Found(String ruleId, Evidence evidence) {
    }

    private static Optional<Found> best(List<Optional<Found>> options) {
        return options.stream().filter(Optional::isPresent).map(Optional::get).findFirst();
    }

    private static Found language(SkillFacts facts, SkillText text, String value,
            Predicate<String> command, Pattern requirement, Predicate<String> manifest,
            Set<String> fences) {
        return best(List.of(
                c(text, value + ".command", command),
                r(text, value + ".requirement", requirement),
                m(facts, value + ".manifest", manifest),
                fence(text, value + ".fence", fences))).orElse(null);
    }

    private static Found tool(SkillText text, String value, Predicate<String> command,
            Pattern requirement) {
        return best(List.of(
                c(text, value + ".command", command),
                r(text, value + ".requirement", requirement))).orElse(null);
    }

    private static Found browser(SkillText text) {
        return best(List.of(
                c(text, "browser.command", RuleLabeler::isBrowser),
                Optional.ofNullable(f(text, "browser.allowed-tools", "allowed-tools",
                        BROWSER_TOOLS)),
                r(text, "browser.requirement", BROWSER_REQUIREMENT))).orElse(null);
    }

    private static Found online(SkillText text) {
        return best(List.of(
                Optional.ofNullable(f(text, "online-service.allowed-tools", "allowed-tools",
                        WEB_TOOLS)),
                c(text, "online-service.command", RuleLabeler::isOnlineCli),
                r(text, "online-service.requirement", ONLINE_REQUIREMENT),
                Optional.ofNullable(f(text, "online-service.description", "description",
                        ONLINE_DESCRIPTION)))).orElse(null);
    }

    private static Found apiKey(SkillText text) {
        return best(List.of(
                c(text, "api-key.env", command -> ENV_KEY.matcher(command).find()),
                code(text, "api-key.code-env", line -> ENV_KEY.matcher(line.text()).find()),
                credentials(text),
                Optional.ofNullable(f(text, "api-key.compatibility", "compatibility",
                        API_KEY_REQUIREMENT)),
                r(text, "api-key.requirement", API_KEY_REQUIREMENT))).orElse(null);
    }

    /**
     * {@code metadata.requires.credentials}: a credential the author declared, with a key-shaped
     * name. This is the strongest api-key signal a skill can carry — it is a declaration, not a
     * mention.
     */
    private static Optional<Found> credentials(SkillText text) {
        Integer start = text.lineOf("metadata");
        if (start == null) {
            return Optional.empty();
        }
        for (String credential : text.credentials()) {
            if (!ENV_KEY.matcher(credential).find()) {
                continue;
            }
            SkillText.Line line = text.frontmatterItemLine(start,
                    Pattern.compile(Pattern.quote(credential.strip())));
            if (line != null) {
                return Optional.of(new Found("api-key.credentials",
                        new Evidence(Evidence.FRONTMATTER, SKILL_MD, line.line(), line.quote())));
            }
        }
        return Optional.empty();
    }

    /** An api-key value found in a fence or inline span rather than on a command line. */
    private static Optional<Found> code(SkillText text, String ruleId,
            Predicate<SkillText.Line> line) {
        return text.codeLines().stream().filter(line).findFirst()
                .map(found -> new Found(ruleId,
                        new Evidence(Evidence.COMMAND, SKILL_MD, found.line(), found.quote())));
    }

    // /* ---- platform list and declared prerequisites ---- */

    /**
     * {@code platforms: [linux, macos]} is the author's explicit supported list. It is a
     * restriction only when it names fewer than all three desktop systems; a list of all three
     * says nothing.
     */
    private static Found platformOs(SkillText text, String value) {
        Integer keyLine = text.lineOf("platforms");
        List<String> platforms = text.platforms();
        if (keyLine == null || platforms.isEmpty() || platforms.size() >= 3) {
            return null;
        }
        for (String platform : platforms) {
            if (!platformIs(platform, value)) {
                continue;
            }
            SkillText.Line line = text.frontmatterItemLine(keyLine,
                    Pattern.compile(Pattern.quote(platform.strip())));
            if (line != null) {
                return new Found(value + ".platforms",
                        new Evidence(Evidence.FRONTMATTER, SKILL_MD, line.line(), line.quote()));
            }
        }
        return null;
    }

    private static boolean platformIs(String platform, String value) {
        String name = platform.strip().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "macos" -> Set.of("macos", "mac", "osx", "os x", "darwin").contains(name);
            case "windows" -> Set.of("windows", "win", "win32", "win64").contains(name);
            case "linux" -> Set.of("linux", "linux2").contains(name);
            default -> false;
        };
    }

    /**
     * {@code prerequisites.commands: [python3, docker]} states a requirement directly. The rules
     * still keep their command/requirement/manifest order; this only fills a value nothing else
     * named, so it can raise recall without overriding a stronger signal.
     */
    private static void addPrerequisites(SkillText text, List<LabelClaim> claims) {
        Set<String> present = new java.util.LinkedHashSet<>();
        for (LabelClaim claim : claims) {
            present.add(claim.definitionId());
        }
        declare(text, claims, present, text.prerequisites(), "prerequisites",
                text.lineOf("prerequisites"));
        Integer metadata = text.lineOf("metadata");
        declare(text, claims, present, text.bins(), "bins",
                metadata != null ? metadata : text.lineOf("bins"));
    }

    /** A declared prerequisite command or bin, mapped to the runtime value it implies. */
    private static void declare(SkillText text, List<LabelClaim> claims, Set<String> present,
            List<String> declared, String source, Integer keyLine) {
        if (keyLine == null || declared.isEmpty()) {
            return;
        }
        for (String entry : declared) {
            String command = SkillText.firstToken(entry);
            String value = PREREQUISITE_VALUES.get(command);
            String ruleId = null;
            if (value != null) {
                ruleId = source + "." + value;
            } else if (ONLINE_BINS.contains(command)) {
                value = "online-service";
                ruleId = source + ".online-service";
            }
            if (value == null || present.contains(RUNTIME + value)) {
                continue;
            }
            SkillText.Line line = text.frontmatterItemLine(keyLine,
                    Pattern.compile(Pattern.quote(entry.strip())));
            if (line == null) {
                continue;
            }
            claims.add(new LabelClaim(RUNTIME + value, ruleId,
                    new Evidence(Evidence.FRONTMATTER, SKILL_MD, line.line(), line.quote())));
            present.add(RUNTIME + value);
        }
    }

    private static Found os(SkillText text, String value, Pattern adjacent) {
        Found platform = platformOs(text, value);
        if (platform != null) {
            return platform;
        }
        String compatibility = text.text("compatibility");
        Integer compatibilityLine = text.lineOf("compatibility");
        if (!compatibility.isBlank() && compatibilityLine != null
                && osMatches(compatibility, value, adjacent)) {
            String quote = text.lineText(compatibilityLine);
            if (!quote.isEmpty()) {
                return new Found(value + ".compatibility", new Evidence(Evidence.FRONTMATTER,
                        SKILL_MD, compatibilityLine, quote));
            }
        }
        for (SkillText.Line line : text.requirementLines()) {
            if (!NEGATION.matcher(line.text()).find() && osMatches(line.text(), value, adjacent)) {
                return new Found(value + ".requirement",
                        new Evidence(Evidence.REQUIREMENT, SKILL_MD, line.line(), line.quote()));
            }
        }
        return null;
    }

    private static boolean osMatches(String text, String value, Pattern adjacent) {
        String lower = text.toLowerCase(Locale.ROOT);
        boolean restricting = lower.contains("only") || text.contains("仅")
                || text.contains("只支持") || lower.contains("requires") || lower.contains("needs");
        if (!restricting) {
            return false;
        }
        boolean listed = (text.contains("仅") || lower.contains("only") || text.contains("只支持"))
                && aliasPresent(lower, value);
        return listed || adjacent.matcher(text).find();
    }

    private static boolean aliasPresent(String lower, String value) {
        return switch (value) {
            case "macos" -> lower.contains("macos") || lower.contains("mac os")
                    || lower.contains("os x");
            case "windows" -> lower.contains("windows");
            case "linux" -> lower.contains("linux");
            default -> false;
        };
    }

    private static Optional<Found> c(SkillText text, String ruleId, Predicate<String> command) {
        for (SkillText.Command candidate : text.commands()) {
            for (String segment : segments(candidate.text())) {
                if (command.test(segment) || command.test(withoutAssignments(segment))) {
                    return Optional.of(new Found(ruleId,
                            new Evidence(Evidence.COMMAND, SKILL_MD, candidate.line(),
                                    candidate.quote())));
                }
            }
        }
        return Optional.empty();
    }

    /** A line can hold several commands: {@code curl … | python3 -c …}. Each is its own command. */
    private static List<String> segments(String text) {
        List<String> parts = new ArrayList<>();
        parts.add(text);
        for (String part : text.split("\\|\\||&&|\\||;")) {
            String stripped = part.strip();
            if (!stripped.isEmpty()) {
                parts.add(stripped);
            }
        }
        return parts;
    }

    /** {@code API_KEY=… python3 x.py}: the assignments are not the executable. */
    private static String withoutAssignments(String text) {
        String stripped = text.strip();
        while (true) {
            String[] parts = stripped.split("\\s+", 2);
            if (parts.length == 2 && parts[0].matches("[A-Za-z_][A-Za-z0-9_]*=.*")) {
                stripped = parts[1].strip();
            } else {
                return stripped;
            }
        }
    }

    private static Optional<Found> r(SkillText text, String ruleId, Pattern pattern) {
        return text.requirementLines().stream()
                .filter(line -> !NEGATION.matcher(line.text()).find())
                .filter(line -> pattern.matcher(line.text()).find())
                .findFirst()
                .map(line -> new Found(ruleId,
                        new Evidence(Evidence.REQUIREMENT, SKILL_MD, line.line(), line.quote())));
    }

    private static Optional<Found> m(SkillFacts facts, String ruleId,
            Predicate<String> relpath) {
        return facts.files().stream().filter(file -> relpath.test(file.relpath()))
                .findFirst()
                .map(file -> new Found(ruleId,
                        new Evidence(Evidence.FILE, file.relpath(), 0, file.relpath())));
    }

    private static Optional<Found> fence(SkillText text, String ruleId, Set<String> languages) {
        if (languages.isEmpty()) {
            return Optional.empty();
        }
        return text.fences().stream().filter(f -> languages.contains(f.info())).findFirst()
                .map(f -> new Found(ruleId,
                        new Evidence(Evidence.FENCE, SKILL_MD, f.line(), f.quote())));
    }

    private static Found f(SkillText text, String ruleId, String key, Pattern pattern) {
        String value = text.text(key);
        if (value.isBlank() || !pattern.matcher(value).find()) {
            return null;
        }
        Integer line = text.lineOf(key);
        if (line == null) {
            return null;
        }
        String quote = text.lineText(line);
        return quote.isEmpty() ? null
                : new Found(ruleId, new Evidence(Evidence.FRONTMATTER, SKILL_MD, line, quote));
    }

    private static boolean hasExecutableContent(SkillFacts facts, SkillText text) {
        if (!text.commands().isEmpty()) {
            return true;
        }
        if (text.fenceLanguages().stream().anyMatch(PROGRAMMING_FENCES::contains)) {
            return true;
        }
        if (!text.requiredSkills().isEmpty() || text.delegatesToAnotherSkill()) {
            return true;
        }
        return facts.files().stream().anyMatch(file -> isCodeOrConfig(file.relpath())
                || isInstructionFile(file.relpath()));
    }

    /** A nested markdown file is more instructions than the root; not a pure prompt. */
    private static boolean isInstructionFile(String relpath) {
        String name = basename(relpath);
        return relpath.contains("/") && (name.endsWith(".md") || name.endsWith(".txt"));
    }

    private static boolean isCodeOrConfig(String relpath) {
        String name = basename(relpath);
        return isPythonFile(relpath) || isNodeFile(relpath) || isJavaFile(relpath)
                || isGoFile(relpath) || isRustFile(relpath) || isDotnetFile(relpath)
                || isRubyFile(relpath) || isPhpFile(relpath) || isCppFile(relpath)
                || isShellFile(relpath) || name.equals("dockerfile")
                || name.startsWith("docker-compose.") || name.equals("compose.yaml")
                || name.equals("compose.yml");
    }

    private static boolean isPythonFile(String relpath) {
        String name = basename(relpath);
        return extension(relpath).equals(".py") || Set.of("requirements.txt", "pyproject.toml",
                "setup.py", "setup.cfg", "pipfile", "pipfile.lock", "uv.lock",
                "poetry.lock").contains(name);
    }

    private static boolean isNodeFile(String relpath) {
        String name = basename(relpath);
        if (Set.of("package.json", "package-lock.json", "pnpm-lock.yaml", "yarn.lock",
                "bun.lockb").contains(name)) {
            return true;
        }
        String extension = extension(relpath);
        // A .jsx/.tsx file is a view component; it runs in a browser build, not under Node.
        if (Set.of(".jsx", ".tsx").contains(extension)) {
            return false;
        }
        // A starter component (.jsx/.jsx-like template) is a browser asset, not a Node script.
        if (Set.of(".js", ".mjs", ".cjs", ".ts").contains(extension)) {
            return !relpath.toLowerCase(Locale.ROOT).contains("starter-components/");
        }
        return false;
    }

    private static boolean isJavaFile(String relpath) {
        String name = basename(relpath);
        return extension(relpath).equals(".java") || Set.of("pom.xml", "build.gradle",
                "build.gradle.kts", "settings.gradle", "settings.gradle.kts").contains(name);
    }

    private static boolean isGoFile(String relpath) {
        String name = basename(relpath);
        return extension(relpath).equals(".go") || Set.of("go.mod", "go.sum").contains(name);
    }

    private static boolean isRustFile(String relpath) {
        String name = basename(relpath);
        return extension(relpath).equals(".rs") || Set.of("cargo.toml", "cargo.lock").contains(name);
    }

    private static boolean isDotnetFile(String relpath) {
        return Set.of(".csproj", ".fsproj", ".vbproj", ".sln", ".cs", ".fs")
                .contains(extension(relpath));
    }

    private static boolean isRubyFile(String relpath) {
        String name = basename(relpath);
        return Set.of(".rb", ".gemspec").contains(extension(relpath))
                || Set.of("gemfile", "gemfile.lock").contains(name);
    }

    private static boolean isPhpFile(String relpath) {
        String name = basename(relpath);
        return extension(relpath).equals(".php")
                || Set.of("composer.json", "composer.lock").contains(name);
    }

    private static boolean isCppFile(String relpath) {
        String name = basename(relpath);
        return Set.of(".c", ".cc", ".cpp", ".cxx", ".h", ".hpp").contains(extension(relpath))
                || Set.of("cmakelists.txt", "makefile", "meson.build").contains(name);
    }

    private static boolean isShellFile(String relpath) {
        return Set.of(".sh", ".bash", ".zsh").contains(extension(relpath));
    }

    private static String basename(String relpath) {
        int slash = relpath.lastIndexOf('/');
        return (slash < 0 ? relpath : relpath.substring(slash + 1)).toLowerCase(Locale.ROOT);
    }

    private static String extension(String relpath) {
        String name = basename(relpath);
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot);
    }

    /* ---- command-shaped predicates: the first token is the executable ---- */

    private static boolean isPython(String text) {
        String token = SkillText.firstToken(text);
        if (token.startsWith("python") || Set.of("pip", "pip3", "pipx").contains(token)) {
            return true;
        }
        return Set.of("uv", "poetry", "conda").contains(token)
                && Set.of("run", "install", "sync", "add", "env", "shell")
                        .contains(SkillText.secondToken(text));
    }

    private static boolean isNode(String text) {
        return Set.of("node", "npm", "npx", "pnpm", "yarn", "bun")
                .contains(SkillText.firstToken(text));
    }

    private static boolean isJava(String text) {
        String token = SkillText.firstToken(text);
        if (Set.of("mvn", "mvnw", "gradle", "gradlew").contains(token)) {
            return true;
        }
        return token.equals("java") && !Set.of("-version", "--version", "-help", "--help", "-h")
                .contains(SkillText.secondToken(text));
    }

    private static boolean isGo(String text) {
        // `go install` compiles and drops a native binary; the skill does not need Go at runtime,
        // and it is usually listed as one of several install methods.
        return SkillText.firstToken(text).equals("go")
                && Set.of("run", "build", "test", "mod", "get", "env", "work")
                        .contains(SkillText.secondToken(text));
    }

    private static boolean isRust(String text) {
        return SkillText.firstToken(text).equals("cargo");
    }

    private static boolean isDotnet(String text) {
        return SkillText.firstToken(text).equals("dotnet");
    }

    private static boolean isRuby(String text) {
        return Set.of("ruby", "bundle", "gem").contains(SkillText.firstToken(text));
    }

    private static boolean isPhp(String text) {
        return Set.of("php", "composer").contains(SkillText.firstToken(text));
    }

    private static boolean isCpp(String text) {
        return Set.of("gcc", "g++", "clang", "clang++", "make", "cmake", "ninja")
                .contains(SkillText.firstToken(text));
    }

    private static boolean isShell(String text) {
        if (!Set.of("bash", "zsh").contains(SkillText.firstToken(text))) {
            return false;
        }
        String second = SkillText.secondToken(text);
        return !second.isEmpty() && !Set.of("-version", "--version", "-h", "--help").contains(second);
    }

    private static boolean isDocker(String text) {
        return Set.of("docker", "docker-compose").contains(SkillText.firstToken(text))
                && Set.of("build", "run", "compose", "pull", "exec", "up", "down", "ps", "start",
                        "stop").contains(SkillText.secondToken(text));
    }

    private static boolean isGpu(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.contains("torchrun") || lower.contains("nvidia-smi")
                || lower.contains("--gpus") || lower.contains("cuda_visible_devices");
    }

    private static boolean isDatabase(String text) {
        String token = SkillText.firstToken(text);
        if (Set.of("psql", "mysql", "mariadb", "mongosh", "redis-cli", "sqlcmd").contains(token)) {
            return true;
        }
        if (text.toUpperCase(Locale.ROOT).contains("EXPLAIN ANALYZE")) {
            return true;
        }
        return Set.of("flyway", "liquibase", "alembic").contains(token)
                && Set.of("migrate", "update", "upgrade").contains(SkillText.secondToken(text));
    }

    private static boolean isBrowser(String text) {
        String token = SkillText.firstToken(text);
        if (Set.of("playwright", "puppeteer", "selenium", "chromedriver").contains(token)) {
            return true;
        }
        if (BROWSER_SCRIPT.matcher(text).find()) {
            return true;
        }
        return Set.of("npx", "pnpm", "yarn", "bun").contains(token)
                && Set.of("playwright", "puppeteer", "selenium")
                        .contains(SkillText.secondToken(text));
    }

    private static boolean isOnlineCli(String text) {
        String token = SkillText.firstToken(text);
        if (token.equals("skmr") && SkillText.secondToken(text).equals("skill")
                && Set.of("read", "invoke").contains(SkillText.thirdToken(text))) {
            // Loading a skill's own instructions says nothing about the task's runtime.
            return false;
        }
        if (Set.of("modal", "runpod", "replicate", "curl", "wget", "gh", "az", "aws",
                "gcloud", "kubectl", "skmr", "lark-cli").contains(token)) {
            return true;
        }
        return REMOTE_URL.matcher(text).find();
    }

    private static boolean isSudo(String text) {
        return SkillText.firstToken(text).equals("sudo");
    }

    private static boolean isLogin(String text) {
        String first = SkillText.firstToken(text);
        String second = SkillText.secondToken(text);
        String third = SkillText.thirdToken(text);
        if (second.equals("login") || (second.equals("auth")
                && Set.of("login", "logout", "status", "whoami", "info").contains(third))) {
            return true;
        }
        // Acting as a user (lark-cli --as user) requires a logged-in account even when the
        // SKILL.md never shows the login command itself.
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.contains("--as user") || lower.contains("--as=user")) {
            return true;
        }
        return first.equals("aws") && second.equals("configure");
    }
}
