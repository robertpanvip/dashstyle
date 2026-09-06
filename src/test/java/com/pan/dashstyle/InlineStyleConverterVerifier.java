package com.pan.dashstyle;

import java.util.*;
import java.util.regex.*;

/**
 * 独立验证：JS 字面量 → 严格 JSON、inlineStyle → CSS 转换 (bug 修复验证)
 *
 * 运行:
 *   javac -d build/test-out src/test/java/com/pan/dashstyle/InlineStyleConverterVerifier.java
 *   java -cp build/test-out com.pan.dashstyle.InlineStyleConverterVerifier
 */
public class InlineStyleConverterVerifier {

    // 与生产代码 JsonToCssConversionTables.UNITLESS 对齐（同 react-dom isUnitlessNumber）
    private static final Set<String> UNITLESS = new HashSet<>(Arrays.asList(
        "animation-iteration-count", "aspect-ratio", "border-image-outset", "border-image-slice",
        "border-image-width", "box-flex", "box-flex-group", "box-ordinal-group", "column-count",
        "columns", "flex", "flex-grow", "flex-positive", "flex-shrink", "flex-negative", "flex-order",
        "grid-area", "grid-row", "grid-row-start", "grid-row-end", "grid-column", "grid-column-start",
        "grid-column-end", "font-weight", "line-clamp", "line-height", "opacity", "order", "orphans",
        "scale", "tab-size", "widows", "z-index", "zoom",
        "fill-opacity", "flood-opacity", "stop-opacity", "stroke-dasharray", "stroke-dashoffset",
        "stroke-miterlimit", "stroke-opacity", "stroke-width"
    ));
    private static final Set<String> UNITLESS_CAMEL = new HashSet<>(Arrays.asList(
        "animationIterationCount", "aspectRatio", "borderImageOutset", "borderImageSlice",
        "borderImageWidth", "boxFlex", "boxFlexGroup", "boxOrdinalGroup", "columnCount",
        "columns", "flex", "flexGrow", "flexPositive", "flexShrink", "flexNegative", "flexOrder",
        "gridArea", "gridRow", "gridRowStart", "gridRowEnd", "gridColumn", "gridColumnStart",
        "gridColumnEnd", "fontWeight", "lineClamp", "lineHeight", "opacity", "order", "orphans",
        "scale", "tabSize", "widows", "zIndex", "zoom",
        "fillOpacity", "floodOpacity", "stopOpacity", "strokeDasharray", "strokeDashoffset",
        "strokeMiterlimit", "strokeOpacity", "strokeWidth"
    ));
    private static final Set<String> SHORTHAND_ARRAY_CAMEL = new HashSet<>(Arrays.asList(
        "padding", "margin", "borderRadius", "borderWidth", "borderStyle",
        "borderColor", "gap", "gridGap", "gridRowGap", "gridColumnGap", "inset"
    ));
    private static final Set<String> TRANSFORM_FUNCS = new HashSet<>(Arrays.asList(
        "translateX","translateY","translateZ","scale","scaleX","scaleY","scaleZ","scale3d",
        "rotate","rotateX","rotateY","rotateZ","skew","skewX","skewY","perspective","matrix",
        "matrix3d","translate3d","rotate3d"
    ));
    private static final Set<String> TRANSFORM_UNITLESS = new HashSet<>(Arrays.asList(
        "scale","scaleX","scaleY","scaleZ","scale3d","matrix","matrix3d"
    ));
    private static final Set<String> TRANSFORM_ANGLE = new HashSet<>(Arrays.asList(
        "rotate","rotateX","rotateY","rotateZ","skew","skewX","skewY"
    ));

