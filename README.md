# CSS Modules Dash Style Support

为 IntelliJ IDEA / WebStorm 提供对 CSS Modules 的增强支持，专门针对 `styles["foo-bar"]` 与 `styles.fooBar` 两种访问形态，并覆盖 CSS/LESS/SCSS 的智能补全、跳转、检查、重构与批量操作。

### 核心功能

- **CSS Module 智能跳转 & 补全**
  - `styles["foo-bar"]`（字符串字面量）与 `styles.fooBar`（member access）双形态
  - 支持导入的 CSS/SCSS/LESS 文件、Vue `<style module>`、本地 JS/TS 对象字面量三种容器
  - 补全项右侧显示 kebab-case 对应的 camelCase 名，Enter 后自动闭合引号

- **Inline Style → CSS Module 一键抽取**（Alt+Enter）
  - `style={{...}}` / `:style="{...}"` → 自动语义推断类名 + 重命名输入框 + 追加到 Module 文件

- **Inline Style JSON → CSS 复制粘贴**：自动修复 JS 对象字面量粘贴到 CSS 时的单位问题（unitless、负数、transform 函数区分）

- **代码检查**：未使用 CSS Module class 置灰 + 删除 Fix；单文件重复声明检测 + 抽取公共类 `@extend`

- **导入自动化**：缺失 import 时 Alt+Enter 自动扫描同目录 `*.module.*` 并注入

- **TSX/Vue 复制 → CSS 规则随动**：复制标签时把关联 CSS 规则打包，粘贴时自动追加/新建 Module 并补 import

- **SCSS / LESS / 原生 CSS Nesting 互转**（Alt+Enter）：变量、嵌套、`@extend`、mixin、插值；不支持的 construct 保留并加提示注释

- **项目颜色 → CSS Variable 抽取**（Code 菜单）：扫选区/文件/目录/全项目 → 语义变量名 → 预览编辑 → `:root` 进剪贴板并就地替换

- **Flex/Grid 布局可视化预览**：`display:flex/grid` 行前 gutter 迷你布局图，悬浮放大预览、点击弹出可调交互面板（拖拽子项 align-self、拖拽轨道）

- **CSS 尺寸/单位换算助手**：长度值行尾显示 `px ≈ rem ≈ vw` 换算、`clamp()` 实际取值、`calc()` 简化值

- **Tailwind 类补全 + CSS 预览**：在 CSS 的 `@apply` 指令内自动补全，内置 200+ 常用 Tailwind 类，候选右侧灰字显示该类展开后的 CSS 声明，Enter 直接补全

### 文档

各功能的详细说明、性能优化清单、测试体系与构建指引见 **[FEATURES.md](FEATURES.md)**。

### 安装

用 IntelliJ IDEA `: Install Plugin from Disk...` 选择 `build/distributions/*.zip`，或本地 `gradle buildPlugin` 打包后安装。

---

Enjoy better development experience with CSS Modules in IntelliJ IDEA!