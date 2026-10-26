package bench;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Builds a readable report from JMH CSV results (results/jdkN.csv). */
public final class Summary {
    private static final String[] IMPLS = {"jdk", "tcnative"};

    /** One benchmark + param combination, e.g. "Full handshake, latency 100 ms per hop". */
    private static final class Case {
        String title, unit;
        int order;
        long latency = -1;
        // jdk version -> impl -> {score, error}
        final SortedMap<Integer, Map<String, double[]>> results = new TreeMap<>();

        String key() {
            return order + "|" + latency;
        }
    }

    public static void main(String[] args) throws IOException {
        Map<String, Case> cases = new HashMap<>();
        for (String file : args) {
            Path path = Paths.get(file);
            Matcher m = Pattern.compile("jdk(\\d+)").matcher(path.getFileName().toString());
            if (!m.find()) continue;
            int jdk = Integer.parseInt(m.group(1));
            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            List<String> header = parse(lines.get(0));
            for (String line : lines.subList(1, lines.size())) {
                if (line.trim().isEmpty()) continue;
                List<String> f = parse(line);
                Case c = new Case();
                String bench = col(header, f, "Benchmark");
                if (bench.endsWith("HandshakeBench.handshake")) {
                    c.title = "Full handshake (new session, no resumption)";
                    c.order = 0;
                } else {
                    c.title = bench;
                    c.order = 1;
                }
                String impl = col(header, f, "Param: impl");
                String lat = col(header, f, "Param: latencyMs");
                if (!lat.isEmpty()) c.latency = Long.parseLong(lat);
                c.unit = col(header, f, "Unit");

                Case existing = cases.get(c.key());
                if (existing == null) cases.put(c.key(), existing = c);
                existing.results.computeIfAbsent(jdk, k -> new HashMap<>())
                        .put(impl, new double[]{num(col(header, f, "Score")), num(col(header, f, "Score Error (99.9%)"))});
            }
        }

        List<Case> sorted = new ArrayList<>(cases.values());
        sorted.sort(Comparator.comparingInt((Case c) -> c.order)
                .thenComparingLong(c -> c.latency));

        System.out.println("TLS 1.3 handshake benchmark: JDK JSSE vs netty-tcnative (BoringSSL)");
        System.out.println("Cipher: TLS_AES_128_GCM_SHA256, P-256 certificate, in-memory transport");
        System.out.println("Values are average time per operation (lower is better); \"±\" is the 99.9% confidence");
        System.out.println("interval, shown only when 3+ measurement iterations were run.");
        System.out.println("\"within noise\" = confidence intervals overlap, no real difference.");

        for (Case c : sorted) {
            StringBuilder t = new StringBuilder(c.title);
            if (c.latency >= 0) t.append(", latency ").append(c.latency).append(" ms per hop");
            System.out.println();
            System.out.println(t + "  [" + c.unit + "]");

            String[] head = {"", "JSSE", "tcnative", "comparison"};
            List<String[]> rows = new ArrayList<>();
            rows.add(head);
            for (Map.Entry<Integer, Map<String, double[]>> e : c.results.entrySet()) {
                String[] row = new String[4];
                row[0] = "Java " + e.getKey();
                for (int i = 0; i < IMPLS.length; i++) row[i + 1] = fmt(e.getValue().get(IMPLS[i]));
                row[3] = compare(e.getValue().get("jdk"), e.getValue().get("tcnative"));
                rows.add(row);
            }
            printTable(rows);
        }
    }

    private static String compare(double[] jdk, double[] tc) {
        if (jdk == null || tc == null || !(jdk[0] > 0) || !(tc[0] > 0)) return "-";
        double r = jdk[0] / tc[0];
        if (Math.abs(r - 1) < 0.005) return "same";
        if (!Double.isNaN(jdk[1]) && !Double.isNaN(tc[1]) && Math.abs(jdk[0] - tc[0]) <= jdk[1] + tc[1]) {
            return "within noise";
        }
        return r > 1
                ? String.format(Locale.ROOT, "tcnative %.2fx faster", r)
                : String.format(Locale.ROOT, "JSSE %.2fx faster", 1 / r);
    }

    private static String fmt(double[] v) {
        if (v == null || Double.isNaN(v[0])) return "-";
        double a = Math.abs(v[0]);
        String f = "%." + (a >= 100 ? 1 : a >= 1 ? 2 : 3) + "f";
        String s = String.format(Locale.ROOT, f, v[0]);
        return Double.isNaN(v[1]) || Double.isInfinite(v[1]) ? s : s + " ± " + String.format(Locale.ROOT, f, v[1]);
    }

    private static void printTable(List<String[]> rows) {
        int[] w = new int[rows.get(0).length];
        for (String[] r : rows) for (int i = 0; i < r.length; i++) w[i] = Math.max(w[i], r[i].length());
        for (int ri = 0; ri < rows.size(); ri++) {
            String[] r = rows.get(ri);
            StringBuilder b = new StringBuilder("  ");
            for (int i = 0; i < r.length; i++) {
                if (i > 0) b.append("   ");
                b.append(i == 0 || i == r.length - 1 ? pad(r[i], w[i]) : lpad(r[i], w[i]));
            }
            System.out.println(b.toString().replaceAll("\\s+$", ""));
            if (ri == 0) {
                int len = 2;
                for (int x : w) len += x + 3;
                StringBuilder sep = new StringBuilder("  ");
                for (int i = 2; i < len - 3; i++) sep.append('-');
                System.out.println(sep);
            }
        }
    }

    private static String col(List<String> header, List<String> f, String name) {
        int i = header.indexOf(name);
        return i < 0 || i >= f.size() ? "" : f.get(i);
    }

    private static List<String> parse(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cur.append(ch);
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (ch == ',' && !quoted) {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(ch);
            }
        }
        out.add(cur.toString());
        return out;
    }

    private static double num(String s) {
        return s.isEmpty() || s.equals("NaN") ? Double.NaN : Double.parseDouble(s.replace(',', '.'));
    }

    private static String pad(String s, int n) {
        StringBuilder b = new StringBuilder(s);
        while (b.length() < n) b.append(' ');
        return b.toString();
    }

    private static String lpad(String s, int n) {
        StringBuilder b = new StringBuilder();
        while (b.length() + s.length() < n) b.append(' ');
        return b.append(s).toString();
    }
}