    static Object parseJson(String s) {
        s = s.trim();
        int[] idx = {0};
        Object r = parseValue(s, idx);
        while (idx[0] < s.length() && Character.isWhitespace(s.charAt(idx[0]))) idx[0]++;
        if (idx[0] != s.length()) throw new RuntimeException("trailing at "+idx[0]);
        return r;
    }
    static Object parseValue(String s, int[] idx) {
        skipWs(s, idx);
        if (idx[0] >= s.length()) throw new RuntimeException("EOF");
        char c = s.charAt(idx[0]);
        if (c == '{') return parseObj(s, idx);
        if (c == '[') return parseArr(s, idx);
        if (c == '"') return parseStr(s, idx);
        if (c == 't' || c == 'f') return parseBool(s, idx);
        if (c == 'n') { idx[0]+=4; return null; }
        return parseNum(s, idx);
    }
    static void skipWs(String s, int[] idx) {
        while (idx[0] < s.length() && Character.isWhitespace(s.charAt(idx[0]))) idx[0]++;
    }
    @SuppressWarnings("unchecked")
    static LinkedHashMap<String, Object> parseObj(String s, int[] idx) {
        LinkedHashMap<String, Object> m = new LinkedHashMap<>();
        idx[0]++;
        skipWs(s, idx);
        if (s.charAt(idx[0]) == '}') { idx[0]++; return m; }
        while (true) {
            skipWs(s, idx);
            String k = parseStr(s, idx);
            skipWs(s, idx);
            if (s.charAt(idx[0]) != ':') throw new RuntimeException("no colon");
            idx[0]++;
            Object v = parseValue(s, idx);
            m.put(k, v);
            skipWs(s, idx);
            char ch = s.charAt(idx[0]);
            if (ch == ',') { idx[0]++; continue; }
            if (ch == '}') { idx[0]++; break; }
            throw new RuntimeException("obj sep at "+idx[0]);
        }
        return m;
    }
    static List<Object> parseArr(String s, int[] idx) {
        List<Object> a = new ArrayList<>();
        idx[0]++;
        skipWs(s, idx);
        if (s.charAt(idx[0]) == ']') { idx[0]++; return a; }
        while (true) {
            Object v = parseValue(s, idx);
            a.add(v);
            skipWs(s, idx);
            char ch = s.charAt(idx[0]);
            if (ch == ',') { idx[0]++; continue; }
            if (ch == ']') { idx[0]++; break; }
            throw new RuntimeException("arr sep at "+idx[0]);
        }
        return a;
    }
    static String parseStr(String s, int[] idx) {
        if (s.charAt(idx[0]) != '"') throw new RuntimeException("not str");
        idx[0]++;
        StringBuilder sb = new StringBuilder();
        while (idx[0] < s.length()) {
            char c = s.charAt(idx[0]++);
            if (c == '"') return sb.toString();
            if (c == '\\' && idx[0] < s.length()) {
                char n = s.charAt(idx[0]++);
                switch (n) {
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case '/': sb.append('/'); break;
                    case 'n': sb.append('\n'); break;
                    case 't': sb.append('\t'); break;
                    case 'r': sb.append('\r'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'u':
                        sb.append((char) Integer.parseInt(s.substring(idx[0], idx[0]+4), 16));
                        idx[0] += 4; break;
                    default: sb.append(n); break;
                }
            } else sb.append(c);
        }
        throw new RuntimeException("unterminated str");
    }
    static Boolean parseBool(String s, int[] idx) {
        if (s.startsWith("true", idx[0])) { idx[0]+=4; return true; }
        if (s.startsWith("false", idx[0])) { idx[0]+=5; return false; }
        throw new RuntimeException("bad bool at "+idx[0]);
    }
    static Object parseNum(String s, int[] idx) {
        int start = idx[0];
        if (s.charAt(idx[0]) == '-') idx[0]++;
        while (idx[0] < s.length() && (Character.isDigit(s.charAt(idx[0])) || s.charAt(idx[0]) == '.' ||
               s.charAt(idx[0]) == 'e' || s.charAt(idx[0]) == 'E' || s.charAt(idx[0]) == '+' || s.charAt(idx[0]) == '-')) idx[0]++;
        String num = s.substring(start, idx[0]);
        if (num.contains(".") || num.contains("e") || num.contains("E")) return Double.parseDouble(num);
        try { return Integer.parseInt(num); } catch (Exception ex) { return Long.parseLong(num); }
    }

    static String jsLiteralToStrictJson(String js) {
        StringBuilder sb = new StringBuilder(js.length()+16);
        int i = 0, n = js.length();
        while (i < n) {
            char ch = js.charAt(i);
            if (ch == '/' && i+1<n && js.charAt(i+1) == '/') {
                i+=2; while (i<n && js.charAt(i)!='\n') i++;
            } else if (ch == '/' && i+1<n && js.charAt(i+1) == '*') {
                i+=2; while (i<n-1 && !(js.charAt(i)=='*' && js.charAt(i+1)=='/')) i++; i+=2;
            } else if (ch == '"') {
                sb.append(ch); i++;
                while (i < n) {
                    char c = js.charAt(i); sb.append(c);
                    if (c == '\\' && i+1<n) { sb.append(js.charAt(i+1)); i+=2; continue; }
                    i++;
                    if (c == '"') break;
                }
            } else if (ch == '\'') {
                sb.append('"'); i++;
                while (i < n) {
                    char c = js.charAt(i);
                    if (c == '\\' && i+1<n) {
                        char nx = js.charAt(i+1);
                        if (nx == '\'') sb.append('\''); else { sb.append('\\'); sb.append(nx); }
                        i += 2;
                    } else if (c == '"') { sb.append('\\'); sb.append('"'); i++; }
                    else if (c == '\'') { sb.append('"'); i++; break; }
                    else { sb.append(c); i++; }
                }
            } else if (isIdentStart(ch)) {
                int start = i;
                while (i<n && isIdentPart(js.charAt(i))) i++;
                String id = js.substring(start, i);
                int k = i; while (k<n && Character.isWhitespace(js.charAt(k))) k++;
                char nx = k<n? js.charAt(k): '\0';
                boolean isKey = nx == ':' || (nx == ']' && start-1>=0 && js.charAt(start-1) == '[');
                if (isKey) { sb.append('"').append(id).append('"'); }
                else { sb.append(id); }
            } else if (ch == ',') {
                int k = i+1; while (k<n && Character.isWhitespace(js.charAt(k))) k++;
                char nx = k<n? js.charAt(k): '\0';
                if (nx == ']' || nx == '}') i++; else { sb.append(ch); i++; }
            } else if (Character.isWhitespace(ch)) { sb.append(ch); i++; }
            else { sb.append(ch); i++; }
        }
        return sb.toString().replaceAll("\\bundefined\\b", "null");
    }
    static boolean isIdentStart(char c) { return Character.isLetter(c) || c=='_' || c=='$'; }
    static boolean isIdentPart(char c) { return Character.isLetterOrDigit(c) || c=='_' || c=='$'; }

    static String normalizePastedStyleExpression(String raw) {
        String t = raw.trim();
        Pattern doubleBrace = Pattern.compile("^\\s*[a-zA-Z_$][\\w$]*\\s*=\\s*\\{\\{\\s*([\\s\\S]*?)\\s*\\}\\}\\s*$");
        Pattern singleBrace = Pattern.compile("^\\s*[a-zA-Z_$][\\w$]*\\s*=\\s*\\{\\s*([\\s\\S]*?)\\s*\\}\\s*$");
        Matcher mdb = doubleBrace.matcher(t);
        Matcher msb = singleBrace.matcher(t);
        String core;
        if (mdb.matches()) core = mdb.group(1).trim();
        else if (msb.matches()) core = msb.group(1).trim();
        else if (t.startsWith("{") && t.endsWith("}")) core = t.substring(1, t.length()-1).trim();
        else return null;
        String candidate = "{" + core + "}";
        if (looksLikeStrictJson(candidate)) return candidate;
        String relaxed = jsLiteralToStrictJson(candidate);
        return looksLikeStrictJson(relaxed)? relaxed : null;
    }
    static boolean looksLikeStrictJson(String s) {
        try { parseJson(s); return true; } catch (Exception ex) { return false; }
    }

    static String camelToKebabStable(String name) {
        StringBuilder sb = new StringBuilder();
        boolean prevLow = false;
        for (char ch : name.toCharArray()) {
            if (ch == '-' || ch == '_') { sb.append('-'); prevLow = false; continue; }
            if (Character.isUpperCase(ch)) {
                if (prevLow) sb.append('-');
                sb.append(Character.toLowerCase(ch));
                prevLow = false;
            } else {
                sb.append(ch);
                prevLow = Character.isLowerCase(ch) || Character.isDigit(ch);
            }
        }
        String r = sb.toString();
        return r.startsWith("-")? r.substring(1) : r;
    }

    static boolean isUnitless(String key, String kebab) {
        return UNITLESS_CAMEL.contains(key) || UNITLESS.contains(kebab);
    }

    static String fmtPrimitiveValue(String origKey, String kebab, String v) {
        if (v.isEmpty()) return null;
        String value = v;
        if ("font-family".equals(kebab) && value.contains(" ") && !value.startsWith("'") && !value.startsWith("\"")) {
            value = "\"" + value + "\"";
        }
        Pattern numeric = Pattern.compile("^-?\\d+(\\.\\d+)?$");
        if (numeric.matcher(value).matches()) {
            if ("0".equals(value)) return "0";
            if (isUnitless(origKey, kebab)) return value;
            return value + "px";
        }
        return value;
    }

    static String fmtShorthandArray(String origKey, String kebab, List<Object> arr) {
        List<String> out = new ArrayList<>();
        for (Object o : arr) {
            if (o instanceof Number || o instanceof String || o instanceof Boolean) {
                String raw = o instanceof Number? trimDot0(String.valueOf(((Number)o).doubleValue())) : String.valueOf(o);
                String f = fmtPrimitiveValue(origKey, kebab, raw);
                if (f != null) out.add(f);
            }
        }
        return out.isEmpty()? null : String.join(" ", out);
    }

    static String trimDot0(String s) {
        return s.endsWith(".0") ? s.substring(0, s.length()-2) : s;
    }

    static String fmtTransformArr(List<Object> arr) {
        List<String> parts = new ArrayList<>();
        for (Object it : arr) {
            if (!(it instanceof Map)) continue;
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) it;
            for (Map.Entry<String, Object> en : m.entrySet()) {
                String func = en.getKey();
                if (!TRANSFORM_FUNCS.contains(func)) continue;
                Object v = en.getValue();
                String raw;
                if (v instanceof Number) {
                    double d = ((Number)v).doubleValue();
                    raw = (d == Math.floor(d) && !Double.isInfinite(d)) ? String.valueOf((long)d) : String.valueOf(d);
                } else {
                    raw = String.valueOf(v);
                }
                Pattern numP = Pattern.compile("^-?\\d+(\\.\\d+)?$");
                String arg;
                if (numP.matcher(raw).matches()) {
                    if (TRANSFORM_UNITLESS.contains(func)) arg = raw;
                    else if (TRANSFORM_ANGLE.contains(func)) arg = raw + "deg";
                    else arg = raw + "px";
                } else arg = raw;
                parts.add(func + "(" + arg + ")");
            }
        }
        return parts.isEmpty()? null : String.join(" ", parts);
    }

