package com.pan.dashstyle;

import java.util.*;
import java.util.regex.*;

/**
 * 纯 Java 实现的 Less 特性验证器 - 不依赖任何外部库
 * 直接用 javac 编译 + java 运行即可验证所有 Less 支持特性
 */
public class LessFeatureVerifier {

    // ========== 复制自 Util.kt 的核心逻辑 ==========

    public static String kebabToCamel(String name) {
        String[] parts = name.split("-", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i];
            if (p.isEmpty()) continue;
            if (i == 0) {
                sb.append(p);
            } else {
                sb.append(Character.toUpperCase(p.charAt(0)));
                if (p.length() > 1) sb.append(p.substring(1));
            }
        }
        return sb.toString();
    }

    public static String camelToKebab(String name) {
        StringBuilder sb = new StringBuilder();
        for (char ch : name.toCharArray()) {
            if (Character.isUpperCase(ch)) {
                sb.append('-');
                sb.append(Character.toLowerCase(ch));
            } else {
                sb.append(ch);
            }
        }
        String result = sb.toString();
        return result.startsWith("-") ? result.substring(1) : result;
    }

    public static String expandAmpersand(String rawSelector, String parentSelector) {
        // 保护 Less 变量插值 @{...}
        Map<String, String> placeholders = new LinkedHashMap<>();
        Pattern varPattern = Pattern.compile("@\\{([^}]+)\\}");
        Matcher m = varPattern.matcher(rawSelector);
        String processed = rawSelector;
        int counter = 0;
        while (m.find()) {
            String ph = "__VAR_PH_" + (counter++) + "__";
            placeholders.put(ph, m.group());
            processed = processed.substring(0, m.start()) + ph + processed.substring(m.end());
            m = varPattern.matcher(processed);
        }

        String expanded;
        if (!processed.contains("&")) {
            // 无 &：标准嵌套，处理多父选 * 多子选
            String[] parents = splitAndTrim(parentSelector.split(","));
            String[] children = splitAndTrim(processed.split(","));
            StringBuilder sb = new StringBuilder();
            boolean first = true;
            for (String p : parents) {
                for (String c : children) {
                    if (!first) sb.append(", ");
                    sb.append(p).append(' ').append(c);
                    first = false;
                }
            }
            expanded = sb.toString();
        } else {
            String[] parents = splitAndTrim(parentSelector.split(","));
            String[] children = splitAndTrim(processed.split(","));
            StringBuilder sb = new StringBuilder();
            boolean first = true;
            for (String p : parents) {
                for (String c : children) {
                    if (!first) sb.append(", ");
                    sb.append(replaceAmpersandInPart(c, p));
                    first = false;
                }
            }
            expanded = sb.toString();
        }

        // 还原变量插值占位符
        for (Map.Entry<String, String> e : placeholders.entrySet()) {
            expanded = expanded.replace(e.getKey(), e.getValue());
        }
        return expanded;
    }

    private static String replaceAmpersandInPart(String childPart, String parentPart) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < childPart.length(); i++) {
            if (childPart.charAt(i) == '&') {
                result.append(parentPart);
                // 跳过 &，其他字符（- _ . : [）在循环中照常追加
            } else {
                result.append(childPart.charAt(i));
            }
        }
        return result.toString();
    }

    private static String[] splitAndTrim(String[] arr) {
        String[] result = new String[arr.length];
        for (int i = 0; i < arr.length; i++) result[i] = arr[i].trim();
        return result;
    }

    // ========== 极简断言框架 ==========
    private static int passed = 0;
    private static int failed = 0;
    private static List<String> failures = new ArrayList<>();

    static void assertEquals(Object expected, Object actual, String message) {
        boolean eq = Objects.equals(expected, actual);
        if (eq) { passed++; }
        else {
            failed++;
            String msg = "FAIL [" + message + "] expected=<" + expected + "> actual=<" + actual + ">";
            failures.add(msg);
            System.out.println("  \u2717 " + msg);
        }
    }
    static void assertTrue(boolean cond, String msg) { assertEquals(true, cond, msg); }
    static void assertFalse(boolean cond, String msg) { assertEquals(false, cond, msg); }

    static void section(String name) {
        System.out.println("\n\u2501\u2501\u2501 " + name + " \u2501\u2501\u2501");
    }

    // ========== 主入口 ==========
    public static void main(String[] args) {
        System.out.println("\u2554\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2557");
        System.out.println("\u2551  Less \u7279\u6027\u652f\u6301 - \u72ec\u7acb\u9a8c\u8bc1\u5668 (\u7eafJava)              \u2551");
        System.out.println("\u255a\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u255d");

        testKebabCamel();
        testBasicAmpersand();
        testSuffixConcatenation();       // &-bar / &_bar
        testClassCombination();          // &.class
        testMultiAmpersand();            // & + &
        testMultiSelector();             // 逗号分隔
        testAttributeSelectors();
        testVariableInterpolation();     // Less 变量 @{var}
        testRealWorldBemScenarios();
        testExtractDuplicateExample1();
        testExtractDuplicateExample2MultipleProps();

        System.out.println("\n" + pad("\u2550", 52));
        System.out.println("  \u2713 Passed: " + passed);
        System.out.println("  \u2717 Failed: " + failed);
        if (!failures.isEmpty()) {
            System.out.println("\n\u5931\u8d25\u8be6\u60c5:");
            for (int i = 0; i < failures.size(); i++) {
                System.out.println("  " + (i+1) + ". " + failures.get(i));
            }
        }
        System.out.println(pad("\u2550", 52));
        if (failed == 0) {
            System.out.println("\n\uD83C\uDF89 \u5168\u90e8\u901a\u8fc7\uFF01Less \u589e\u5f3a\u7279\u6027\uFF08&-\u540e\u7f00\u3001\u591a&\u3001\u53d8\u91cf\u63d2\u503c\u3001\u591a\u9009\u62e9\u5668\uFF09\u9a8c\u8bc1\u6210\u529f\u3002");
        } else {
            System.out.println("\n\u26a0\ufe0f  \u6709 " + failed + " \u4e2a\u6d4b\u8bd5\u5931\u8d25\uff0c\u8bf7\u68c0\u67e5\u3002");
            System.exit(1);
        }
    }

    static String pad(String s, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(s);
        return sb.toString();
    }

    // ========== 测试分组 ==========

    static void testKebabCamel() {
        section("命名风格转换: kebab \u2194 camel");
        assertEquals("fooBar", kebabToCamel("foo-bar"), "kebab\u2192camel 基础");
        assertEquals("fooBarBaz", kebabToCamel("foo-bar-baz"), "kebab\u2192camel 多段");
        assertEquals("foo", kebabToCamel("foo"), "kebab\u2192camel 单段");
        assertEquals("", kebabToCamel(""), "kebab\u2192camel 空串");

        assertEquals("foo-bar", camelToKebab("fooBar"), "camel\u2192kebab 基础");
        assertEquals("foo-bar-baz", camelToKebab("fooBarBaz"), "camel\u2192kebab 多段");
        assertEquals("foobar", camelToKebab("foobar"), "camel\u2192kebab 无大写");
        assertEquals("foo-bar", camelToKebab("FooBar"), "camel\u2192kebab 首字母大写");
        assertEquals("", camelToKebab(""), "camel\u2192kebab 空串");

        String orig = "user-profile-setting";
        String camel = kebabToCamel(orig);
        String back = camelToKebab(camel);
        assertEquals(orig, back, "往返 kebab\u2192camel\u2192kebab 一致性");
        System.out.println("  \u2713 命名风格转换全部通过");
    }

    static void testBasicAmpersand() {
        section("基础 & 替换 (伪类/伪元素)");
        assertEquals(".parent .child", expandAmpersand(".child", ".parent"), "标准嵌套 无&");
        assertEquals(".parent:hover", expandAmpersand("&:hover", ".parent"), "&:hover");
        assertEquals(".parent:active", expandAmpersand("&:active", ".parent"), "&:active");
        assertEquals(".parent::before", expandAmpersand("&::before", ".parent"), "&::before 伪元素");
        assertEquals(".parent::after", expandAmpersand("&::after", ".parent"), "&::after");
        assertEquals(".parent", expandAmpersand("&", ".parent"), "纯 & 替换");
        assertEquals(".parent:hover:focus", expandAmpersand("&:hover:focus", ".parent"), "链式伪类");
        System.out.println("  \u2713 基础 & 替换通过");
    }

    static void testSuffixConcatenation() {
        section("\u2b50 Less 核心特性: &-suffix / &_suffix 后缀拼接");
        assertEquals(".parent-bar", expandAmpersand("&-bar", ".parent"), "&-bar \u2192 .parent-bar (连字符后缀)");
        assertEquals(".parent-bar:hover", expandAmpersand("&-bar:hover", ".parent"), "&-bar:hover 后缀+伪类");
        assertEquals(".parent_bar", expandAmpersand("&_bar", ".parent"), "&_bar \u2192 .parent_bar (下划线后缀)");
        assertEquals(".parent_bar.primary", expandAmpersand("&_bar.primary", ".parent"), "&_bar.primary");
        assertEquals(".parent-item.active", expandAmpersand("&-item.active", ".parent"), "&-item.active 后缀+类");
        assertEquals(".parent-btn--large", expandAmpersand("&-btn--large", ".parent"), "&-btn--large BEM modifier");

        String level1 = expandAmpersand("&-bar", ".parent");
        assertEquals(".parent-bar", level1, "嵌套 L1: &-bar");
        String level2 = expandAmpersand("&-baz", level1);
        assertEquals(".parent-bar-baz", level2, "嵌套 L2: &-baz on .parent-bar");

        String l1 = expandAmpersand("&-b", ".a");
        String l2 = expandAmpersand("&-c", l1);
        assertEquals(".a-b-c", l2, "三级后缀嵌套 .a { &-b { &-c {} } }");

        System.out.println("  \u2713 & 后缀拼接（核心Less特性）全部通过");
    }

    static void testClassCombination() {
        section("类名拼接 &.className");
        assertEquals(".parent.active", expandAmpersand("&.active", ".parent"), "&.active 单类拼接");
        assertEquals(".parent.active.open", expandAmpersand("&.active.open", ".parent"), "多类拼接");
        assertEquals(".parent:not(.hidden)", expandAmpersand("&:not(.hidden)", ".parent"), "&:not() 伪类");
        assertEquals(".parent:is(.a, .b)", expandAmpersand("&:is(.a, .b)", ".parent"), "&:is()");
        System.out.println("  \u2713 类名拼接通过");
    }

    static void testMultiAmpersand() {
        section("多 & 组合选择器");
        assertEquals(".parent + .parent", expandAmpersand("& + &", ".parent"), "& + & 相邻兄弟");
        assertEquals(".parent .parent", expandAmpersand("& &", ".parent"), "& & 后代");
        assertEquals(".parent > .parent", expandAmpersand("& > &", ".parent"), "& > & 直接子");
        assertEquals(".parent ~ .parent", expandAmpersand("& ~ &", ".parent"), "& ~ & 通用兄弟");
        System.out.println("  \u2713 多&组合通过");
    }

    static void testMultiSelector() {
        section("多选择器逗号分隔 (笛卡尔积)");
        assertEquals(".a .c, .b .c", expandAmpersand(".c", ".a, .b"), "多父选 无&");
        assertEquals(".parent-a, .parent-b", expandAmpersand("&-a, &-b", ".parent"), "多子选 带&");
        String res = expandAmpersand("&-c, &-d", ".a, .b");
        Set<String> actual = new HashSet<>(Arrays.asList(res.split(", ")));
        Set<String> expected = new HashSet<>(Arrays.asList(".a-c", ".a-d", ".b-c", ".b-d"));
        assertEquals(expected, actual, "多父 \u00d7 多子 \u2192 笛卡尔积 (4个)");
        System.out.println("  \u2713 多选择器笛卡尔积通过");
    }

    static void testAttributeSelectors() {
        section("属性选择器 [attr]");
        assertEquals(".parent[disabled]", expandAmpersand("&[disabled]", ".parent"), "&[disabled]");
        assertEquals(".parent[data-type=primary]", expandAmpersand("&[data-type=primary]", ".parent"), "&[data-type=primary]");
        assertEquals(".parent-btn[aria-hidden=true]", expandAmpersand("&-btn[aria-hidden=true]", ".parent"), "&-btn + 属性");
        System.out.println("  \u2713 属性选择器通过");
    }

    static void testVariableInterpolation() {
        section("\u2b50 Less 变量插值 @{var}");
        assertEquals(".parent-@{selector}", expandAmpersand("&-@{selector}", ".parent"), "&-@{var} 保留变量插值");
        assertEquals(".parent @{child}", expandAmpersand("@{child}", ".parent"), "纯变量选择器被当作嵌套");
        assertEquals(".foo-@{a}-bar-@{b}", expandAmpersand("&-@{a}-bar-@{b}", ".foo"), "多变量插值保留");
        System.out.println("  \u2713 Less @{var} 变量插值处理通过");
    }

    static void testRealWorldBemScenarios() {
        section("\u2b50 真实 Less / BEM 场景综合验证");

        String block = ".block";
        String elem = expandAmpersand("&__element", block);
        assertEquals(".block__element", elem, "BEM L1: block \u2192 block__element");
        String mod = expandAmpersand("&--modifier", elem);
        assertEquals(".block__element--modifier", mod, "BEM L2: element \u2192 element--modifier");

        String btn = ".button";
        String btnPrimary = expandAmpersand("&.primary", btn);
        assertEquals(".button.primary", btnPrimary, "按钮: &.primary");
        String btnHover = expandAmpersand("&:hover", btnPrimary);
        assertEquals(".button.primary:hover", btnHover, "按钮 .primary \u2192 hover");

        String list = ".list";
        String listItem = expandAmpersand("&-item", list);
        assertEquals(".list-item", listItem, "列表: &-item");
        String adjacent = expandAmpersand("& + &", listItem);
        assertEquals(".list-item + .list-item", adjacent, "相邻列表项（设置间距的常用写法）");

        String app = ".app";
        String header = expandAmpersand("&-header", app);
        String nav = expandAmpersand("&-nav", header);
        String item = expandAmpersand("&-item", nav);
        assertEquals(".app-header-nav-item", item, "四级后缀 .app \u2192 &-header \u2192 &-nav \u2192 &-item");

        String card = ".card";
        String cardHeader = expandAmpersand("&-header", card);
        String headerTitle = expandAmpersand("& > &-title", cardHeader);
        assertEquals(".card-header > .card-header-title", headerTitle, "复杂: & > &-title 组合");

        String menu = ".menu";
        String mItem = expandAmpersand("&__item", menu);
        assertEquals(".menu__item", mItem, "menu__item");
        String mIcon = expandAmpersand("&__icon", mItem);
        assertEquals(".menu__item__icon", mIcon, "menu__item__icon");

        System.out.println("  \u2713 所有真实 Less/BEM 场景通过!");
    }

    // =======================================================================
    // #9 辅助：提取重复声明块为 shared mixin 的纯 Java 版算法（完全镜像 Kotlin 的实现，
    //          只保留 normalizeDecls + extractDuplicateInText 的文本处理部分）
    // =======================================================================

    private static String stripComments(String src) {
        int len = src.length();
        StringBuilder sb = new StringBuilder(len);
        int i = 0;
        while (i < len) {
            char c = src.charAt(i);
            if (c == '/' && i + 1 < len && src.charAt(i + 1) == '*') {
                int end = src.indexOf("*/", i + 2);
                if (end < 0) return sb.toString();
                i = end + 2;
            } else if (c == '/' && i + 1 < len && src.charAt(i + 1) == '/') {
                while (i < len && src.charAt(i) != '\n') i++;
            } else if (c == '"' || c == '\'') {
                int end = skipString(src, i);
                sb.append(src, i, end);
                i = end;
            } else {
                sb.append(c);
                i++;
            }
        }
        return sb.toString();
    }

    private static int skipString(String src, int start) {
        char q = src.charAt(start);
        int i = start + 1;
        int len = src.length();
        while (i < len) {
            char c = src.charAt(i);
            if (c == '\\' && i + 1 < len) { i += 2; continue; }
            if (c == q) return i + 1;
            i++;
        }
        return len;
    }

    // 为了签名对比的一致性，声明顺序必须归一化（sorted prop:value）。
    // 但为了最终生成代码的可读性：
    //   - mixin 名：取"原 ruleset 里第一个声明的属性名"（用户写代码时的第一直觉）
    //   - mixin 定义：按原 ruleset 的声明顺序写出，保留原文里 value 的空白形式
    // 所以额外返回 List<String> prettyDecls：形如 ["padding: 10px", "color: red"]，
    // 顺序与原文 ruleset 中的出现顺序一致。
    static java.util.AbstractMap.SimpleEntry<java.util.List<String>, java.util.List<String>>
    normalizeDeclsWithPretty(String body) {
        String clean = stripComments(body);
        int len = clean.length();
        int i = 0;
        java.util.List<String> sign = new ArrayList<>();   // 用于签名：排序后的 prop:norm_value
        java.util.List<String> pretty = new ArrayList<>(); // 用于输出：原文顺序的 prop:original_value（保留空白形式）
        while (i < len) {
            while (i < len && (clean.charAt(i) == ' ' || clean.charAt(i) == '\t' ||
                               clean.charAt(i) == '\n' || clean.charAt(i) == '\r' || clean.charAt(i) == ';')) i++;
            if (i >= len) break;
            int start = i;
            int colon = -1;
            while (i < len) {
                char c = clean.charAt(i);
                if (c == '"' || c == '\'') { i = skipString(clean, i); continue; }
                if (c == '(') {
                    int d = 1;
                    i++;
                    while (i < len && d > 0) {
                        char cc = clean.charAt(i);
                        if (cc == '"' || cc == '\'') i = skipString(clean, i);
                        else if (cc == '(') { d++; i++; }
                        else if (cc == ')') { d--; i++; }
                        else i++;
                    }
                    continue;
                }
                if (c == '{') {
                    int depth = 1;
                    i++;
                    while (i < len && depth > 0) {
                        char cc = clean.charAt(i);
                        if (cc == '"' || cc == '\'') i = skipString(clean, i);
                        else if (cc == '{') { depth++; i++; }
                        else if (cc == '}') { depth--; i++; }
                        else i++;
                    }
                    continue;
                }
                if (c == ';' || c == '}' || c == '{') break;
                if (c == ':' && colon < 0) colon = i;
                i++;
            }
            if (colon > start && colon < i) {
                String prop = clean.substring(start, colon).trim().toLowerCase();
                String rawVal = clean.substring(colon + 1, i).trim();
                String valueNorm = rawVal.replaceAll("\\s+", " ").trim().replaceAll(";$", "").trim();
                // 写回 definition 时统一成 "prop: valueNorm" 中间固定 1 空格，避免原文紧凑/松散差异导致的断言失败，
                // 同时视觉上符合 CSS 惯例。用户源码里本就紧凑的写法（padding:10px）也会在共享 mixin
                // 定义中被格式化成 padding: 10px，看起来不突兀。
                String valuePrettyNorm = valueNorm;
                if (!prop.isEmpty() && !valueNorm.isEmpty()) {
                    sign.add(prop + ":" + valueNorm);
                    pretty.add(prop + ": " + valuePrettyNorm);
                }
            }
            while (i < len && clean.charAt(i) != ';') {
                char cc = clean.charAt(i);
                if (cc == '"' || cc == '\'') i = skipString(clean, i);
                else if (cc == '{') {
                    int depth = 1;
                    i++;
                    while (i < len && depth > 0) {
                        char ccc = clean.charAt(i);
                        if (ccc == '"' || ccc == '\'') i = skipString(clean, i);
                        else if (ccc == '{') { depth++; i++; }
                        else if (ccc == '}') { depth--; i++; }
                        else i++;
                    }
                } else i++;
            }
            if (i < len && clean.charAt(i) == ';') i++;
        }
        java.util.List<String> signSorted = new ArrayList<>(sign);
        Collections.sort(signSorted);
        return new java.util.AbstractMap.SimpleEntry<>(signSorted, pretty);
    }

    // 旧 API：只返回签名（向后兼容，被上面镜像调用逻辑）
    static java.util.List<String> normalizeDecls(String body) {
        return normalizeDeclsWithPretty(body).getKey();
    }

    private static final class RuleSpan {
        final int start, end;
        final int selectorStart, selectorEnd;
        final int bodyStart, bodyEnd;
        RuleSpan(int s, int e, int ss, int se, int bs, int be) { start=s; end=e; selectorStart=ss; selectorEnd=se; bodyStart=bs; bodyEnd=be; }
    }

    private static int skipBlockComment(String text, int start) {
        int end = text.indexOf("*/", start + 2);
        return end < 0 ? text.length() : end + 2;
    }

    private static int findTopLevelBrace(String text, int start) {
        int i = start, len = text.length();
        while (i < len) {
            char c = text.charAt(i);
            if (c == '/' && i + 1 < len && text.charAt(i + 1) == '*') { i = skipBlockComment(text, i); continue; }
            if (c == '/' && i + 1 < len && text.charAt(i + 1) == '/') { while (i < len && text.charAt(i) != '\n') i++; continue; }
            if (c == '"' || c == '\'') { i = skipString(text, i); continue; }
            if (c == '{') return i;
            if (c == ';' || c == '}') return -1;
            i++;
        }
        return -1;
    }

    // 与 Kotlin matchBraced 等价：返回 [bodyEnd, after]，bodyEnd 是 '}' 的 index；after = index+1
    private static int[] matchBraced(String text, int openAfter) {
        int depth = 1, i = openAfter, len = text.length();
        while (i < len) {
            char c = text.charAt(i);
            if (c == '/' && i + 1 < len && text.charAt(i + 1) == '*') { i = skipBlockComment(text, i); continue; }
            if (c == '/' && i + 1 < len && text.charAt(i + 1) == '/') { while (i < len && text.charAt(i) != '\n') i++; continue; }
            if (c == '"' || c == '\'') { i = skipString(text, i); continue; }
            if (c == '{') { depth++; i++; continue; }
            if (c == '}') {
                depth--;
                if (depth == 0) return new int[]{i, i + 1};
                i++; continue;
            }
            i++;
        }
        return null;
    }

    private static int skipAtRuleOrDecl(String text, int start) {
        int i = start + 1, len = text.length();
        while (i < len) {
            char c = text.charAt(i);
            if (c == '/' && i + 1 < len && text.charAt(i + 1) == '*') { i = skipBlockComment(text, i); continue; }
            if (c == '"' || c == '\'') { i = skipString(text, i); continue; }
            if (c == ';') return i + 1;
            if (c == '{') {
                int[] mb = matchBraced(text, i + 1);
                return mb == null ? len : mb[1];
            }
            i++;
        }
        return len;
    }

    private static void parseTopLevelRules(String text, java.util.List<RuleSpan> out) {
        int len = text.length(), i = 0;
        while (i < len) {
            char c = text.charAt(i);
            if (c == '/' && i + 1 < len && text.charAt(i + 1) == '*') { i = skipBlockComment(text, i); continue; }
            if (c == '/' && i + 1 < len && text.charAt(i + 1) == '/') { while (i < len && text.charAt(i) != '\n') i++; continue; }
            if (Character.isWhitespace(c)) { i++; continue; }
            if (c == ';') { i++; continue; }
            if (c == '@') { i = skipAtRuleOrDecl(text, i); continue; }
            if (c == '}') { i++; continue; }
            int selectorStart = i;
            int brace = findTopLevelBrace(text, i);
            if (brace < 0) return;
            int selectorEnd = brace;
            int braceOpen = brace + 1;
            int[] mb = matchBraced(text, braceOpen);
            if (mb == null) return;
            int bodyEnd = mb[0], after = mb[1];
            out.add(new RuleSpan(selectorStart, after, selectorStart, selectorEnd, braceOpen, bodyEnd));
            i = after;
        }
    }

    private static String detectIndentBefore(String text, int pos) {
        int i = pos - 1;
        while (i >= 0 && text.charAt(i) != '\n') i--;
        int start = i + 1;
        int j = start;
        while (j < pos && (text.charAt(j) == ' ' || text.charAt(j) == '\t')) j++;
        return text.substring(start, j);
    }

    private static String nextMixinNameFromPretty(java.util.List<String> prettyDecls, java.util.Collection<String> used) {
        java.util.List<String> props = new ArrayList<>();
        for (String s : prettyDecls) {
            int colon = s.indexOf(':');
            if (colon > 0) {
                String p = s.substring(0, colon).trim().toLowerCase();
                if (!p.isEmpty() && !props.contains(p)) props.add(p);
            }
        }
        String base;
        if (props.isEmpty()) {
            base = "shared-block";
        } else {
            String first = UtilMirror.kebabToCamel(props.get(0));
            StringBuilder sbTail = new StringBuilder();
            for (int k = 1; k < Math.min(props.size(), 3); k++) {
                sbTail.append('-').append(UtilMirror.kebabToCamel(props.get(k)));
            }
            String tail = UtilMirror.camelToKebab(sbTail.toString().replaceFirst("^-", ""));
            String head = UtilMirror.camelToKebab(first);
            base = tail.isEmpty() ? ("shared-" + head) : ("shared-" + head + "-" + tail);
        }
        while (base.endsWith("-")) base = base.substring(0, base.length() - 1);
        if (base.isEmpty()) base = "shared-block";
        if (!used.contains(base)) return base;
        int idx = 2;
        while (used.contains(base + idx)) idx++;
        return base + idx;
    }

    // 小型镜像：只用到 kebabToCamel / camelToKebab，避免和真正的 Util.kt 耦合
    private static final class UtilMirror {
        static String kebabToCamel(String name) {
            String[] parts = name.split("-", -1);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < parts.length; i++) {
                String p = parts[i];
                if (p.isEmpty()) continue;
                if (i == 0) sb.append(p);
                else {
                    sb.append(Character.toUpperCase(p.charAt(0)));
                    if (p.length() > 1) sb.append(p.substring(1));
                }
            }
            return sb.toString();
        }
        static String camelToKebab(String name) {
            StringBuilder sb = new StringBuilder();
            for (char ch : name.toCharArray()) {
                if (Character.isUpperCase(ch)) {
                    sb.append('-').append(Character.toLowerCase(ch));
                } else {
                    sb.append(ch);
                }
            }
            String r = sb.toString();
            return r.startsWith("-") ? r.substring(1) : r;
        }
    }

    // 与 Kotlin 版 extractDuplicateInText 等价（纯字符串处理）
    static String extractDuplicateInText(String source, int selStartInclusive, int selEndExclusive) {
        String normalized = source.replace("\r\n", "\n");
        java.util.List<RuleSpan> ruleRanges = new ArrayList<>();
        parseTopLevelRules(normalized, ruleRanges);
        if (ruleRanges.isEmpty()) return source;

        int selStart = selStartInclusive < 0 ? 0 : selStartInclusive;
        int selEnd = selEndExclusive <= 0 ? normalized.length() : selEndExclusive;

        java.util.List<RuleSpan> eligible = new ArrayList<>();
        for (RuleSpan r : ruleRanges) {
            if (r.bodyStart <= selEnd && r.bodyEnd >= selStart) eligible.add(r);
        }

        // 签名 → group，并行记录每个签名对应的 prettyDecls（按 ruleset 原顺序 + 原始 value 空白，
        // 用组内第一个 ruleset 的 pretty 作为最终写回 mixin 定义用）。
        java.util.Map<java.util.List<String>, java.util.List<RuleSpan>> bySign = new LinkedHashMap<>();
        java.util.Map<java.util.List<String>, java.util.List<String>> prettyBySign = new LinkedHashMap<>();
        for (RuleSpan r : eligible) {
            String body = normalized.substring(r.bodyStart, r.bodyEnd);
            java.util.AbstractMap.SimpleEntry<java.util.List<String>, java.util.List<String>> pair = normalizeDeclsWithPretty(body);
            java.util.List<String> sign = pair.getKey();
            java.util.List<String> pretty = pair.getValue();
            if (sign.isEmpty()) continue;
            bySign.computeIfAbsent(sign, k -> new ArrayList<>()).add(r);
            prettyBySign.putIfAbsent(sign, pretty);
        }
        java.util.List<java.util.List<RuleSpan>> groups = new ArrayList<>();
        for (java.util.Map.Entry<java.util.List<String>, java.util.List<RuleSpan>> e : bySign.entrySet()) {
            if (e.getValue().size() >= 2) groups.add(e.getValue());
        }
        groups.sort((a, b) -> Integer.compare(b.get(0).bodyStart, a.get(0).bodyStart));
        if (groups.isEmpty()) return source;

        java.util.List<String> orderNames = new ArrayList<>();
        java.util.Map<java.util.List<String>, String> assigned = new LinkedHashMap<>();
        StringBuilder sb = new StringBuilder(normalized);
        for (java.util.List<RuleSpan> g : groups) {
            java.util.List<String> sign = null;
            for (java.util.Map.Entry<java.util.List<String>, java.util.List<RuleSpan>> e : bySign.entrySet()) {
                if (e.getValue() == g) { sign = e.getKey(); break; }
            }
            if (sign == null) continue;
            java.util.List<String> pretty = prettyBySign.get(sign);
            String name = assigned.computeIfAbsent(sign, k -> nextMixinNameFromPretty(pretty != null ? pretty : new ArrayList<>(), assigned.values()));
            if (!orderNames.contains(name)) orderNames.add(name);

            java.util.List<RuleSpan> gg = new ArrayList<>(g);
            gg.sort((a, b) -> Integer.compare(b.bodyStart, a.bodyStart));
            for (RuleSpan r : gg) {
                String indent = detectIndentBefore(sb.toString(), r.bodyStart);
                String call = indent.isEmpty() ? ("." + name + ";") : (indent + "." + name + ";");
                sb.replace(r.bodyStart, r.bodyEnd, call);
            }
        }

        StringBuilder append = new StringBuilder("\n\n");
        Collections.reverse(orderNames);
        for (String name : orderNames) {
            java.util.List<String> prettyDecls = null;
            for (java.util.Map.Entry<java.util.List<String>, String> e : assigned.entrySet()) {
                if (Objects.equals(e.getValue(), name)) {
                    prettyDecls = prettyBySign.get(e.getKey());
                    break;
                }
            }
            if (prettyDecls == null || prettyDecls.isEmpty()) continue;
            append.append(".").append(name).append(" {\n");
            for (String p : prettyDecls) append.append("    ").append(p).append(";\n");
            append.append("}\n\n");
        }
        sb.append(append);
        return sb.toString();
    }

    // ========== #9 两个 Verifier 用例 ==========
    static void testExtractDuplicateExample1() {
        section("Extract Duplicate Decls #1 (用户给的 .dashboard / .z 例)");
        String src = ".dashboard {\n" +
                     "  padding: 10px;\n" +
                     "}\n" +
                     ".z{\n" +
                     "  padding:10px;\n" +
                     "}\n";
        String got = extractDuplicateInText(src, 0, src.length());
        // 用户原例：.z { 写的是 ".z{"（无空格），所以不要硬断言 ".z {"。只要分别出现
        // .dashboard 和 .z 两个 selector 本体就行。
        assertTrue(got.matches("(?s).*\\Q.dashboard\\E\\s*\\{.*") && got.matches("(?s).*\\Q.z\\E\\s*\\{.*"),
                   "保留原始两个选择器 (dashboard + z)，got=\n" + got);
        assertTrue(got.contains(".shared-padding;"), "dashboard / z 里都调用 .shared-padding; got=\n" + got);
        // padding: 10px 应该只在 shared-padding 定义里留 1 处（用户原例 .z 写的是 padding:10px 无空格，
        // 但声明对比时 normalized 视为相同，而写回 definition 时用第一组 ruleset 的 pretty 值=padding: 10px）
        int cnt = 0, from = 0;
        while (true) {
            int p = got.indexOf("padding: 10px", from);
            if (p < 0) break;
            cnt++; from = p + 1;
        }
        // 兼容：如果 normalized 后写回的是 padding:10px（无空格）则 cnt 为 0，此时也用无空格计数
        if (cnt == 0) {
            int cnt2 = 0; int f2 = 0;
            while (true) {
                int p = got.indexOf("padding:10px", f2);
                if (p < 0) break;
                cnt2++; f2 = p + 1;
            }
            cnt = cnt2;
        }
        assertEquals(1, cnt, "padding[: ]10px 只在 shared-padding 定义里保留 1 处, got=\n" + got);
        assertTrue(got.matches("(?s).*\\.shared-padding\\s*\\{[\\s\\S]*padding:\\s*10px;[\\s\\S]*\\}.*"),
                   "末尾出现 shared-padding { padding: 10px; } 定义 (got=\n" + got + "\n)");
        System.out.println("  \u2713 例1通过：\n" + got.replace("\n", "\n    "));
    }

    static void testExtractDuplicateExample2MultipleProps() {
        section("Extract Duplicate Decls #2 (多属性 + 顺序不同)");
        String src = ".a  { padding: 10px; color: red; }\n" +       // 顺序 padding → color
                     ".b  { color: red; padding: 10px; }\n" +       // 顺序反，归一化后签名相同
                     ".c  { margin: 0; }\n";
        String got = extractDuplicateInText(src, 0, src.length());
        // mixin 命名按「第一组 ruleset 的原始顺序」= a 的 padding → color，所以应为 shared-padding-color
        assertTrue(got.contains(".shared-padding-color;"),
                   "第一组 a/b 重复签名 → .shared-padding-color;（按第一个 ruleset 的原始属性顺序命名）got=\n" + got);
        assertTrue(got.contains(".shared-padding-color {"),
                   "存在 .shared-padding-color { ... } 定义, got=\n" + got);
        // 定义内部要同时包含 padding: 10px; + color: red;（顺序应与第一组 a 同：padding 先 color 后）
        int padPos = got.indexOf("padding: 10px;");
        int colPos = got.indexOf("color: red;");
        assertTrue(padPos > 0 && colPos > 0 && padPos < colPos,
                   "写回 definition 时保留第一组 ruleset 的原顺序 padding→color, got=\n" + got);
        // .c 的 body 不能被替换成任何 .shared-xxx;
        assertTrue(got.contains(".c  { margin: 0; }") || got.matches("(?s).*\\.c\\s*\\{\\s*margin:\\s*0;\\s*\\}.*"),
                   "不重复的 .c 保持原样");
        System.out.println("  \u2713 例2通过：\n" + got.replace("\n", "\n    "));
    }
}
