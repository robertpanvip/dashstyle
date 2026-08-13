package com.pan.dashstyle;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 独立的颜色工具验证器（纯 Java，不需要 Gradle / IntelliJ 环境）。
 *
 * 与 Util.kt 中 normalizeColor / suggestColorVarName / scanColorsInText 对应纯 Java 实现：
 *  1. 对同一组输入两边跑，得到的结果应当一致。
 *  2. 这里也做一套简单 micro-benchmark（10k 次重复）方便对比性能优化前后的差异。
 *
 * 使用方式（项目根目录执行）：
 *   javac -d /tmp/build src/test/java/com/pan/dashstyle/ColorToolingVerifier.java
 *   java  -cp /tmp/build com.pan.dashstyle.ColorToolingVerifier
 */
@SuppressWarnings({"unchecked", "rawtypes"})
public class ColorToolingVerifier {

    // ============== 归一化：与 Util.normalizeColor 对应 ==============
    private static final Pattern RE_HEX8 = Pattern.compile("#([0-9a-fA-F]{8})\\b");
    private static final Pattern RE_HEX6 = Pattern.compile("#([0-9a-fA-F]{6})\\b");
    private static final Pattern RE_HEX3 = Pattern.compile("#([0-9a-fA-F]{3})\\b");
    private static final Pattern RE_RGBA = Pattern.compile("(?i)rgba?\\(\\s*([^)]*)\\)");
    private static final Pattern RE_HSLA = Pattern.compile("(?i)hsla?\\(\\s*([^)]*)\\)");
    private static final Pattern SPLIT_ARGS = Pattern.compile("[,/\\s]+");

    private static final Set<String> NAMED = new HashSet<>(Arrays.asList(
        "aliceblue","antiquewhite","aqua","aquamarine","azure","beige","bisque","black","blanchedalmond","blue",
        "blueviolet","brown","burlywood","cadetblue","chartreuse","chocolate","coral","cornflowerblue","cornsilk",
        "crimson","cyan","darkblue","darkcyan","darkgoldenrod","darkgray","darkgreen","darkgrey","darkkhaki",
        "darkmagenta","darkolivegreen","darkorange","darkorchid","darkred","darksalmon","darkseagreen","darkslateblue",
        "darkslategray","darkslategrey","darkturquoise","darkviolet","deeppink","deepskyblue","dimgray","dimgrey",
        "dodgerblue","firebrick","floralwhite","forestgreen","fuchsia","gainsboro","ghostwhite","gold","goldenrod",
        "gray","green","greenyellow","grey","honeydew","hotpink","indianred","indigo","ivory","khaki","lavender",
        "lavenderblush","lawngreen","lemonchiffon","lightblue","lightcoral","lightcyan","lightgoldenrodyellow",
        "lightgray","lightgreen","lightgrey","lightpink","lightsalmon","lightseagreen","lightskyblue","lightslategray",
        "lightslategrey","lightsteelblue","lightyellow","lime","limegreen","linen","magenta","maroon",
        "mediumaquamarine","mediumblue","mediumorchid","mediumpurple","mediumseagreen","mediumslateblue",
        "mediumspringgreen","mediumturquoise","mediumvioletred","midnightblue","mintcream","mistyrose","moccasin",
        "navajowhite","navy","oldlace","olive","olivedrab","orange","orangered","orchid","palegoldenrod",
        "palegreen","paleturquoise","palevioletred","papayawhip","peachpuff","peru","pink","plum","powderblue",
        "purple","rebeccapurple","red","rosybrown","royalblue","saddlebrown","salmon","sandybrown","seagreen",
        "seashell","sienna","silver","skyblue","slateblue","slategray","slategrey","snow","springgreen","steelblue",
        "tan","teal","thistle","tomato","turquoise","violet","wheat","white","whitesmoke","yellow","yellowgreen"
    ));