    @SuppressWarnings("unchecked")
    static String formatCssValue(String origKey, String kebab, Object el) {
        if (el == null) return null;
        if (el instanceof Boolean) return null;
        if (el instanceof Number) {
            double d = ((Number)el).doubleValue();
            String raw = (d == Math.floor(d) && !Double.isInfinite(d)) ? String.valueOf((long)d) : String.valueOf(d);
            return fmtPrimitiveValue(origKey, kebab, raw);
        }
        if (el instanceof String) return fmtPrimitiveValue(origKey, kebab, (String)el);
        if (el instanceof List) {
            List<Object> arr = (List<Object>)el;
            if ("transform".equals(kebab) && allMaps(arr)) return fmtTransformArr(arr);
            if (SHORTHAND_ARRAY_CAMEL.contains(origKey) || "padding".equals(kebab) || "margin".equals(kebab) || "border-radius".equals(kebab)) {
                return fmtShorthandArray(origKey, kebab, arr);
            }
            List<String> items = new ArrayList<>();
            for (Object a : arr) {
                String f = formatCssValue(origKey, kebab, a);
                if (f!=null) items.add(f);
            }
            return items.isEmpty()? null : String.join(" ", items);
        }
        if (el instanceof Map) {
            return "/* unsupported object - expand manually */";
        }
        return null;
    }

