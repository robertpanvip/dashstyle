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

- **Tailwind 类补全 + CSS 预览**：在 CSS 的 `@apply` 指令内自动补全（`Ctrl/Cmd+Space` 手动触发，`Enter` 确认），内置 200+ 常用 Tailwind 类，候选右侧灰字显示该类展开后的 CSS 声明
- **缺失类自动生成 Tailwind CSS**：`styles.xxx` 引用的类缺失且 `xxx` 是 Tailwind 原子化类时，`Alt+Enter` 自动生成对应展开 CSS（如 `.flex { display: flex }`）

### 文档

各功能的详细说明、性能优化清单、测试体系与构建指引见 **[FEATURES.md](FEATURES.md)**。

### 安装

用 IntelliJ IDEA `: Install Plugin from Disk...` 选择 `build/distributions/*.zip`，或本地 `gradle buildPlugin` 打包后安装。

---

Enjoy better development experience with CSS Modules in IntelliJ IDEA!