    static String normalizeColor(String raw) {
        if (raw == null) return null;
        String t = raw.trim().toLowerCase(Locale.ROOT);
        if (t.isEmpty()) return null;
        Matcher m;
        m = RE_HEX8.matcher(t);
        if (m.find()) {
            String v = m.group(1);
            String a = v.substring(6, 8);
            return "ff".equals(a) ? "#" + v.substring(0, 6) : "#" + v;
        }
        m = RE_HEX6.matcher(t);
        if (m.find()) return "#" + m.group(1);
        m = RE_HEX3.matcher(t);
        if (m.find()) {
            String v = m.group(1);
            return "#" + v.charAt(0) + v.charAt(0) + v.charAt(1) + v.charAt(1) + v.charAt(2) + v.charAt(2);
        }
        m = RE_RGBA.matcher(t);
        if (m.find()) {
            String[] a = filterBlank(SPLIT_ARGS.split(m.group(1)));
            if (a.length == 3) return "rgb(" + a[0] + "," + a[1] + "," + a[2] + ")";
            if (a.length == 4) {
                String alpha = normalizeAlpha(a[3]);
                return "1".equals(alpha)
                    ? "rgb(" + a[0] + "," + a[1] + "," + a[2] + ")"
                    : "rgba(" + a[0] + "," + a[1] + "," + a[2] + "," + alpha + ")";
            }
            return null;
        }
        m = RE_HSLA.matcher(t);
        if (m.find()) {
            String[] a = filterBlank(SPLIT_ARGS.split(m.group(1)));
            if (a.length == 3) return "hsl(" + a[0] + "," + a[1] + "," + a[2] + ")";
            if (a.length == 4) {
                String alpha = normalizeAlpha(a[3]);
                return "1".equals(alpha)
                    ? "hsl(" + a[0] + "," + a[1] + "," + a[2] + ")"
                    : "hsla(" + a[0] + "," + a[1] + "," + a[2] + "," + alpha + ")";
            }
            return null;
        }
        return NAMED.contains(t) ? t : null;
    }

    private static String normalizeAlpha(String a) {
        String s = a.endsWith("%") ? a.substring(0, a.length() - 1) : a;
        double d;
        try { d = Double.parseDouble(s); } catch (Exception x) { return a; }
        double r = a.endsWith("%") ? d / 100.0 : d;
        String res = String.format(Locale.ROOT, "%.10f", r);
        while (res.endsWith("0")) res = res.substring(0, res.length() - 1);
        if (res.endsWith(".")) res = res.substring(0, res.length() - 1);
        return res.isEmpty() ? "0" : res;
    }

    private static String[] filterBlank(String[] arr) {
        List<String> out = new ArrayList<>(arr.length);
        for (String x : arr) if (x != null && !x.isEmpty()) out.add(x);
        return out.toArray(new String[0]);
    }

    // ============== 语义变量名：与 Util.suggestColorVarName 对应 ==============
    private static final Map<String, String> NAMED_TO_HEX = new HashMap<>();
    static {
        NAMED_TO_HEX.put("white","ffffff"); NAMED_TO_HEX.put("black","000000");
        NAMED_TO_HEX.put("red","ff0000");   NAMED_TO_HEX.put("green","008000");
        NAMED_TO_HEX.put("blue","0000ff");  NAMED_TO_HEX.put("yellow","ffff00");
        NAMED_TO_HEX.put("purple","800080");NAMED_TO_HEX.put("gray","808080");
        NAMED_TO_HEX.put("grey","808080");  NAMED_TO_HEX.put("orange","ffa500");
        NAMED_TO_HEX.put("pink","ffc0cb");  NAMED_TO_HEX.put("cyan","00ffff");
        NAMED_TO_HEX.put("magenta","ff00ff"); NAMED_TO_HEX.put("lime","00ff00");
        NAMED_TO_HEX.put("maroon","800000");NAMED_TO_HEX.put("navy","000080");
        NAMED_TO_HEX.put("olive","808000"); NAMED_TO_HEX.put("teal","008080");
        NAMED_TO_HEX.put("silver","c0c0c0");NAMED_TO_HEX.put("aqua","00ffff");
        NAMED_TO_HEX.put("fuchsia","ff00ff");
    }

    static String suggestColorVarName(String normalized, Set<String> existing, int index) {
        String hex;
        if (normalized.startsWith("#") && normalized.length() >= 7) hex = normalized.substring(1, 7);
        else hex = NAMED_TO_HEX.get(normalized);
        String base = "";
        if (hex != null) {
            int r = Integer.parseInt(hex.substring(0, 2), 16);
            int g = Integer.parseInt(hex.substring(2, 4), 16);
            int b = Integer.parseInt(hex.substring(4, 6), 16);
            int mx = Math.max(Math.max(r, g), b);
            int mn = Math.min(Math.min(r, g), b);
            int diff = mx - mn;
            int sum = r + g + b;
            if (diff < 20) {
                base = sum < 60 ? "dark"
                     : sum < 180 ? "text-dark"
                     : sum < 360 ? "muted"
                     : sum < 600 ? "neutral" : "bg-light";
            } else if (b > r && b > g) {
                base = "primary";
            } else if (r > b && g > b && Math.abs(r - g) <= Math.max(r, g) * 0.55) {
                // 黄/橙/金色调：两个暖色分量都远高于蓝，且 r,g 差距在 55% 以内
                base = "warning";
            } else if (r > g && r > b && sum > 500) {
                base = "accent";
            } else if (r > g && r > b) {
                base = "danger";
            } else if (g > r && g > b) {
                base = "success";
            } else if (r > b && g > b) {
                base = "warning";
            } else if (r == g && r > b) {
                base = "warning";
            }
        }
        String cand = base.isEmpty() ? "--color-" + (index + 1) : "--color-" + base;
        int i = 2;
        while (existing.contains(cand)) {
            cand = base.isEmpty() ? "--color-" + (index + i) : "--color-" + base + "-" + i;
            i++;
        }
        return cand;
    }

