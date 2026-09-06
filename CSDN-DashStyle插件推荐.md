# 安利一款小众但好用的 IDEA 插件：DashStyle —— 让内联样式和 CSS Module 之间的墙消失

> 你是不是也写过这样的 React 组件：`style={{ display: 'flex', gap: 8, paddingTop: 12 }}`，写的时候很爽，改的时候想哭？想把内联样式迁到 CSS Module，又对着一堆 `camelCase → kebab-case`、单位补全、`import styles from './index.module.css'` 望而却步？这篇文章安利一个开源的 IntelliJ IDEA / WebStorm 插件 —— **DashStyle CSS Module Support**，它把上面这些脏活全包了。

- 开源地址：https://github.com/robertpanvip/dashstyle
- 下载安装：GitHub Releases 页面下载 `DashStyle-x.x.x.zip`，IDE 内 `Install Plugin from Disk...` 安装
- 适用技术栈：React / Vue / Angular + CSS / SCSS / LESS（含 `.sass` 缩进语法、Vue `<style module>`）

---

## 一、它主要干啥？

一句话总结：**DashStyle 是一个补齐 IntelliJ 系 IDE 对 CSS Modules 支持短板的增强插件**，核心解决两件事：

1. **内联样式（inline style）→ CSS Module 的双向迁移**：复制粘贴自动转换、Alt+Enter 一键抽取、整文件批量迁移；
2. **CSS Module 的日常编码体验**：`styles.xxx` 的智能补全、跳转、悬浮预览、未使用检查、Tailwind 集成。

下面按"你遇到的痛点 → 它怎么解决"来展开。

## 二、痛点一：内联样式想迁去 CSS Module，手动改太痛苦

内联样式的天然缺陷大家都知道：伪类 / 媒体查询写不了、样式无法复用、和设计系统脱节。但迁移的成本也很真实——

```jsx
// 迁移前：这样的代码
<div style={{ display: 'flex', gap: 8, paddingTop: 12, lineHeight: 1.5 }}>...</div>

// 迁移后：要手写成这样，全靠人肉
<div className={styles.toolbar}>...</div>
```

```css
/* 手写时还得注意：gap 要补 px、lineHeight 不用补、
   paddingTop 要转成 kebab-case……一不留神就写错 */
.toolbar {
  display: flex;
  gap: 8px;
  padding-top: 12px;
  line-height: 1.5;
}
```

DashStyle 提供了**三条迁移通道**，按粒度从细到粗：

### 1. 复制粘贴即转换（最轻量）

直接把 JS 里的样式对象复制、粘贴到任意 CSS / SCSS / LESS 文件里，插件自动完成转换：

- `paddingTop` → `padding-top`（camelCase 转 kebab-case）
- `gap: 8` → `gap: 8px`（数字自动补单位）
- `opacity: 0.5`、`zIndex: 99` → 保持无单位（对齐 react-dom 官方 `isUnitlessNumber` 名单，行为和 React 渲染完全一致）
- `"margin": 0` → `margin: 0`（零值不加单位）
- `"transform": ["rotate(15deg)", "translateY(10px)"]` → `transform: rotate(15deg) translateY(10px);`（shorthand 数组展开）
- `undefined` 值自动跳过、`font-family` 带空格自动加引号

不光认 React 的 `style={{...}}`，**Vue 的 `:style` / `v-bind:style`、Angular 的 `[style]` / `[ngStyle]`** 也认，连 ngStyle 的单位后缀键 `'font-size.px'` 都能正确处理。

### 2. Alt+Enter 一键抽取（推荐日常用）

光标放在 `style={{...}}` 上按 `Alt+Enter`，选择"提取为 CSS Module"：

- 自动推断一个语义化类名，并弹出**可编辑的重命名输入框**（不满意直接改，不用再 Refactor）
- 自动定位同目录的 `.module.css / .module.scss / .module.less` 文件，把规则追加进去
- 组件里的 `style={{...}}` 替换成 `className={styles.xxx}`，缺 `import` 自动补上

从"想抽"到"抽完"，一次快捷键搞定。

### 3. 整文件批量迁移（重构利器）

老项目里一个组件几十个 `className="foo"` + `style={{...}}`？菜单 **Refactor → Convert className to CSS Module**，对整个文件或选区一键迁移：所有字符串 className 转成 `styles.xxx`、收集样式生成 Module 文件、补齐 import，全程一个 Write Action，可一次性 Undo。

