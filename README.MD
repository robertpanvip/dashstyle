# CSS Modules Dash Style Support

为 IntelliJ IDEA 平台提供对 CSS Modules 的增强支持，专门针对 `styles["foo-bar"]` 形式的字符串键访问。

### 主要功能

- **代码补全**
  在 `styles["` 中自动补全导入的 CSS Modules 中的类名（kebab-case 形式），补全项右侧会显示对应的 camelCase 名称。

- **跳转导航**
  Ctrl+点击字符串中的 `"foo-bar"` 可直接跳转到 CSS 文件中对应的 `.foo-bar` ruleset，或本地 JS/TS 对象中定义的 `fooBar` 属性。

- **智能引号处理**
  补全完成后自动插入匹配的结束引号，并将光标置于引号内部，便于继续编辑。

- **双来源支持**
  同时支持从导入的 CSS/SCSS/LESS 文件（CSS Modules 模式）以及本地定义的 JS/TS 对象（如 `const styles = { ... }`）中获取类名。

### 适用场景

适用于 React、Vue 或任何使用 CSS Modules 的项目，让 kebab-case 类名在字符串访问时也能享有完整的 IDE 智能支持（补全、跳转、重构等）。

Enjoy better development experience with CSS Modules in IntelliJ IDEA!