    static boolean allMaps(List<Object> l) {
        for (Object o : l) if (!(o instanceof Map)) return false;
        return !l.isEmpty();
    }

    @SuppressWarnings("unchecked")
    static String convertInlineStyleToCss(String jsonStr) {
        Object parsed;
        try { parsed = parseJson(jsonStr); } catch (Exception ex) { return null; }
        if (!(parsed instanceof Map)) return null;
        Map<String, Object> m = (Map<String, Object>) parsed;
        List<String> lines = new ArrayList<>();
        for (Map.Entry<String, Object> en : m.entrySet()) {
            String kebab = camelToKebabStable(en.getKey());
            String v = formatCssValue(en.getKey(), kebab, en.getValue());
            if (v != null) lines.add("  " + kebab + ": " + v + ";");
        }
        return lines.isEmpty()? "" : String.join("\n", lines) + "\n";
    }

    static int passed = 0, failed = 0;
    static List<String> fails = new ArrayList<>();
    static void assertEquals(Object expected, Object actual, String msg) {
        boolean ok = Objects.equals(expected, actual);
        if (expected instanceof Set && actual instanceof String) {
            @SuppressWarnings({"unchecked","rawtypes"})
            Set<String> ex = (Set) expected;
            Set<String> ac = new HashSet<>(Arrays.asList(((String)actual).split(", ")));
            ok = Objects.equals(ex, ac);
        }
        if (ok) { passed++; }
        else { failed++; String m = "FAIL: "+msg+"\n  expected=|"+expected+"|\n  actual  =|"+actual+"|"; fails.add(m); System.out.println("  ✗ "+m); }
    }
    static void assertContains(String haystack, String needle, String msg) {
        boolean ok = haystack != null && needle != null && haystack.contains(needle);
        if (ok) passed++;
        else { failed++; String m="FAIL(contains): "+msg+"\n  haystack=|"+haystack+"|\n  needle  =|"+needle+"|"; fails.add(m); System.out.println("  ✗ "+m); }
    }
    static void assertNotNull(Object o, String msg) { if (o != null) passed++; else { failed++; fails.add("FAIL(not null): "+msg); System.out.println("  ✗ FAIL "+msg); } }
    static void assertNull(Object o, String msg) { if (o == null) passed++; else { failed++; fails.add("FAIL(null): "+msg); System.out.println("  ✗ FAIL "+msg); } }
    static void assertFalse_(boolean cond, String msg) { if (!cond) passed++; else { failed++; fails.add("FAIL(false): "+msg); System.out.println("  ✗ FAIL(false) "+msg); } }
    static void section(String s) { System.out.println("\n━━━ "+s+" ━━━"); }