    // ============== 扫描：与 Util.scanColorsInText 对应 ==============
    private static final Pattern RE_WORD = Pattern.compile("[A-Za-z][A-Za-z0-9-]*");
    private static final Pattern[] STRUCT = {RE_HEX8, RE_HEX6, RE_HEX3, RE_RGBA, RE_HSLA};

    /** 返回 [原始文本, 归一化, start, end] 的 List<int[]> 用并行字符串列表记录；简单起见输出 List<Object[]>. */
    static List<Object[]> scanColorsInText(String text) {
        int n = text.length();
        List<Object[]> out = new ArrayList<>();
        boolean[] consumed = new boolean[n];
        for (Pattern p : STRUCT) {
            Matcher m = p.matcher(text);
            while (m.find()) {
                int s = m.start(); int e = m.end() - 1;
                if (e >= n) continue;
                if (isAnyConsumed(consumed, s, e)) continue;
                String norm = normalizeColor(m.group());
                if (norm == null) continue;
                Arrays.fill(consumed, s, e + 1, true);
                out.add(new Object[]{m.group(), norm, s, e});
            }
        }
        Matcher wm = RE_WORD.matcher(text);
        while (wm.find()) {
            int s = wm.start(); int e = wm.end() - 1;
            if (e >= n) continue;
            if (isAnyConsumed(consumed, s, e)) continue;
            if (s > 0 && isWordLike(text.charAt(s - 1))) continue;
            if (e + 1 < n && isWordLike(text.charAt(e + 1))) continue;
            String word = text.substring(s, e + 1);
            String lower = word.toLowerCase(Locale.ROOT);
            if (!NAMED.contains(lower)) continue;
            Arrays.fill(consumed, s, e + 1, true);
            out.add(new Object[]{word, lower, s, e});
        }
        out.sort(Comparator.comparingInt(a -> (Integer) a[2]));
        return out;
    }
    private static boolean isAnyConsumed(boolean[] c, int s, int e) {
        for (int i = s; i <= e; i++) if (c[i]) return true;
        return false;
    }
    private static boolean isWordLike(char ch) {
        return Character.isLetterOrDigit(ch) || ch == '_' || ch == '-';
    }

    // ============== 验证与微基准 ==============
    private static int failed = 0;
    private static int passed = 0;
    private static void checkEq(String name, Object expected, Object actual) {
        boolean ok = Objects.equals(expected, actual);
        if (ok) { passed++; System.out.println("  [PASS] " + name); }
        else { failed++; System.out.println("  [FAIL] " + name + "  expected=[" + expected + "]  actual=[" + actual + "]"); }
    }

    static void runNormalizeTests() {
        System.out.println("--- normalizeColor ---");
        checkEq("HEX3 white", "#ffffff", normalizeColor("#fff"));
        checkEq("HEX6 passthrough", "#1a2b3c", normalizeColor("#1A2B3C"));
        checkEq("HEX8 alpha ff drop", "#112233", normalizeColor("#112233ff"));
        checkEq("HEX8 alpha 80", "#11223380", normalizeColor("#11223380"));
        checkEq("rgb spaces", "rgb(255,0,0)", normalizeColor("RGB(255 0 0)"));
        checkEq("rgba alpha=1 -> rgb", "rgb(10,20,30)", normalizeColor("rgba(10,20,30,1)"));
        checkEq("rgba alpha% 50", "rgba(10,20,30,0.5)", normalizeColor("rgba(10,20,30,50%)"));
        checkEq("hsl", "hsl(120,50%,50%)", normalizeColor("hsl(120, 50%, 50%)"));
        checkEq("named case-insensitive", "cornflowerblue", normalizeColor("CornFlowerBlue"));
        checkEq("invalid returns null (2 参数)", null, normalizeColor("rgba(1,2)"));
        checkEq("invalid returns null (#ggg)", null, normalizeColor("#ggg"));
    }

