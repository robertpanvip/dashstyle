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
            String[] parentParts = splitAndTrim(processed.split(","));
            // Oops - correction: parent parts from parentSelector
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
            System.out.println("  ✗ " + msg);
        }
    }
    static void assertTrue(boolean cond, String msg) { assertEquals(true, cond, msg); }
    static void assertFalse(boolean cond, String msg) { assertEquals(false, cond, msg); }

    static void section(String name) {
        System.out.println("\n━━━ " + name + " ━━━");
    }

    // ========== 主入口 ==========
    public static void main(String[] args) {
        System.out.println("╔══════════════════════════════════════════════════╗");
        System.out.println("║  Less 特性支持 - 独立验证器 (纯Java)              ║");
        System.out.println("╚══════════════════════════════════════════════════╝");

        testKebabCamel();
        testBasicAmpersand();
        testSuffixConcatenation();       // &-bar / &_bar
        testClassCombination();          // &.class
        testMultiAmpersand();            // & + &
        testMultiSelector();             // 逗号分隔
        testAttributeSelectors();
        testVariableInterpolation();     // Less 变量 @{var}
        testRealWorldBemScenarios();

        System.out.println("\n" + "═".repeat(52));
        System.out.println("  ✓ Passed: " + passed);
        System.out.println("  ✗ Failed: " + failed);
        if (!failures.isEmpty()) {
            System.out.println("\n失败详情:");
            for (int i = 0; i < failures.size(); i++) {
                System.out.println("  " + (i+1) + ". " + failures.get(i));
            }
        }
        System.out.println("═".repeat(52));
        if (failed == 0) {
            System.out.println("\n🎉 全部通过！Less 增强特性（&-后缀、多&、变量插值、多选择器）验证成功。");
        } else {
            System.out.println("\n⚠️  有 " + failed + " 个测试失败，请检查。");
            System.exit(1);
        }
    }

    // ========== 测试分组 ==========

    static void testKebabCamel() {
        section("命名风格转换: kebab ↔ camel");
        assertEquals("fooBar", kebabToCamel("foo-bar"), "kebab→camel 基础");
        assertEquals("fooBarBaz", kebabToCamel("foo-bar-baz"), "kebab→camel 多段");
        assertEquals("foo", kebabToCamel("foo"), "kebab→camel 单段");
        assertEquals("", kebabToCamel(""), "kebab→camel 空串");

        assertEquals("foo-bar", camelToKebab("fooBar"), "camel→kebab 基础");
        assertEquals("foo-bar-baz", camelToKebab("fooBarBaz"), "camel→kebab 多段");
        assertEquals("foobar", camelToKebab("foobar"), "camel→kebab 无大写");
        assertEquals("foo-bar", camelToKebab("FooBar"), "camel→kebab 首字母大写");
        assertEquals("", camelToKebab(""), "camel→kebab 空串");

        String orig = "user-profile-setting";
        String camel = kebabToCamel(orig);
        String back = camelToKebab(camel);
        assertEquals(orig, back, "往返 kebab→camel→kebab 一致性");
        System.out.println("  ✓ 命名风格转换全部通过");
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
        System.out.println("  ✓ 基础 & 替换通过");
    }

    static void testSuffixConcatenation() {
        section("⭐ Less 核心特性: &-suffix / &_suffix 后缀拼接");
        // 这是之前不支持、现在需要补齐的关键特性
        assertEquals(".parent-bar", expandAmpersand("&-bar", ".parent"), "&-bar → .parent-bar (连字符后缀)");
        assertEquals(".parent-bar:hover", expandAmpersand("&-bar:hover", ".parent"), "&-bar:hover 后缀+伪类");
        assertEquals(".parent_bar", expandAmpersand("&_bar", ".parent"), "&_bar → .parent_bar (下划线后缀)");
        assertEquals(".parent_bar.primary", expandAmpersand("&_bar.primary", ".parent"), "&_bar.primary");
        assertEquals(".parent-item.active", expandAmpersand("&-item.active", ".parent"), "&-item.active 后缀+类");
        assertEquals(".parent-btn--large", expandAmpersand("&-btn--large", ".parent"), "&-btn--large BEM modifier");

        // 多级嵌套：.parent { &-bar { &-baz {} } }
        String level1 = expandAmpersand("&-bar", ".parent");
        assertEquals(".parent-bar", level1, "嵌套 L1: &-bar");
        String level2 = expandAmpersand("&-baz", level1);
        assertEquals(".parent-bar-baz", level2, "嵌套 L2: &-baz on .parent-bar");

        // 三级嵌套 .a-b-c
        String l1 = expandAmpersand("&-b", ".a");
        String l2 = expandAmpersand("&-c", l1);
        assertEquals(".a-b-c", l2, "三级后缀嵌套 .a { &-b { &-c {} } }");

        System.out.println("  ✓ & 后缀拼接（核心Less特性）全部通过");
    }

    static void testClassCombination() {
        section("类名拼接 &.className");
        assertEquals(".parent.active", expandAmpersand("&.active", ".parent"), "&.active 单类拼接");
        assertEquals(".parent.active.open", expandAmpersand("&.active.open", ".parent"), "多类拼接");
        assertEquals(".parent:not(.hidden)", expandAmpersand("&:not(.hidden)", ".parent"), "&:not() 伪类");
        assertEquals(".parent:is(.a, .b)", expandAmpersand("&:is(.a, .b)", ".parent"), "&:is()");
        System.out.println("  ✓ 类名拼接通过");
    }

    static void testMultiAmpersand() {
        section("多 & 组合选择器");
        assertEquals(".parent + .parent", expandAmpersand("& + &", ".parent"), "& + & 相邻兄弟");
        assertEquals(".parent .parent", expandAmpersand("& &", ".parent"), "& & 后代");
        assertEquals(".parent > .parent", expandAmpersand("& > &", ".parent"), "& > & 直接子");
        assertEquals(".parent ~ .parent", expandAmpersand("& ~ &", ".parent"), "& ~ & 通用兄弟");
        System.out.println("  ✓ 多&组合通过");
    }

    static void testMultiSelector() {
        section("多选择器逗号分隔 (笛卡尔积)");
        assertEquals(".a .c, .b .c", expandAmpersand(".c", ".a, .b"), "多父选 无&");
        assertEquals(".parent-a, .parent-b", expandAmpersand("&-a, &-b", ".parent"), "多子选 带&");
        // 多父 + 多子 = 笛卡尔积 4 个
        String res = expandAmpersand("&-c, &-d", ".a, .b");
        Set<String> actual = new HashSet<>(Arrays.asList(res.split(", ")));
        Set<String> expected = new HashSet<>(Arrays.asList(".a-c", ".a-d", ".b-c", ".b-d"));
        assertEquals(expected, actual, "多父 × 多子 → 笛卡尔积 (4个)");
        System.out.println("  ✓ 多选择器笛卡尔积通过");
    }

    static void testAttributeSelectors() {
        section("属性选择器 [attr]");
        assertEquals(".parent[disabled]", expandAmpersand("&[disabled]", ".parent"), "&[disabled]");
        assertEquals(".parent[data-type=primary]", expandAmpersand("&[data-type=primary]", ".parent"), "&[data-type=primary]");
        assertEquals(".parent-btn[aria-hidden=true]", expandAmpersand("&-btn[aria-hidden=true]", ".parent"), "&-btn + 属性");
        System.out.println("  ✓ 属性选择器通过");
    }

    static void testVariableInterpolation() {
        section("⭐ Less 变量插值 @{var}");
        // 关键：不能让 @{...} 被 & 逻辑破坏
        assertEquals(".parent-@{selector}", expandAmpersand("&-@{selector}", ".parent"), "&-@{var} 保留变量插值");
        assertEquals(".parent @{child}", expandAmpersand("@{child}", ".parent"), "纯变量选择器被当作嵌套");
        assertEquals(".foo-@{a}-bar-@{b}", expandAmpersand("&-@{a}-bar-@{b}", ".foo"), "多变量插值保留");
        System.out.println("  ✓ Less @{var} 变量插值处理通过");
    }

    static void testRealWorldBemScenarios() {
        section("⭐ 真实 Less / BEM 场景综合验证");

        // = BEM block__element--modifier =
        String block = ".block";
        String elem = expandAmpersand("&__element", block);
        assertEquals(".block__element", elem, "BEM L1: block → block__element");
        String mod = expandAmpersand("&--modifier", elem);
        assertEquals(".block__element--modifier", mod, "BEM L2: element → element--modifier");

        // = 按钮状态: .button.primary:hover =
        String btn = ".button";
        String btnPrimary = expandAmpersand("&.primary", btn);
        assertEquals(".button.primary", btnPrimary, "按钮: &.primary");
        String btnHover = expandAmpersand("&:hover", btnPrimary);
        assertEquals(".button.primary:hover", btnHover, "按钮 .primary → hover");

        // = 列表项相邻: .list-item + .list-item =
        String list = ".list";
        String listItem = expandAmpersand("&-item", list);
        assertEquals(".list-item", listItem, "列表: &-item");
        String adjacent = expandAmpersand("& + &", listItem);
        assertEquals(".list-item + .list-item", adjacent, "相邻列表项（设置间距的常用写法）");

        // = 四级嵌套 .app-header-nav-item =
        String app = ".app";
        String header = expandAmpersand("&-header", app);
        String nav = expandAmpersand("&-nav", header);
        String item = expandAmpersand("&-item", nav);
        assertEquals(".app-header-nav-item", item, "四级后缀 .app → &-header → &-nav → &-item");

        // = 复杂组合: .card-header > .card-header-title =
        String card = ".card";
        String cardHeader = expandAmpersand("&-header", card);
        String headerTitle = expandAmpersand("& > &-title", cardHeader);
        assertEquals(".card-header > .card-header-title", headerTitle, "复杂: & > &-title 组合");

        // = BEM 双下划线 .menu__item__icon =
        String menu = ".menu";
        String mItem = expandAmpersand("&__item", menu);
        assertEquals(".menu__item", mItem, "menu__item");
        String mIcon = expandAmpersand("&__icon", mItem);
        assertEquals(".menu__item__icon", mIcon, "menu__item__icon");

        System.out.println("  ✓ 所有真实 Less/BEM 场景通过!");
    }
}
