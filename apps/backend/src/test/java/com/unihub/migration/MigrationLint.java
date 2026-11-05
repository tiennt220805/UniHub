package com.unihub.migration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The static half of INV-15 (Phase 7 checklist D88; database-schema.md §10, "Expand/contract
 * rule"): a new migration runs while the previous release still serves, so it may only
 * <em>expand</em> the schema. This finds, in a migration's SQL, what an old pod could not survive:
 *
 * <ul>
 *   <li>{@code DROP TABLE}, {@code DROP COLUMN};
 *   <li>{@code RENAME} of anything;
 *   <li>{@code ALTER COLUMN … TYPE};
 *   <li>{@code ALTER COLUMN … SET NOT NULL};
 *   <li>{@code ADD COLUMN … NOT NULL} with no {@code DEFAULT} (an old writer does not know the
 *       column, so its inserts fail).
 * </ul>
 *
 * The one way past is the <em>contract step</em>: a comment {@code -- contract-step: <which release
 * stopped using the object>} anywhere in the file. Comments and single-quoted strings are blanked
 * before matching, so prose about these statements is never mistaken for one; dollar-quoted bodies
 * are not blanked, since a {@code DO $$} block runs real DDL.
 *
 * <p>Known gap, recorded rather than hidden: only the files handed in are checked ({@link
 * #newest(Path)} is the newest one, as D88 decides), so two migrations added in one commit leave
 * the older of them unchecked.
 */
final class MigrationLint {

    /** A forbidden statement and where it is. */
    record Violation(String rule, int line, String statement) {}

    static final String CONTRACT_MARKER = "contract-step:";

    private static final Pattern MARKER = Pattern.compile("--[ \\t]*contract-step:[ \\t]*\\S.{8,}");
    private static final Pattern VERSIONED = Pattern.compile("V(\\d+(?:_\\d+)*)__.+\\.sql");

    private static final List<Rule> STATEMENT_RULES =
            List.of(
                    new Rule("DROP TABLE", Pattern.compile("\\bdrop\\s+table\\b")),
                    new Rule("DROP COLUMN", Pattern.compile("\\bdrop\\s+column\\b")),
                    new Rule("RENAME", Pattern.compile("\\brename\\b")),
                    new Rule(
                            "ALTER COLUMN TYPE",
                            Pattern.compile(
                                    "\\balter\\s+(column\\s+)?\\S+\\s+(set\\s+data\\s+)?type\\b")),
                    new Rule(
                            "SET NOT NULL",
                            Pattern.compile(
                                    "\\balter\\s+(column\\s+)?\\S+\\s+set\\s+not\\s+null\\b")));

    private record Rule(String name, Pattern pattern) {}

    private MigrationLint() {}

    /**
     * The file with the highest Flyway version in {@code directory} ({@code V1 < V1_1 < V2 < V10}).
     */
    static Path newest(Path directory) throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(f -> VERSIONED.matcher(f.getFileName().toString()).matches())
                    .max(Comparator.comparing(MigrationLint::version, MigrationLint::compare))
                    .orElseThrow(() -> new IllegalStateException("no migration in " + directory));
        }
    }

    private static int[] version(Path file) {
        var matcher = VERSIONED.matcher(file.getFileName().toString());
        matcher.matches();
        return java.util.Arrays.stream(matcher.group(1).split("_"))
                .mapToInt(Integer::parseInt)
                .toArray();
    }

    private static int compare(int[] a, int[] b) {
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int x = i < a.length ? a[i] : 0;
            int y = i < b.length ? b[i] : 0;
            if (x != y) {
                return Integer.compare(x, y);
            }
        }
        return 0;
    }

    static List<Violation> check(Path migration) throws IOException {
        return check(Files.readString(migration, StandardCharsets.UTF_8));
    }

    /** The violations in {@code sql}, or none when it carries the contract-step marker. */
    static List<Violation> check(String sql) {
        if (MARKER.matcher(sql).find()) {
            return List.of();
        }
        var violations = new ArrayList<Violation>();
        var blanked = blank(sql);
        int line = 1;
        for (var statement : blanked.split(";", -1)) {
            int statementLine = line + leadingNewlines(statement);
            var normalized = statement.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
            for (var rule : STATEMENT_RULES) {
                if (rule.pattern().matcher(normalized).find()) {
                    violations.add(new Violation(rule.name(), statementLine, normalized));
                }
            }
            if (normalized.startsWith("alter table")
                    && addsRequiredColumnWithoutDefault(normalized)) {
                violations.add(
                        new Violation(
                                "ADD COLUMN NOT NULL without DEFAULT", statementLine, normalized));
            }
            line += (int) statement.chars().filter(c -> c == '\n').count();
        }
        return violations;
    }

    private static int leadingNewlines(String statement) {
        int n = 0;
        for (char c : statement.toCharArray()) {
            if (c == '\n') {
                n++;
            } else if (!Character.isWhitespace(c)) {
                break;
            }
        }
        return n;
    }

    /** True when some action of an ALTER TABLE adds a NOT NULL column that has no default. */
    private static boolean addsRequiredColumnWithoutDefault(String alterTable) {
        for (var action : topLevelActions(alterTable)) {
            if (!action.startsWith("add ") && !action.contains(" add ")) {
                continue;
            }
            var add = action.substring(action.indexOf("add ") + 4).trim();
            if (add.startsWith("column ")) {
                add = add.substring("column ".length());
            } else if (add.matches("^(constraint|primary|unique|check|foreign|exclude)\\b.*")) {
                continue; // a constraint, not a column
            }
            add = add.replaceFirst("^if not exists ", "");
            boolean notNull = Pattern.compile("\\bnot null\\b").matcher(add).find();
            boolean hasDefault =
                    Pattern.compile("\\b(default|generated|(big|small)?serial)\\b")
                            .matcher(add)
                            .find();
            if (notNull && !hasDefault) {
                return true;
            }
        }
        return false;
    }

    /** The comma-separated actions of an ALTER TABLE, split outside parentheses. */
    private static List<String> topLevelActions(String alterTable) {
        var actions = new ArrayList<String>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < alterTable.length(); i++) {
            char c = alterTable.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (c == ',' && depth == 0) {
                actions.add(alterTable.substring(start, i).trim());
                start = i + 1;
            }
        }
        actions.add(alterTable.substring(start).trim());
        return actions;
    }

    /**
     * The SQL with comments and single-quoted strings replaced by spaces (newlines kept, so line
     * numbers stay true). Dollar-quoted text is left alone.
     */
    static String blank(String sql) {
        var out = new StringBuilder(sql.length());
        int i = 0;
        int n = sql.length();
        while (i < n) {
            char c = sql.charAt(i);
            if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                while (i < n && sql.charAt(i) != '\n') {
                    out.append(' ');
                    i++;
                }
            } else if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                int end = sql.indexOf("*/", i + 2);
                int stop = end < 0 ? n : end + 2;
                for (; i < stop; i++) {
                    out.append(sql.charAt(i) == '\n' ? '\n' : ' ');
                }
            } else if (c == '\'') {
                out.append(' ');
                i++;
                while (i < n) {
                    char s = sql.charAt(i);
                    if (s == '\'' && i + 1 < n && sql.charAt(i + 1) == '\'') {
                        out.append("  ");
                        i += 2;
                    } else if (s == '\'') {
                        out.append(' ');
                        i++;
                        break;
                    } else {
                        out.append(s == '\n' ? '\n' : ' ');
                        i++;
                    }
                }
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }
}