    static void runSuggestTests() {
        System.out.println("--- suggestColorVarName ---");
        checkEq("blue primary", "--color-primary", suggestColorVarName("#2563eb", Collections.emptySet(), 0));
        checkEq("red danger", "--color-danger", suggestColorVarName("#dc2626", Collections.emptySet(), 0));
        checkEq("gray neutral", "--color-neutral", suggestColorVarName("#808080", Collections.emptySet(), 0));
        checkEq("black dark", "--color-dark", suggestColorVarName("#111111", Collections.emptySet(), 0));
        checkEq("green success", "--color-success", suggestColorVarName("#16a34a", Collections.emptySet(), 0));
        checkEq("yellow warning", "--color-warning", suggestColorVarName("#eab308", Collections.emptySet(), 0));
        Set<String> ex = new HashSet<>(); ex.add("--color-primary");
        checkEq("collision -> primary-2", "--color-primary-2", suggestColorVarName("#2563eb", ex, 0));
        checkEq("hsl no-semantic index 0", "--color-1", suggestColorVarName("hsl(1,2%,3%)", Collections.emptySet(), 0));
    }

    static void runScanTests() {
        System.out.println("--- scanColorsInText ---");
        String css = ".a { color: #fff; border: 1px solid red; background: rgb(255, 0, 0); }";
        List<Object[]> out = scanColorsInText(css);
        Set<String> norms = new HashSet<>();
        for (Object[] r : out) norms.add((String) r[1]);
        checkEq("scan contains #fff expand #ffffff", true, norms.contains("#ffffff"));
        checkEq("scan contains red", true, norms.contains("red"));
        checkEq("scan contains rgb(255,0,0)", true, norms.contains("rgb(255,0,0)"));
        // HEX6 只识别一个（不会和 HEX3 重叠）
        String s2 = ".x { color: #abcdef; }";
        checkEq("scan HEX6 only 1", 1, scanColorsInText(s2).size());
        // 边界：white-space 里 white 应排除
        String s3 = ".a { x: 1; white-space: nowrap; }";
        List<Object[]> o3 = scanColorsInText(s3);
        int c = 0;
        for (Object[] r : o3) if ("white".equals(r[1])) c++;
        checkEq("scan white in white-space excluded", 0, c);
        // offsets sorted
        List<Object[]> r4 = scanColorsInText(".a{color:#fff;background:blue}");
        for (int i = 1; i < r4.size(); i++) {
            int prev = (Integer) r4.get(i - 1)[2];
            int curr = (Integer) r4.get(i)[2];
            if (prev > curr) { checkEq("scan offset sorted fail i=" + i, true, false); break; }
        }
        if (r4.size() >= 2) checkEq("scan offsets sorted (sample)", true, true);
        // roundtrip substring
        String src = "x: rgba(1,2,3,0.5); y: #12345678;";
        List<Object[]> ro = scanColorsInText(src);
        int ok = 0;
        for (Object[] a : ro) {
            String orig = (String) a[0];
            int s = (Integer) a[2]; int e = (Integer) a[3];
            if (orig.equals(src.substring(s, e + 1))) ok++;
        }
        checkEq("scan range roundtrip", ro.size(), ok);
    }

    static void runBenchmark() {
        System.out.println("\n--- micro-bench (颜色工具 10k iterations 热身 2k) ---");
        String big = buildBigCss(50);
        // warm
        for (int i = 0; i < 2000; i++) { normalizeColor("#" + Integer.toHexString((i & 0xFFFFFF) | 0xFF000000)); }
        long t0 = System.nanoTime();
        for (int i = 0; i < 10_000; i++) scanColorsInText(big);
        long t1 = System.nanoTime();
        double usPerIter = (t1 - t0) / 10_000.0 / 1000.0;
        System.out.printf(Locale.ROOT,
            "scanColorsInText( ~%d colors*50 = %d chars, 10k iters): total=%.1f ms, avg=%.1f µs/iter%n",
            countMatches(big, "#"), big.length(), (t1 - t0) / 1_000_000.0, usPerIter);
    }

    private static int countMatches(String s, String sub) {
        int c = 0; int p = -1;
        while ((p = s.indexOf(sub, p + 1)) != -1) c++;
        return c;
    }

    private static String buildBigCss(int perGroup) {
        StringBuilder sb = new StringBuilder();
        String[] pal = {
            "#fff", "#111111", "#2563eb", "#dc2626", "#16a34a", "#eab308",
            "red", "white", "cornflowerblue", "rgb(255,0,0)", "rgba(10,20,30,0.5)",
            "hsl(120,50%,50%)", "#112233aa"
        };
        for (int g = 0; g < perGroup; g++) {
            sb.append(".cls").append(g).append("{\n");
            for (int j = 0; j < pal.length; j++) {
                sb.append("  color").append(j).append(':').append(pal[j]).append(";\n");
            }
            sb.append("}\n");
        }
        return sb.toString();
    }

    public static void main(String[] args) {
        runNormalizeTests();
        runSuggestTests();
        runScanTests();
        runBenchmark();
        System.out.println();
        System.out.println("TOTAL: passed=" + passed + "  failed=" + failed);
        if (failed > 0) System.exit(1);
    }
}
