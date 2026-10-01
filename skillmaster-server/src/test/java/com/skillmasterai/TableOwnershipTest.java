package com.skillmasterai;

import static org.assertj.core.api.Assertions.assertThat;

import com.skillmasterai.common.ModuleMap;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Approximates §2.5 rule 1 — "a module may only read and write the tables it owns" — as a
 * source lint over string literals.
 *
 * <p><strong>This is a lint, not a proof, and it is worth being precise about why.</strong> It
 * reads Java source text, so SQL assembled at runtime, built from fragments, hidden in a resource
 * file, or written with a table alias it does not know about will all slip past. A table named
 * only in Javadoc {@code {@code …}} is invisible to it too. What it does catch is the ordinary
 * mistake: a query in one module reaching for another module's table. The mechanism that would
 * actually guarantee the rule is a PostgreSQL role per module with {@code GRANT} on its own
 * tables only, which needs one DataSource per module and far more machinery than P0 justifies.
 * Noted here so the gap is visible rather than assumed closed.
 *
 * <p>Only string literals are inspected — comments are skipped. Scanning whole files would flag
 * every identifier that happens to share a table's name, and scanning comments would flag prose
 * (this class's own sibling once tripped over a Javadoc phrase in quotes).
 */
class TableOwnershipTest {

    private static final Path SOURCES = Path.of("src/main/java");

    private static final Path MIGRATIONS = Path.of("src/main/resources/db/migration");

    private static final String COMMON_PACKAGE = ModuleMap.BASE_PACKAGE + ".common";

    private static final Pattern PACKAGE_DECLARATION =
            Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;", Pattern.MULTILINE);

    /*
     * Case-insensitive and leading-whitespace tolerant on purpose: PostgreSQL accepts either
     * spelling and both are legal SQL, so a parse that only recognises the loud one would let the
     * quiet one through as agreement — the set would simply lack the table on both sides.
     */
    private static final Pattern CREATE_TABLE =
            Pattern.compile("^\\s*CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?(\\w+)",
                    Pattern.MULTILINE | Pattern.CASE_INSENSITIVE);

    /*
     * A migration may take a table away again, and a table that no longer exists is not a table any
     * module can be asked to own — so the declared set is what the migrations create minus what
     * they drop. Without this, dropping a table would force a module to claim a table the schema no
     * longer has, and the map would have to lie to keep this test green.
     *
     * `IF EXISTS` is accepted because PostgreSQL accepts it; its presence or absence says nothing
     * about whether the table survives the statement.
     */
    private static final Pattern DROP_TABLE =
            Pattern.compile("^\\s*DROP\\s+TABLE\\s+(?:IF\\s+EXISTS\\s+)?(\\w+)",
                    Pattern.MULTILINE | Pattern.CASE_INSENSITIVE);

    @Test
    void everyTableInTheBaselineIsOwnedByExactlyOneModule() throws IOException {
        // Pins the map against the migrations in both directions, which is what this test has
        // always claimed to do. Counting the map's own entries could not do it: a table added to a
        // migration and never registered leaves the count where it was and passed here, which is
        // exactly the case §2.5 rule 1 exists to catch. ModuleMap throws if two modules claim the
        // same table.
        //
        // Every migration file, not just the baseline: the next table arrives in a later one, and
        // reading only V1 would leave it unowned and touchable from any module with nothing to
        // notice.
        assertThat(Files.isDirectory(MIGRATIONS))
                .as("expected to run from the module directory, so %s resolves", MIGRATIONS)
                .isTrue();

        Set<String> declared = new HashSet<>();
        try (Stream<Path> files = Files.list(MIGRATIONS)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".sql")).sorted().toList()) {
                collectDeclaredTables(Files.readString(file, StandardCharsets.UTF_8), declared);
            }
        }

        assertThat(declared).as("%s should declare tables at all", MIGRATIONS).isNotEmpty();
        assertThat(ModuleMap.tableOwners().keySet())
                .as("every table the migrations declare, and only those, should have one owner")
                .containsExactlyInAnyOrderElementsOf(declared);
    }

    @Test
    void aTableIsOnlyNamedInsideItsOwnersPackage() throws IOException {
        assertThat(Files.isDirectory(SOURCES))
                .as("expected to run from the module directory, so %s resolves", SOURCES)
                .isTrue();

        Map<String, ModuleMap.Module> owners = ModuleMap.tableOwners();
        List<String> violations = new ArrayList<>();

        try (Stream<Path> files = Files.walk(SOURCES)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                String packageName = packageOf(source);

                // ModuleMap declares the whole map, so it necessarily names every table.
                if (packageName.startsWith(COMMON_PACKAGE)) {
                    continue;
                }

                String queries = stringLiteralsIn(source);
                for (Map.Entry<String, ModuleMap.Module> entry : owners.entrySet()) {
                    ModuleMap.Module owner = entry.getValue();
                    if (mentionsTable(queries, entry.getKey())
                            && !isInside(packageName, owner.packageName())) {
                        violations.add("%s names table '%s', which belongs to %s (%s)"
                                .formatted(file, entry.getKey(), owner.id(), owner.packageName()));
                    }
                }
            }
        }

        assertThat(violations).isEmpty();
    }

    /**
     * The scan is only worth trusting if it can tell code from prose, so that part is pinned
     * here rather than discovered by a confusing failure elsewhere.
     */
    @Test
    void literalsAreScannedButCommentsAndCharactersAreNot() {
        String source = """
                // "skill" in a line comment
                /* "skill_version" in a block comment */
                class Example {
                    char quote = '"';
                    String sql = "SELECT * FROM blob";
                    String doc = \"\"\"
                            "namespace" inside a text block
                            \"\"\";
                }
                """;

        String literals = stringLiteralsIn(source);

        // The character literal is the case that makes the first assertion do two jobs: were `'"'`
        // read as the start of a string, it would swallow everything up to the next quote — which is
        // the one opening "SELECT * FROM blob" — and that string would come out as the text of a
        // literal rather than as a literal. So a duplicate assertion here would catch nothing that
        // this one does not.
        assertThat(literals).contains("SELECT * FROM blob").contains("\"namespace\" inside a text block");
        assertThat(literals).doesNotContain("in a line comment").doesNotContain("in a block comment");
    }

    /**
     * The matcher itself, which the rule below can only exercise negatively.
     *
     * <p>"Violations is empty" is equally true when nothing is ever matched, so a one-word edit that
     * makes {@link #mentionsTable} always answer false switches rule 1's code-level half off and
     * leaves the suite green — which is what this pins.
     */
    @Test
    void aTableIsRecognisedOnlyWhereSqlNamesOne() {
        assertThat(mentionsTable("SELECT * FROM skill WHERE id = 1", "skill")).isTrue();
        assertThat(mentionsTable("INSERT INTO blob_content (sha256) VALUES ('x')", "blob_content"))
                .isTrue();
        assertThat(mentionsTable("UPDATE skill_version SET deleted_at = 1", "skill_version"))
                .isTrue();
        assertThat(mentionsTable("DELETE FROM audit_event", "audit_event")).isTrue();
        assertThat(mentionsTable("LOCK TABLE version_file IN SHARE MODE", "version_file")).isTrue();

        assertThat(mentionsTable("SELECT * FROM skill_version", "skill"))
                .as("'_' is a word character, so this does not mention the 'skill' table")
                .isFalse();
        assertThat(mentionsTable("the skill has no SKILL.md at its root", "skill"))
                .as("prose is not a query — the false positive the SQL-context rule exists to remove")
                .isFalse();
    }

    /**
     * The tables one migration leaves behind, added to / removed from an accumulating set.
     *
     * <p>Files are read in name order and share one set, so a table created by V1 and dropped by V3
     * ends up absent — which is the truth about the schema. Names are lower-cased because
     * PostgreSQL folds unquoted identifiers that way, so {@code CREATE TABLE FOO} and
     * {@code DROP TABLE foo} are the same table and have to cancel out.
     */
    static void collectDeclaredTables(String sql, Set<String> into) {
        Matcher created = CREATE_TABLE.matcher(sql);
        while (created.find()) {
            into.add(created.group(1).toLowerCase(Locale.ROOT));
        }
        Matcher dropped = DROP_TABLE.matcher(sql);
        while (dropped.find()) {
            into.remove(dropped.group(1).toLowerCase(Locale.ROOT));
        }
    }

    /**
     * A dropped table stops needing an owner — the half of the rule above that only bites once a
     * migration takes something away, which nothing did until V3 dropped {@code browser_session}.
     *
     * <p>Pinned because the failure it prevents is silent in the other direction: were the
     * subtraction dropped, the suite would go red with a message about a table nobody can find in
     * the schema, and the tempting fix would be to claim it in {@link ModuleMap} again.
     */
    @Test
    void aTableDroppedByALaterMigrationIsNoLongerDeclared() {
        Set<String> declared = new HashSet<>();
        collectDeclaredTables("CREATE TABLE browser_session (\n  session_id TEXT\n);", declared);
        collectDeclaredTables("CREATE TABLE spring_session (primary_id TEXT);", declared);
        assertThat(declared).containsExactlyInAnyOrder("browser_session", "spring_session");

        collectDeclaredTables("DROP TABLE IF EXISTS browser_session;", declared);
        assertThat(declared)
                .as("dropped in a later migration, so no module owns it any more")
                .containsExactly("spring_session");
    }

    private static String packageOf(String source) {
        Matcher matcher = PACKAGE_DECLARATION.matcher(source);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static boolean isInside(String packageName, String modulePackage) {
        return packageName.equals(modulePackage) || packageName.startsWith(modulePackage + ".");
    }

    /**
     * Whether a query actually names the table — that is, whether the name follows one of the
     * keywords that can introduce one.
     *
     * <p>Bare word matching was tried first and was wrong: several tables are ordinary English
     * words, so {@code "the skill has no SKILL.md at its root"} looked like a reference to the
     * {@code skill} table. Requiring SQL context removes that whole class of false positive and
     * still catches what this is for — a query in one module reading another module's table.
     *
     * <p>Word boundaries matter too: {@code '_'} is a word character, so {@code \bskill\b} does not
     * match inside {@code skill_version}, and every table would otherwise appear to be several.
     */
    static boolean mentionsTable(String queries, String table) {
        return Pattern.compile("(?i)\\b(?:from|join|into|update|table)\\s+" + Pattern.quote(table) + "\\b")
                .matcher(queries)
                .find();
    }

    /**
     * Returns the contents of every string literal and text block, and nothing else.
     *
     * <p>Written as a scanner rather than a regex because the three constructs interleave: a
     * {@code //} inside a string is not a comment, a quote inside a comment is not a literal, and
     * a char literal such as {@code '"'} would otherwise look like the start of one.
     */
    static String stringLiteralsIn(String source) {
        StringBuilder literals = new StringBuilder();
        int i = 0;
        int n = source.length();

        while (i < n) {
            char c = source.charAt(i);

            if (c == '/' && i + 1 < n && source.charAt(i + 1) == '/') {
                int end = source.indexOf('\n', i);
                i = end < 0 ? n : end + 1;
            } else if (c == '/' && i + 1 < n && source.charAt(i + 1) == '*') {
                int end = source.indexOf("*/", i + 2);
                i = end < 0 ? n : end + 2;
            } else if (c == '"' && source.startsWith("\"\"\"", i)) {
                int end = source.indexOf("\"\"\"", i + 3);
                literals.append(source, i + 3, end < 0 ? n : end).append('\n');
                i = end < 0 ? n : end + 3;
            } else if (c == '"') {
                StringBuilder content = new StringBuilder();
                int j = i + 1;
                while (j < n) {
                    char d = source.charAt(j);
                    if (d == '\\' && j + 1 < n) {
                        content.append(source.charAt(j + 1));
                        j += 2;
                    } else if (d == '"' || d == '\n') {
                        break;
                    } else {
                        content.append(d);
                        j++;
                    }
                }
                literals.append(content).append('\n');
                i = j + 1;
            } else if (c == '\'') {
                int j = i + 1;
                while (j < n) {
                    char d = source.charAt(j);
                    if (d == '\\') {
                        j += 2;
                    } else if (d == '\'') {
                        break;
                    } else {
                        j++;
                    }
                }
                i = j + 1;
            } else {
                i++;
            }
        }

        return literals.toString();
    }
}
