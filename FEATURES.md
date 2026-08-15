# DashStyle 功能说明文档

> **DashStyle CSS Module Support** — IntelliJ/WebStorm 插件。为 CSS Modules（`styles["kebab"]` / `styles.fooBar`）、LESS/SCSS 嵌套、inline style 抽取、SCSS↔LESS↔CSS 互转、项目颜色变量化等提供**智能补全 + 跳转 + 检查 + 重构 + 批量操作**能力。

---

## 一、核心能力总览

| # | 模块 | 能力说明 |
|---|------|---------|
| 1 | **选择器展开引擎** | 为 LESS / SCSS / 原生 CSS Nesting 提供选择器全量展开（`&` 后缀拼接、BEM `__`、Sass 插值、`@at-root`、`%placeholder`），配合 IDE 缓存（`CachedValue`）实现高性能 |
| 2 | **CSS Module 智能跳转 & 补全** | `styles["foo-bar"]` 字符串字面量 + `styles.fooBar` member access 双形态；Vue `<style module>` / 本地对象字面量 / 导入 Module 文件三容器 |
| 3 | **Inline Style → CSS Module 一键抽取**（Intention Action） | `style={{...}}` 或 `:style="{...}"` → 自动语义推断类名 + 重命名输入框 + 追加到 Module 文件 |
| 4 | **Inline Style JSON → CSS 复制粘贴**（CopyPastePreProcessor） | 支持 JS 对象字面量（key 无引号/单引号/尾随逗号/注释）、unitless 属性、负数、transform 函数区分 scale/rotate/translate 单位 |
| 5 | **代码检查 (Inspection)** | 未使用 CSS Module class 置灰 + 删除 Fix；单文件重复 CSS 声明检测 + 抽取公共类（生成 `@extend` 回所有重复点） |
| 6 | **CSS Module 导入自动化** | `styles.xxx` 没 import 时 Alt+Enter 自动扫描同目录 `*.module.*` 并加 import；复制 `styles.xxx` → 粘贴时自动带 import 元数据 |
| 7 | **TSX/Vue 复制 → CSS 规则随动** | 复制 TSX/Vue 标签时，把关联 class 的 CSS 规则打包；粘贴时目标有 styles import 就追加，没就新建同名 `.module.css` 并自动 import |
| 8 | **SCSS / LESS / 原生 CSS Nesting 互转**（Intention Action） | 常用子集：变量、嵌套、`@extend`、mixin、插值；不支持的 construct 保留并加提示注释 |
| 9 | **项目公共颜色 → CSS Variable 抽取**（AnAction） | 扫选区 / 文件 / 目录 / 全项目 → 归一化等值分组 → 语义变量名（primary/success/danger/neutral…）→ 弹窗编辑 + 色块预览 → 确认后 `:root { --color-xxx }` 进剪贴板并就地替换为 `var(--x)` |
| 10 | **Flex/Grid 布局可视化预览**（gutter LineMarker + 交互弹窗） | `display:flex/grid` 行前渲染迷你布局图（WebStorm 颜色预览式），悬浮看放大预览、点击弹出可调交互面板（拖拽子项 align-self、拖拽 grid 轨道） |
| 11 | **CSS 尺寸/单位换算助手**（Inlay） | 长度值行尾显示 `px ≈ rem ≈ vw` 换算、`clamp()` 在视口下的实际取值、`calc()` 简化值 |
| 12 | **Tailwind 类补全 + CSS 预览**（`@apply` 内自动补全） | 内置 200+ 常用 Tailwind 类，候选右侧灰字显示该类展开后的 CSS 声明，按 Enter 直接补全 |

---

## 二、各功能详细说明