## 三、痛点二：找不到目标 CSS Module 文件怎么办？——方言感知自动创建

这是 1.3.5 引入、我个人最喜欢的特性之一。抽取样式时如果目标 Module 文件不存在，插件不会傻傻报错，而是按**四级方言探测级联**自动创建：

1. 看旁边已有的 Module 文件是什么方言（同目录投票）；
2. 看整个项目里 `.module.scss` / `.module.less` 的数量普查，谁多用谁；
3. 看 `package.json` 依赖：有 `sass` / `node-sass` → 建 `.module.scss`；有 `less` → 建 `.module.less`；
4. 都没有 → 兜底建原生 `.module.css`。

也就是说：**SCSS 项目自动建 `.module.scss`，LESS 项目自动建 `.module.less`**，不用任何配置。Vue 单文件组件同理，没有匹配的 `<style module>` 块时自动追加一块（含 SASS 缩进语法支持）。

## 四、痛点三：`styles["foo-bar"]` 没有补全和跳转

用 CSS Modules 的人都被这两种写法折磨过：kebab-case 类名只能 `styles["foo-bar"]`，camelCase 才能 `styles.fooBar`。原生 IDE 对前者基本没有支持。DashStyle 双形态全覆盖：

- **智能补全**：两种写法都能补全，候选右侧还显示对应的另一种命名形态，`Enter` 后自动闭合引号
- **跳转**：`Ctrl/Cmd + 点击` `styles.xxx` 直接跳到 CSS 里的 `.{类名}` 规则定义
- **悬浮预览**：鼠标悬停在 `styles.xxx` 或 CSS 类名上，直接弹窗展示展开后的完整选择器和所有声明，不用来回切文件
- 容器支持三类：import 的 CSS / SCSS / LESS Module 文件、Vue `<style module>`、本地 JS / TS 对象字面量

## 五、痛点四：CSS Module 文件越写越乱没人管

插件自带两个检查（Inspection）：

- **未使用的类置灰**：`styles` 文件里没人引用的类自动变灰，Alt+Enter 一键删除，杜绝僵尸样式
- **重复声明块检测**：两个类声明内容雷同会提示，并提供 Fix 自动抽取公共类（`@extend`），样式收敛不靠自觉

## 六、彩蛋：Tailwind 集成

如果你的项目混用了 Tailwind：

- `@apply` 指令内自动补全 Tailwind 类名（内置 200+ 常用类），**候选右侧灰字直接预览该类展开后的完整 CSS**，不用再开官网查表
- `styles.flex` 这类引用的类在 CSS 文件里不存在、但名字恰好是 Tailwind 原子类时，Alt+Enter 自动生成展开后的规则，比如 `.flex { display: flex; }`

## 七、版本演进速览

| 版本 | 主要内容 |
|---|---|
| 1.3.6 | 单位转换对齐 react-dom `isUnitlessNumber`：`gap` / `grid-gap` / `flex-basis` 等数字正确补 `px`，`strokeWidth` / `scale` / `zoom` 等保持无单位 |
| 1.3.5 | 方言感知自动创建 Module 文件（package.json / 同目录 / 项目普查四级级联）；Vue `<style module>` 自动追加；SASS 缩进语法 |
| 1.3.4 | 抽取类名时可编辑重命名对话框；若干修复 |
| 1.3.3 | 中英文国际化 |

## 八、安装

1. 打开 [Releases](https://github.com/robertpanvip/dashstyle/releases) 页面，下载最新 `DashStyle-x.x.x.zip`（例如 `DashStyle-1.3.6.zip`）；
2. IntelliJ IDEA / WebStorm：`Settings → Plugins → ⚙️ → Install Plugin from Disk...` 选择 zip；
3. 重启 IDE 即可，无需任何配置，装完即用。

也可以克隆仓库自己构建：`gradle buildPlugin`，产物在 `build/distributions/` 下。

## 九、写在最后

CSS Modules 是个"人人觉得该用、手动迁移人人嫌烦"的技术。DashStyle 的思路很直接：**把迁移成本压到一次快捷键，把日常使用体验补到原生级别**。如果你在 IDEA / WebStorm 里写 React / Vue / Angular，项目里哪怕只有一丁点 CSS Modules，这款插件都值得一装。

觉得有用的话，去 [GitHub](https://github.com/robertpanvip/dashstyle) 点个 ⭐，也欢迎提 issue 反馈使用场景。
