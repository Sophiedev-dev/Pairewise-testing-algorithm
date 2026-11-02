import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class Main {

    static final File DIR = findBaseDir();
    static final File JENNY = new File(DIR, "jenny.exe");
    // Dimension 1 -> a, 2 -> b, 3 -> c. Feature letter -> numeric value.
    static final String[] COEFFICIENTS = {"a", "b", "c"};
    // Fixed values for the first features; beyond that, random integers are generated.
    static final int[] BASE_VALUES = {0, 5, -5, 1, -2};
    // Feature letters in jenny's order: a..z then A..Z.
    static final String JENNY_LETTERS =
        "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
    // Hard limits of jenny.exe itself (it refuses fewer than 2 or more than 52 features).
    static final int MIN_FEATURES = 2;
    static final int MAX_FEATURES = JENNY_LETTERS.length();

    // Tests imposed to jenny (read on stdin with -o), then extended to cover all pairs.
    // x^2 - 2x + 1 = 0 guarantees a Δ = 0 case (only possible with >= 4/5/4 choices).
    static final List<Map<String, Integer>> REQUIRED_TESTS =
        Collections.singletonList(coeffs(1, -2, 1));

    static Map<String, Integer> coeffs(int a, int b, int c) {
        Map<String, Integer> m = new LinkedHashMap<>();
        m.put("a", a);
        m.put("b", b);
        m.put("c", c);
        return m;
    }

    // Rebuilt from the user's input in run() by buildFeatureValues().
    static Map<Character, Integer> FEATURE_VALUES = new LinkedHashMap<>();

    // jenny.exe sits next to Main.class (or Main.java); fall back to the current directory.
    static File findBaseDir() {
        try {
            File loc = new File(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            File dir = loc.isDirectory() ? loc : loc.getParentFile();
            if (dir != null && new File(dir, "jenny.exe").exists()) return dir;
        } catch (Exception ignored) {
        }
        return new File(System.getProperty("user.dir"));
    }

    static int randomInt(int min, int max) {
        return ThreadLocalRandom.current().nextInt(min, max + 1);
    }

    // n letter -> value pairs: BASE_VALUES first, then distinct random integers
    // (positive or negative), e.g. 3 -> { a: 0, b: 5, c: -5 }.
    // Values must stay unique so a value maps back to a single letter.
    static Map<Character, Integer> buildFeatureValues(int n) {
        List<Integer> values = new ArrayList<>();
        for (int i = 0; i < Math.min(n, BASE_VALUES.length); i++) values.add(BASE_VALUES[i]);
        int range = Math.max(10, 2 * n);
        while (values.size() < n) {
            int v = randomInt(-range, range);
            if (!values.contains(v)) values.add(v);
        }
        Map<Character, Integer> map = new LinkedHashMap<>();
        for (int i = 0; i < values.size(); i++) map.put(JENNY_LETTERS.charAt(i), values.get(i));
        return map;
    }

    // Explain why a token is not a positive integer.
    static String describeInvalidToken(String tok) {
        if (Pattern.compile("\\p{L}").matcher(tok).find()) {
            return "\"" + tok + "\" contient des lettres. Veuillez entrer un nombre.";
        }
        if (tok.matches("-\\d+([.,]\\d+)?")) {
            return "\"" + tok + "\" est un nombre négatif. Veuillez entrer un nombre entier positif.";
        }
        if (tok.matches("\\d*[.,]\\d+")) {
            return "\"" + tok + "\" est un nombre décimal. Veuillez entrer un nombre entier.";
        }
        return "\"" + tok + "\" contient un symbole. Veuillez entrer un nombre.";
    }

    // Result of parseDimensions: either dims or an error message to show.
    static final class ParseResult {
        final int[] dims;
        final String error;

        ParseResult(int[] dims, String error) {
            this.dims = dims;
            this.error = error;
        }
    }

    // Validate a line like "8 8 8" (or "8" as shorthand for "8 8 8").
    static ParseResult parseDimensions(String input) {
        String trimmed = input.trim();
        List<String> tokens = trimmed.isEmpty()
            ? new ArrayList<>()
            : new ArrayList<>(Arrays.asList(trimmed.split("\\s+")));
        if (tokens.isEmpty()) {
            return new ParseResult(null, "Vous n'avez rien saisi. Veuillez entrer un nombre.");
        }
        for (String tok : tokens) {
            if (!tok.matches("\\d+")) return new ParseResult(null, describeInvalidToken(tok));
        }
        if (tokens.size() == 1) {
            tokens.add(tokens.get(0));
            tokens.add(tokens.get(0));
        }
        if (tokens.size() != COEFFICIENTS.length) {
            return new ParseResult(null, "Il faut " + COEFFICIENTS.length
                + " nombres (un par coefficient " + String.join(", ", COEFFICIENTS)
                + "), vous en avez saisi " + tokens.size() + ".");
        }
        // Huge numbers are clamped: they only need to compare as "too big".
        long[] values = tokens.stream()
            .mapToLong(t -> t.length() > 18 ? Long.MAX_VALUE : Long.parseLong(t))
            .toArray();
        for (long d : values) {
            if (d < MIN_FEATURES) {
                return new ParseResult(null, d + " est trop petit : jenny exige au moins "
                    + MIN_FEATURES + " choix par coefficient.");
            }
        }
        for (int i = 0; i < values.length; i++) {
            if (values[i] > MAX_FEATURES) {
                return new ParseResult(null, tokens.get(i)
                    + " est trop grand : jenny.exe accepte au maximum " + MAX_FEATURES
                    + " choix par coefficient (lettres a-z puis A-Z).");
            }
        }
        return new ParseResult(Arrays.stream(values).mapToInt(v -> (int) v).toArray(), null);
    }

    static String fmt(double x) {
        long r = Math.round(x * 1e4);
        if (r == 0) return "0";
        return BigDecimal.valueOf(r, 4).stripTrailingZeros().toPlainString();
    }

    // Solve a*x^2 + b*x + c = 0.
    static String solveQuadratic(int a, int b, int c) {
        if (a == 0) {
            if (b == 0) {
                return c == 0
                    ? "Infinité de solutions (0 = 0)"
                    : "Aucune solution (" + fmt(c) + " = 0 impossible)";
            }
            double x = (double) -c / b;
            return "Équation du 1er degré : x = " + fmt(x);
        }

        double delta = (double) b * b - 4.0 * a * c;
        if (delta > 0) {
            double s = Math.sqrt(delta);
            double x1 = (-b - s) / (2.0 * a);
            double x2 = (-b + s) / (2.0 * a);
            return "Δ=" + fmt(delta) + " > 0 : x1 = " + fmt(x1) + ", x2 = " + fmt(x2);
        }
        if (delta == 0) {
            double x = -b / (2.0 * a);
            return "Δ=0 : racine double x = " + fmt(x);
        }
        double re = -b / (2.0 * a);
        double im = Math.sqrt(-delta) / (2.0 * Math.abs(a));
        return "Δ=" + fmt(delta) + " < 0 : x = " + fmt(re) + " ± " + fmt(im) + "i";
    }

    // Parse jenny output: one test per line, tokens like "1a 2b 3c".
    // jenny reports its own errors on stdout ("jenny: ...") with exit code 0.
    static List<String[]> parseJenny(String stdout) {
        List<String> lines = Arrays.stream(stdout.split("\\r?\\n"))
            .map(String::trim)
            .filter(line -> !line.isEmpty() && !line.startsWith("Could not cover"))
            .collect(Collectors.toList());
        for (String line : lines) {
            if (line.startsWith("jenny:")) throw new RuntimeException(line);
        }
        return lines.stream().map(line -> line.split("\\s+")).collect(Collectors.toList());
    }

    static final Pattern TOKEN = Pattern.compile("^(\\d+)([a-zA-Z])$");

    static Map<String, Integer> tokensToCoefficients(String[] tokens) {
        Map<String, Integer> coeffs = new LinkedHashMap<>();
        for (String tok : tokens) {
            Matcher m = TOKEN.matcher(tok);
            if (!m.matches()) throw new RuntimeException("Jeton jenny invalide : \"" + tok + "\"");
            String digits = m.group(1);
            int index = digits.length() > 9 ? -1 : Integer.parseInt(digits) - 1;
            String name = index >= 0 && index < COEFFICIENTS.length ? COEFFICIENTS[index] : null;
            Integer value = FEATURE_VALUES.get(m.group(2).charAt(0));
            if (name == null || value == null) {
                throw new RuntimeException("Jeton jenny non pris en charge : \"" + tok + "\"");
            }
            coeffs.put(name, value);
        }
        for (String name : COEFFICIENTS) {
            if (!coeffs.containsKey(name)) {
                throw new RuntimeException("Coefficient " + name + " manquant dans \""
                    + String.join(" ", tokens) + "\"");
            }
        }
        return coeffs;
    }

    // Inverse of tokensToCoefficients: { a: 1, b: -2, c: 1 } -> " 1d 2e 3d ".
    static String coefficientsToTestLine(Map<String, Integer> coeffs) {
        List<String> tokens = new ArrayList<>();
        for (int i = 0; i < COEFFICIENTS.length; i++) {
            Integer wanted = coeffs.get(COEFFICIENTS[i]);
            Character letter = null;
            for (Map.Entry<Character, Integer> e : FEATURE_VALUES.entrySet()) {
                if (e.getValue().equals(wanted)) {
                    letter = e.getKey();
                    break;
                }
            }
            if (letter == null) {
                throw new RuntimeException("Valeur " + wanted + " absente de FEATURE_VALUES");
            }
            tokens.add((i + 1) + "" + letter);
        }
        return " " + String.join(" ", tokens) + " \n";
    }

    // A required test can only be passed to jenny if each value exists in its dimension
    // (dimension i uses the first dims[i] entries of FEATURE_VALUES).
    static boolean fitsDimensions(Map<String, Integer> coeffs, int[] dims) {
        List<Integer> values = new ArrayList<>(FEATURE_VALUES.values());
        for (int i = 0; i < COEFFICIENTS.length; i++) {
            if (!values.subList(0, Math.min(dims[i], values.size())).contains(coeffs.get(COEFFICIENTS[i]))) {
                return false;
            }
        }
        return true;
    }

    static void printTable(List<Object[]> rows) {
        String[] headers = {"ID", "Jetons Jenny", "a", "b", "c", "Résultat"};
        int[] widths = new int[headers.length];
        for (int i = 0; i < headers.length; i++) {
            widths[i] = headers[i].length();
            for (Object[] r : rows) widths[i] = Math.max(widths[i], String.valueOf(r[i]).length());
        }

        System.out.println(line(widths, "┌", "┬", "┐"));
        System.out.println(row(widths, headers));
        System.out.println(line(widths, "├", "┼", "┤"));
        for (Object[] r : rows) System.out.println(row(widths, r));
        System.out.println(line(widths, "└", "┴", "┘"));
    }

    static String line(int[] widths, String l, String m, String r) {
        return l + Arrays.stream(widths).mapToObj(w -> repeat("─", w + 2)).collect(Collectors.joining(m)) + r;
    }

    static String row(int[] widths, Object[] cells) {
        StringBuilder sb = new StringBuilder("│");
        for (int i = 0; i < cells.length; i++) {
            String s = String.valueOf(cells[i]);
            String pad = repeat(" ", widths[i] - s.length());
            // Right-align numeric columns.
            sb.append(' ').append(i >= 2 && i <= 4 ? pad + s : s + pad).append(' ');
            sb.append(i < cells.length - 1 ? "│" : "");
        }
        return sb.append("│").toString();
    }

    // Returns true on success, false on failure (errors already printed).
    static boolean run(int[] dims) {
        FEATURE_VALUES = buildFeatureValues(Arrays.stream(dims).max().getAsInt());
        List<String> jennyArgs = new ArrayList<>();
        jennyArgs.add("-o");
        for (int d : dims) jennyArgs.add(String.valueOf(d));
        List<Map<String, Integer>> required = REQUIRED_TESTS.stream()
            .filter(t -> fitsDimensions(t, dims))
            .collect(Collectors.toList());

        List<String> command = new ArrayList<>();
        command.add(JENNY.getPath());
        command.addAll(jennyArgs);

        String stdout;
        String stderr;
        try {
            Process child = new ProcessBuilder(command).directory(DIR).start();
            CompletableFuture<String> out = CompletableFuture.supplyAsync(() -> readAll(child.getInputStream()));
            CompletableFuture<String> err = CompletableFuture.supplyAsync(() -> readAll(child.getErrorStream()));

            // jenny -o without a file name reads old tests on stdin until a "." line.
            StringBuilder input = new StringBuilder();
            for (Map<String, Integer> t : required) input.append(coefficientsToTestLine(t));
            input.append(".\n");
            try (OutputStream stdin = child.getOutputStream()) {
                stdin.write(input.toString().getBytes(StandardCharsets.UTF_8));
            }

            int code = child.waitFor();
            stdout = out.join();
            stderr = err.join();
            if (code != 0) {
                System.err.println("Échec de l'exécution de " + JENNY + " " + String.join(" ", jennyArgs)
                    + " : Command failed with exit code " + code);
                if (!stderr.isEmpty()) System.err.println(stderr);
                return false;
            }
        } catch (IOException | InterruptedException | RuntimeException e) {
            System.err.println("Échec de l'exécution de " + JENNY + " " + String.join(" ", jennyArgs)
                + " : " + e.getMessage());
            return false;
        }

        List<Object[]> rows = new ArrayList<>();
        try {
            List<String[]> tests = parseJenny(stdout);
            for (int i = 0; i < tests.size(); i++) {
                String[] tokens = tests.get(i);
                Map<String, Integer> co = tokensToCoefficients(tokens);
                int a = co.get("a"), b = co.get("b"), c = co.get("c");
                rows.add(new Object[] {i + 1, String.join(" ", tokens), a, b, c, solveQuadratic(a, b, c)});
            }
        } catch (RuntimeException e) {
            System.err.println("Erreur : " + e.getMessage());
            return false;
        }

        String mapping = FEATURE_VALUES.entrySet().stream()
            .map(e -> e.getKey() + "=" + e.getValue())
            .collect(Collectors.joining(", "));
        System.out.println("\njenny " + String.join(" ", jennyArgs) + " → " + rows.size()
            + " tests (" + mapping + ")\n");
        printTable(rows);
        return true;
    }

    static String repeat(String s, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(s);
        return sb.toString();
    }

    static String readAll(java.io.InputStream in) {
        try {
            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            for (int n; (n = in.read(chunk)) != -1; ) buf.write(chunk, 0, n);
            return new String(buf.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static void main(String[] args) throws IOException {
        BufferedReader rl = new BufferedReader(new InputStreamReader(System.in));
        while (true) {
            System.out.print("Entrez les dimensions pour Jenny (ex: 3 3 3, 5 5 5 ou 8 8 8) : ");
            System.out.flush();
            String answer = rl.readLine();
            // Ctrl+D / Ctrl+Z before a valid answer: quit.
            if (answer == null) System.exit(0);
            ParseResult parsed = parseDimensions(answer);
            if (parsed.error != null) {
                System.out.println("  ✗ " + parsed.error + "\n");
                continue;
            }
            System.exit(run(parsed.dims) ? 0 : 1);
        }
    }
}