### 2.1 选择器展开（Util.expandSelector）
位置：[Util.kt](file:///workspace/src/main/kotlin/com/pan/dashstyle/Util.kt)

- 支持所有常见 `&` 场景
  - `&-suffix`、`&--suffix`、`&__element`（BEM）
  - `&.class`（父子同节点加类）、`& + &`、`& > &`、`.parent &`
  - 多层嵌套 + 逗号选择器的**笛卡尔积**
  - Less 插值 `@{var}`、Sass 插值 `#{$var}` + `@at-root` + Sass 占位符 `%placeholder`
- **性能优化**：
  - 所有正则（LESS/SASS插值、逗号切分、颜色类）均在 companion object **顶层预编译**
  - 选择器结果挂 `CachedValuesManager`，失效范围为当前 `CssRuleset` 元素（变更即失效）
  - 选择器拆分/合并采用 **提前剪枝**：无 `&` 的单层选择器直接短路返回，避免冗余计算

### 2.2 CSS Module 引用 & 补全
- `styles["kebab-case"]`（字符串访问）—— 早期支持：补全建议里附带 camelCase 名、`Enter` 后自动闭合引号、Ctrl+Click 跳转到对应 `.kebab-case` ruleset
- `styles.fooBar`（member access，v1.2 后新增）—— 经 [StyleMemberAccessReference.kt](file:///workspace/src/main/kotlin/com/pan/dashstyle/StyleMemberAccessReference.kt) 的 `PsiReferenceContributor` 注入引用，支持跳转/补全/找引用
- 三种 **CSS 容器识别**（[CssModuleResolver.kt](file:///workspace/src/main/kotlin/com/pan/dashstyle/CssModuleResolver.kt)）：
  1. `ImportedFile`：`import styles from './Foo.module.css'`
  2. `VueStyleTag`：Vue `<style module>`
  3. `LocalObjectLiteral`：TS/JS 本地 `const styles = { fooBar: '.foo-bar' }`
- **文档悬浮**：[DashStyleDocumentationProvider.kt](file:///workspace/src/main/kotlin/com/pan/dashstyle/DashStyleDocumentationProvider.kt) —— 鼠标悬浮 `styles.xxx`，弹窗展示展开后完整选择器 + 声明列表 + 源文件位置

### 2.3 Inline Style → CSS Module 抽取（Alt+Enter）
位置：[InlineStyleToCssModuleIntention.kt](file:///workspace/src/main/kotlin/com/pan/dashstyle/InlineStyleToCssModuleIntention.kt)

1. **语义类名推断引擎**（[SemanticClassNameInferrer.kt](file:///workspace/src/main/kotlin/com/pan/dashstyle/SemanticClassNameInferrer.kt)）
   - 组件名 → 候选 kebab 名（如 `UserCard` → `user-card`）
   - 父/兄弟组件的 className 作为上下文（父是 `.dialog`，子可能是 `.dialog-header`）
   - style 属性语义映射（`display:flex + align-items:center + justify-content:center` → `flex-center`；`backgroundColor + boxShadow` → `card` 等）
   - 产生一组从具体到抽象的候选，Alt+Enter 默认用最高优先级，支持"重命名风格"输入框让你改名
2. **自动定位目标 CSS Module**
   - Vue → 当前 `<style module>`
   - TSX → 查找 import 指向的 `*.module.css|scss|less`，否则用文件同目录同名 Module，没有就新建并 prepend import
3. **替换 style 属性**：React `style={{...}}` → `className={styles.fooBar}`；Vue `:style="{...}"` → `:class="$style.fooBar"`

### 2.4 JSON→CSS 复制粘贴修复
位置：[JsonToCssCopyPastePreProcessor.kt](file:///workspace/src/main/kotlin/com/pan/dashstyle/JsonToCssCopyPastePreProcessor.kt)

解决了复制 DevTools Style/React DevTools 中的 JS 字面量 style 对象到 CSS 文件时的多个 bug：
- **key 无引号** → `jsLiteralToStrictJson` 预处理把 JS 对象字面量（单引号/无引号 key、`//` 注释、尾随逗号）转严格 JSON
- **unitless 属性不加 px**：opacity / zIndex / flex / zoom / lineHeight-clamp / scale 倍数 等均按 W3C 白名单识别
- **负数**：保留负号 + 加 px
- **0**：保留 `0` 不加单位
- **transform 函数区分**：
  - `scale*`, `matrix*` 等倍数型 → **不加单位**
  - `rotate*`, `skew*` → 加 **deg**
  - `translate*`, `perspective` → 加 **px**
- 对外公开入口：`JsonToCssCopyPastePreProcessor.Util.convertJsonToCss(raw)` / `convertOrNull(raw)`

### 2.5 Inspection 类检查
| Inspection | 触发 | 修复 |
|---|---|---|
| [UnusedCssModuleClassInspection](file:///workspace/src/main/kotlin/com/pan/dashstyle/UnusedCssModuleClassInspection.kt) | CSS Module 文件中存在定义但 TSX/Vue 里 `styles.xxx` / `styles["xxx"]` 未引用时类名**置灰**；扫描到动态 `styles[expr]` 自动关闭该文件的检查避免误报 | QuickFix：删除未用 ruleset |
| [DuplicateCssDeclarationsInspection](file:///workspace/src/main/kotlin/com/pan/dashstyle/DuplicateCssDeclarationsInspection.kt) | 同一 CSS 文件里 ≥2 个 ruleset 声明块完全相同 → **黄色 + 波浪线**（仿 TS 重复代码检查视觉） | QuickFix：抽取为公共类 `.common-name`，重复点删除原声明并按语言替换为合并引用——**LESS** 用 mixin 调用 `.common-name();`（LESS 的 ruleset 本身就是 mixin），**SCSS/Sass** 用 `@extend .common-name;`，纯 **CSS** 无 extend/mixin 只删声明不动选择器 |

### 2.6 导入自动化
> 说明：复制 `styles.xxx` → 粘贴时自动带 import，IntelliJ 平台自带的「Add import on paste」已能胜任，
> DashStyle 自实现的 `CssModuleImportCopyPasteProcessor` 已于 v1.2.1 移除注册，避免两条逻辑冲突。
- **[AddCssModuleImportIntention](file:///workspace/src/main/kotlin/com/pan/dashstyle/AddCssModuleImportIntention.kt)**：`styles` 未声明 → Alt+Enter 自动扫描同目录 `BaseName.module.(css|scss|less)`，匹配则注入 `import styles from './BaseName.module.css'`；Vue 环境自动进 `<script setup>` 顶部

### 2.7 TSX/Vue 标签复制 → CSS 规则复制
位置：[CssBundleCopyPastePostProcessor.kt](file:///workspace/src/main/kotlin/com/pan/dashstyle/CssBundleCopyPastePostProcessor.kt)

- 复制选区（任意 TSX/Vue 片段）→ 预处理阶段遍历被选 PSI，收集所有 `styles.xxx` / `styles["xxx"]` / `:class="$style.xxx"` 点 → 解析对应 ruleset → 序列化成 Base64 JSON marker 附到复制文本末尾：
  ```
  /* __DS_CSS_BUNDLE__: eyJydWxlcyI6W3sic2VsZWN0b3IiOiI... */
  ```
- 粘贴时：剥离 marker → 解析 JSON → 等粘贴落地后 `invokeLater + WriteCommandAction`：
  - 目标有**同 from** 或**同 binding + 候选解析**的 styles import → 对应 CSS Module 文件末尾追加 rules
  - 没有就找目标文件同目录已存在的 `BaseName.module.*` 追加 + 自动补 import
  - 都没有就**新建** `BaseName.module.css`，写 rules，然后顶部补 import

### 2.8 SCSS / LESS / 原生 CSS Nesting 互转
位置：[CssPreprocessorTranspileIntention.kt](file:///workspace/src/main/kotlin/com/pan/dashstyle/CssPreprocessorTranspileIntention.kt)

- **支持子集**：变量 (`$x` / `@x` / `--x`)、嵌套、`@extend`、`@mixin`+`@include`（可转为 CSS Nesting + 注释，或对 SCSS↔LESS 做直译）、插值 `#{}` / `@{}`
- **Vue 兼容**：检测 `<style lang="scss|less|css">` 的 tag，直接替换 `<style>` value；纯 CSS 文件就替换整个文件内容
- **不支持的 construct**（`@for`, `@if`, 内置函数等）保留**不破坏**，并在上方插入 `/* DashStyle transpile warning: <原内容> 未处理 */` 注释

### 2.9 项目颜色 → CSS Variable 抽取（新增 2026-08）
位置：[ExtractColorsAction.kt](file:///workspace/src/main/kotlin/com/pan/dashstyle/ExtractColorsAction.kt)

**入口**：Code 菜单（Generate 之前）或 Help→Find Action 搜 **`DashStyle: Extract Colors as CSS Variables`**

#### 扫描范围（按优先级自动）
1. **当前编辑器选区**：仅扫选区中的颜色
2. **ProjectView 选中文件/目录**：递归扫描 CSS/SCSS/LESS/Vue（`.vue` 只提取 `<style>` 内部，防止 script/template 字符串被识别）；跳过 `node_modules` / `.git` / `build` / `dist`
3. **未指定**：全项目 CSS/SCSS/LESS/SASS 文件

#### 颜色归一化 & 等值分组
在 [Util.kt:257-303](file:///workspace/src/main/kotlin/com/pan/dashstyle/Util.kt#L257-L303) `normalizeColor()`：
- HEX3 → HEX6：`#fff` ≡ `#ffffff`
- HEX8 alpha=FF → 降为 HEX6：`#ffffff`
- rgba alpha=1 → rgb：`rgba(255,0,0,1)` ≡ `rgb(255,0,0)` ≡ `rgb(255 0 0)` ≡ `red`
- hsla alpha=1 → hsl
- 148 种命名颜色 case-insensitive

#### 语义变量名建议（[Util.suggestColorVarName](file:///workspace/src/main/kotlin/com/pan/dashstyle/Util.kt#L358-L401)）
基于 RGB 分量决策：
| 分量特征 | 变量名 |
|---|---|
| 蓝色主色 | `--color-primary` |
| 红色主色 | `--color-danger` |
| 绿色主色 | `--color-success` |
| 黄色主色 | `--color-warning` |
| 红+绿+亮度高（粉/橙调） | `--color-accent` |
| 灰度 & 暗 | `--color-dark` / `--color-text-dark` / `--color-muted` / `--color-neutral` / `--color-bg-light` |
| 其他 | `--color-1 / --color-2 / ...` |
| 冲突自动追加后缀 | `-2 / -3 / ...` |

#### 对话框 & 确认动作
- JBTable 5 列：**Swatch 色块 / 变量名（可编辑） / 出现次数 / Sample 原值**
- CheckBox：`Replace color references with var(--x) in source files`（默认勾选）
- 底部预览：实时 `:root { --color-xxx: value; ... }`
- **OK 后**：
  1. Clipboard：`:root { ... }` 复制到剪贴板
  2. 源文件：勾选替换时按 Document 分组、**从后向前 offset 逆序替换**为 `var(--color-xxx)`，每次替换前二次 `normalizeColor` 校验，避免并发改坏

#### 性能优化（见 §三）

### 2.10 Flex/Grid 布局可视化预览（新增 2026-08）
位置：[LayoutPreviewGutterMarkerProvider.kt](file:///workspace/src/main/kotlin/com/pan/dashstyle/LayoutPreviewGutterMarkerProvider.kt) / [LayoutPreviewPopup.kt](file:///workspace/src/main/kotlin/com/pan/dashstyle/LayoutPreviewPopup.kt)

- **gutter 迷你布局图**：`display:flex|grid` 行前渲染 32×32 布局图（WebStorm 颜色预览式），每条布局属性行前渲染「聚焦该属性」的图标；布局解析复用 [LayoutContextResolver.kt](file:///workspace/src/main/kotlin/com/pan/dashstyle/LayoutContextResolver.kt)，按文件 `CachedValue` 缓存
- **悬浮放大预览**：悬停 gutter 图标，把同一布局渲染成 ~200×130 的 PNG 作为 HTML tooltip，达到与行内预览相当的清晰度
- **点击交互弹窗**：弹出可调面板，实时画布随控件重绘
  - flex：justify-content / align-items / align-content / flex-direction / flex-wrap / gap，子项**拖动自适应 align-self**
  - grid：grid-template-columns/rows（**拖动轨道分隔线调 fr/px**）、gap、对齐
- **写回**：「应用到样式」把当前值写回 CSS ruleset（已存在改值，缺失则新增）
- **纯逻辑层**：[FlexLayoutResolver.kt](file:///workspace/src/main/kotlin/com/pan/dashstyle/FlexLayoutResolver.kt)（含逐子项 alignSelf）/ [GridLayoutResolver.kt](file:///workspace/src/main/kotlin/com/pan/dashstyle/GridLayoutResolver.kt)（含轨道 resize），经 [LayoutModel.kt](file:///workspace/src/main/kotlin/com/pan/dashstyle/LayoutModel.kt) 密封类统一抽象，可独立单测

### 2.11 CSS 尺寸/单位换算助手（新增 2026-08）
位置：[CssUnitInlayProvider.kt](file:///workspace/src/main/kotlin/com/pan/dashstyle/CssUnitInlayProvider.kt) / [CssUnitAssistant.kt](file:///workspace/src/main/kotlin/com/pan/dashstyle/CssUnitAssistant.kt)

- **px ↔ rem ↔ vw 互转**：长度值行尾显示 `12px ≈ 0.75rem ≈ 0.83vw`（可配置根字号与视口宽）
- **clamp() 解析**：`clamp(16px, 2vw, 24px)` → 在给定视口下实际取值，标注夹到 min/max
- **calc() 简化**：支持 `+ - * /` 与括号、px/rem/vw 混算，返回合并后的 px
- 纯逻辑层不依赖 IDE SDK，非法输入静默返回 null，绝不抛异常

### 2.12 Tailwind 类补全 + CSS 预览（新增 2026-08）
位置：[TailwindClassCompletionContributor.kt](file:///workspace/src/main/kotlin/com/pan/dashstyle/TailwindClassCompletionContributor.kt) / [TailwindClassResolver.kt](file:///workspace/src/main/kotlin/com/pan/dashstyle/TailwindClassResolver.kt)

- **触发位置**：CSS 的 `@apply` 指令内（`abc { @apply <光标>; }`），CSS/SCSS/LESS 三种语言均生效
- **候选**：内置 200+ 常用 Tailwind 类（布局 / flex / grid / 间距 / 尺寸 / 排版 / 文本颜色 / 背景 / 边框 / 阴影 / 过渡 / 变换 / 交互等），按输入前缀匹配，空前缀返回全部
- **预览框**：每个候选右侧灰字显示该类展开后的 CSS 声明（如 `flex → display: flex`），尾部再标分组名（`(flex)`）
- **补全**：按 Enter 直接补全，无需额外确认
- **数据源**：内置清单开箱即用，无需项目 `tailwind.config.js` / `node_modules`；如需扩展可在此类中追加 `TailwindClass(name, css, group)`
- **纯逻辑层**：[TailwindClassResolver.kt](file:///workspace/src/main/kotlin/com/pan/dashstyle/TailwindClassResolver.kt)`search(prefix)` / `find(name)`，可独立单测

---

## 三、性能优化清单

| 对象 | 问题 | 优化 |
|------|------|------|
| **选择器展开** | 每次调用重复编译正则 + 重复计算父子组合 | Regex 顶层预编译；结果挂 `CachedValue<CssRuleset>`；无 `&` 直接剪枝 |
| **颜色归一化 rgba/hsla** | 每次 `split(Regex(""))` 新编译正则 | 抽出 `RE_SPLIT_COLOR_ARGS` 顶层 |
| **scanColorsInText 命名颜色** | 每次调用用 `NAMED_COLORS.joinToString("\|")` 构造 148 分支 alternation + 回溯正则 → 极慢 | 改为 `RE_WORD_TOKEN` 扫所有字母单词 + `NAMED_COLORS.contains(HashMap O(1))` + 手动前后边界校验 |
| **scanColorsInText 标记 consumed** | `range.forEach { c[i]=true }` 内联函数开销 | `BooleanArray.fill(true, from, to)` 原生批量赋值 + `isAnyConsumed` 手写 while 循环 |
| **scanColorsInText 结构型顺序** | 先扫 HEX3 再 HEX6 会重叠误匹配 | 固定顺序 `HEX8 → HEX6 → HEX3 → RGBA → HSLA` 从长到短；冲突时 consume 数组阻挡 |
| **CopyPaste 端** | 之前靠 `CopyPastePostProcessor<T>` 泛型 + `EMPTY_DATA` 反复对象分配 | 全部落位 `CopyPastePreProcessor` 单接口双钩子；粘贴后调度走 `invokeLater` 配合一次 `WriteCommandAction` |
| **颜色工具测试** | 无基准数据 | `ColorToolingVerifier` 附带 micro-bench（10k iter 扫描 ~50 调色板 * 50 rulesets = ~9000 字符 CSS） |

> 本地 micro-bench（在 ColorToolingVerifier 里可跑）下颜色工具 **~10k 次/ms 级** 的吞吐，可放心用于大项目批量扫描。

---

## 四、测试体系

### 4.1 Gradle JUnit 5 测试（src/test/kotlin）
| 文件 | 覆盖 |
|------|------|
| [UtilTest.kt](file:///workspace/src/test/kotlin/com/pan/dashstyle/UtilTest.kt) | `kebabToCamel / camelToKebab` round-trip；**`normalizeColor` HEX3/6/8/rgb/rgba%/hsl/命名/非法**（16 用例）；**`suggestColorVarName` 语义/冲突/index**（8 用例）；**`scanColorsInText` 混合/重叠/边界/空/range**（5 用例） |
| [LessAmpersandExpansionTest.kt](file:///workspace/src/test/kotlin/com/pan/dashstyle/LessAmpersandExpansionTest.kt) | `&` 基础替换、`&-suffix`、`&__element`、多父多子笛卡尔积、逗号分隔、伪类拼接类 |
| [JsonToCssConverterTest.kt](file:///workspace/src/test/kotlin/com/pan/dashstyle/JsonToCssConverterTest.kt) | JSON 检测 / camel→kebab / 数字 px / 多属性 / **JS 字面量（无引号 key + 尾随逗号） / transform scale 无 px / unitless（opacity,z-index,flex）/ 负数与 0** |
| [TailwindClassResolverTest.kt](file:///workspace/src/test/kotlin/com/pan/dashstyle/TailwindClassResolverTest.kt) | 内置清单非空且有序、每个类带 CSS/分组、前缀/大小写/未知搜索、`find` 精确匹配、关键类 CSS 声明抽查 |

### 4.2 独立验证器（src/test/java）—— 零依赖、零环境
> `javac ...Verifier.java && java ...Verifier` 即可跑，方便未配置 Gradle/IntelliJ 环境也能回归。

| 文件 | 覆盖 |
|------|------|
| [LessFeatureVerifier.java](file:///workspace/src/test/java/com/pan/dashstyle/LessFeatureVerifier.java) | 纯 Java 版 kebab/camel + Less `&` 扩展全部真实 BEM 场景 |
| [InlineStyleConverterVerifier.java](file:///workspace/src/test/java/com/pan/dashstyle/InlineStyleConverterVerifier.java) | 纯 Java 版 JS 字面量 → 严格 JSON + 数字单位 + transform 函数区分 + 数组简写属性 |
| **[ColorToolingVerifier.java](file:///workspace/src/test/java/com/pan/dashstyle/ColorToolingVerifier.java)**（新增）| 纯 Java 版 normalizeColor / suggestColorVarName / scanColorsInText 全用例 + micro-bench（10k iter 吞吐计时） |

---

## 五、配置 & 构建

### 腾讯镜像 init 脚本
项目根目录 [_local_init.gradle.kts](file:///workspace/_local_init.gradle.kts) 统一：
- `settingsEvaluated { pluginManagement { ... Tencent ... } }` — 插件仓
- `allprojects { repositories -> replace MavenCentral/GPP url with Tencent }` + JetBrains Snapshots/Releases 专用仓（避免 ideaIU:LATEST-EAP-SNAPSHOT 被 MavenCentral 429 限速）

调用：
```bash
./gradlew --init-script _local_init.gradle.kts buildPlugin
# 如无 wrapper jar，直接用系统 gradle（>=8.5）：
gradle --init-script _local_init.gradle.kts compileKotlin compileTestKotlin buildPlugin -x test
```

### Gradle & JDK 兼容
- `gradle.properties`：`org.gradle.java.installations.auto-detect=false` + `org.gradle.java.home=...java/17.0.2`（屏蔽 JDK 25 自动探测导致 Kotlin DSL `JavaVersion.parse("25.0.2")` 崩）
- Kotlin 2.1.0 + `-Xsuppress-version-warnings`：容忍 WebStorm-2025.3 SDK 中 Kotlin 2.2 metadata 高版本（仅警告不错误）
- JUnit 5、Kotlin reflect 依赖

---

## 六、扩展点注册总览
位置：[plugin.xml](file:///workspace/src/main/resources/META-INF/plugin.xml)

- `<extensions defaultExtensionNs="com.intellij">`
  - `PsiReferenceContributor`（字符串 + member access 双引用提供者）
  - `documentationProvider`（悬浮展示完整 CSS 规则）
  - `localInspection`（未使用 class / 重复声明）
  - `annotator` ×3 + `highlightVisitor`（CSS Module class 置灰 / 重复声明波浪线；Vue/Svelte 内嵌 `<style module>` 场景）
  - `lineMarkerProvider`（flex/grid 布局 gutter 预览）
  - `inlayProvider`（尺寸/单位换算助手）
  - `completion.contributor` ×3（Tailwind 类补全，CSS/SCSS/LESS）
  - `copyPastePreProcessor`（JSON→CSS / TSX+CSS 打包粘贴）
  - `intentionAction` ×4（Inline 抽取 / 缺失 class 创建 / styles import 自动补 / 预处理互转）
- `<actions>`
  - `DashStyle.ExtractColors`：CodeMenu(Generate 之前) + HelpFindAction — **项目颜色 → CSS Variable 抽取**

---

## 七、常见操作指引

| 目标 | 操作 |
|------|------|
| 把 TSX 里的 `style={{...}}` 抽成 CSS 类 | 光标定位到 `style` → **Alt+Enter → DashStyle: Extract inline style to CSS Module** |
| 找不到 `styles.xxx` 跳转 / 补全 | 先检查是否已 import；未 import 光标放 `styles` 上 **Alt+Enter** 自动注入 |
| 写了 `styles.fooBar` 但是 CSS 里没有类 | 光标放 `fooBar` 上 **Alt+Enter → DashStyle: Create missing CSS class** → 光标直接落大括号里 |
| 两个 CSS class 声明一模一样被标黄 | **Alt+Enter → Extract common class** → 所有重复点自动 `@extend` |
| 复制 `<UserCard />` 到新文件但想连带它的 CSS | 正常 `Cmd/Ctrl+C` + `Cmd/Ctrl+V` 即可，DashStyle 自动打包 |
| `.scss` 想转 `.less` 或原生 CSS Nesting | 光标在 `<style lang="...">` 或文件内任意处 → **Alt+Enter → DashStyle: Transpile preprocessor**，下拉选目标格式 |
| 想把项目里颜色统一抽成变量并替换 | 打开 Code 菜单 → **DashStyle: Extract Colors as CSS Variables** → 编辑变量名 → OK → 剪贴板拿 `:root { }` 并就地替换 |
| 在 CSS 里写 Tailwind 工具类 | 光标放进 `@apply ` 后 → 输入前缀（如 `ju`）→ 下拉右侧灰字预览 CSS → **Enter** 补全 |

---

*文档版本与 DashStyle v1.2.1 对应。*