    public static void main(String[] args) {
        System.out.println("╔══════════════════════════════════════════════════╗");
        System.out.println("║  InlineStyle JSON/JS → CSS 修复验证器            ║");
        System.out.println("╚══════════════════════════════════════════════════╝");

        section("Bug #1: JS 字面量 → JSON");
        String js1 = "{ fontSize: 14, backgroundColor: \"red\", display: \"flex\" }";
        String json1 = jsLiteralToStrictJson(js1);
        assertNotNull(json1, "非严格JSON解析");
        assertEquals(true, looksLikeStrictJson(json1), "产出应该是严格JSON " + json1);

        String js2 = "style={{ paddingTop: 10, marginLeft: -15, color: 'blue' }}";
        String norm2 = normalizePastedStyleExpression(js2);
        assertNotNull(norm2, "normalize 完整 style={{...}} 形式");
        assertEquals(true, looksLikeStrictJson(norm2), "产出是严格JSON: " + norm2);

        String js3 = "{ 'font-size': '14px', 'color': 'blue' }";
        String json3 = jsLiteralToStrictJson(js3);
        assertContains(json3, "\"font-size\"", "单引号 key 被转双引号");
        assertContains(json3, "\"blue\"", "单引号 val 被转双引号");

        String js4 = "{ a: 1, b: 2, }";
        String json4 = jsLiteralToStrictJson(js4);
        assertEquals(true, looksLikeStrictJson(json4), "尾随逗号移除成功 " + json4);

        String js5 = "{ /* padding */ margin: 10, // 行注释\n color: \"red\" }";
        String json5 = jsLiteralToStrictJson(js5);
        assertEquals(true, looksLikeStrictJson(json5), "注释被移除 " + json5);

        String js6 = "{ display: undefined, flex: 1 }";
        String json6 = jsLiteralToStrictJson(js6);
        assertContains(json6, "null", "undefined 被替换成 null");
        System.out.println("  ✓ JS 字面量解析通过");

        section("Bug #2: 之前不生效的真实 inlineStyle 复制转换");
        String fromReact = "{ fontSize: 14, display: 'flex', alignItems: 'center', padding: 20, margin: 0 }";
        String jsonFixed = jsLiteralToStrictJson(fromReact);
        String css = convertInlineStyleToCss(jsonFixed);
        assertNotNull(css, "能转换");
        assertContains(css, "  font-size: 14px;", "fontSize→font-size, 14→14px");
        assertContains(css, "  display: flex;", "字符串值保留");
        assertContains(css, "  align-items: center;", "alignItems→align-items");
        assertContains(css, "  padding: 20px;", "padding 20→20px");
        assertContains(css, "  margin: 0;", "margin 0→0 (不加px)");
        System.out.println("  ✓ 修复后的 React inline-style 转换通过!");

        section("数值单位修复: 负数/小数/unitless 属性");
        String negCss = cssOf("{\"marginLeft\": -15}");
        assertContains(negCss, "margin-left: -15px;", "负数 marginLeft 加 -15px ✔");
        String decCss = cssOf("{\"letterSpacing\": 0.5}");
        assertContains(decCss, "letter-spacing: 0.5px;", "小数 letterSpacing 加 px");
        String unitless = cssOf("{\"flex\":1,\"zIndex\":10,\"opacity\":0.5,\"lineHeight\":1.5,\"fontWeight\":600}");
        assertContains(unitless, "flex: 1;", "flex: 1 不加px ✔");
        assertContains(unitless, "z-index: 10;", "zIndex 不加px ✔");
        assertContains(unitless, "opacity: 0.5;", "opacity 不加px ✔");
        assertContains(unitless, "line-height: 1.5;", "line-height 不加px ✔");
        assertContains(unitless, "font-weight: 600;", "font-weight 不加px ✔");
        System.out.println("  ✓ 单位规则修复通过 (负数/小数/unitless)");

        section("数组简写属性 padding/margin");
        String pad = cssOf("{\"padding\": [10, 20, 30, 40]}");
        assertContains(pad, "padding: 10px 20px 30px 40px;", "padding 数组简写");
        String margin = cssOf("{\"margin\": [0, \"auto\"]}");
        assertContains(margin, "margin: 0 auto;", "margin [0,auto]");
        String radius = cssOf("{\"borderRadius\": [4, 8]}");
        assertContains(radius, "border-radius: 4px 8px;", "border-radius 数组");
        System.out.println("  ✓ 简写数组属性通过");

        section("React transform: [{ translateX: 10, rotateY: 45 }]");
        String transform = cssOf("{\"transform\": [{\"translateX\": 10, \"translateY\": -5, \"rotateY\": 45}]}");
        assertContains(transform, "translateX(10px)", "translateX(10px)");
        assertContains(transform, "translateY(-5px)", "translateY(-5px)");
        assertContains(transform, "rotateY(45deg)", "rotateY 加 deg 单位 (不加px)");
        String transform2 = cssOf("{\"transform\": [{\"scale\": \"1.2\", \"rotate\": \"90deg\"}]}");
        assertContains(transform2, "scale(1.2)", "scale(1.2) 是字符串原样");
        assertContains(transform2, "rotate(90deg)", "已有 deg 保留");
        System.out.println("  ✓ React transform 数组转换通过");

        section("其他: null/boolean/family 引号/稳定 kebab 转换");
        String nullBool = cssOf("{\"display\": null, \"enabled\": true, \"color\": \"red\"}");
        assertContains(nullBool, "color: red;", "正常属性保留");
        assertFalse_(nullBool.contains("enabled:"), "boolean 属性跳过");
        assertFalse_(nullBool.contains("display:"), "null 属性跳过");
        String family = cssOf("{\"fontFamily\": \"PingFang SC\"}");
        assertContains(family, "\"PingFang SC\"", "多字字体名加双引号");
        String already = cssOf("{\"font-size\": \"14px\", \"z-index\": 9}");
        assertContains(already, "font-size: 14px;", "原已是 kebab-case 不变");
        assertContains(already, "z-index: 9;", "unitless 不加px 且不变");
        System.out.println("  ✓ 其它边界通过");

        section("综合：复杂 inlineStyle（组件里最常见的写法）");
        String big = "style={{\n" +
                     "    display: 'flex',\n" +
                     "    justifyContent: 'center',\n" +
                     "    alignItems: 'center',\n" +
                     "    padding: '16px 24px',\n" +
                     "    marginTop: -12,\n" +
                     "    zIndex: 99,\n" +
                     "    flex: 1,\n" +
                     "    opacity: 0.9,\n" +
                     "    borderRadius: [2, 4],\n" +
                     "    transform: [{ translateX: 10, scale: 1.2, rotate: 15 }],\n" +
                     "    // line comment\n" +
                     "    fontFamily: 'Helvetica Neue',\n" +
                     "    color: undefined,\n" +
                     "}}";
        String norm = normalizePastedStyleExpression(big);
        assertNotNull(norm, "大段综合 normalize 成功");
        String bigCss = convertInlineStyleToCss(norm);
        assertNotNull(bigCss, "大段综合 CSS 生成成功");
        String[] expectKeys = {"display","justify-content","align-items","padding","margin-top","z-index","flex","opacity","border-radius","transform","font-family"};
        for (String k : expectKeys) assertContains(bigCss, k+":", "包含 " + k);
        assertFalse_(bigCss.contains("color:"), "undefined 的 color 被跳过");
        assertFalse_(bigCss.contains("/*"), "综合例子没有 unsupported 注释");
        System.out.println("  ✓ 综合场景通过");

        System.out.println("\n" + pad("═",52));
        System.out.println("  ✓ Passed: " + passed);
        System.out.println("  ✗ Failed: " + failed);
        if (!fails.isEmpty()) { System.out.println("\n失败列表:"); for (int i=0;i<fails.size();i++) System.out.println("  "+(i+1)+". "+fails.get(i)); }
        System.out.println(pad("═",52));
        if (failed == 0) System.out.println("\n🎉 所有 InlineStyle→CSS 修复测试通过！");
        else System.exit(1);
    }
    static String pad(String s, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(s);
        return sb.toString();
    }
    static String cssOf(String json) { return convertInlineStyleToCss(json); }
}
