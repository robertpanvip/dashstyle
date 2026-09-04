package com.pan.dashstyle.support

/**
 * Tailwind 类补全的纯逻辑层。
 *
 * 内置一份常用 Tailwind 类清单（类名 → 对应 CSS 声明），
 * 供 [TailwindClassCompletionContributor] 在 CSS 的 `@apply` 指令内做补全，
 * 并在候选右侧灰字显示该类展开后的 CSS 预览。
 *
 * 纯逻辑、不依赖 IDE 沙箱，可直接在 Gradle JVM 上单测。
 */
object TailwindClassResolver {

    /** 单个 Tailwind 类。 */
    data class TailwindClass(
        val name: String,
        val css: String,
        val group: String,
    )

    /** 全部内置类（按名称排序，补全候选稳定）。 */
    val all: List<TailwindClass> by lazy { CLASSES.sortedBy { it.name } }

    /** 按已输入前缀匹配类名（前缀为空返回全部）。 */
    fun search(prefix: String): List<TailwindClass> {
        val p = prefix.trim()
        if (p.isEmpty()) return all
        val lower = p.lowercase()
        return all.filter { it.name.startsWith(lower) }
    }

    /** 按名称精确查找（用于把候选 CSS 预览渲染成 tooltip）。 */
    fun find(name: String): TailwindClass? {
        val n = name.trim().lowercase()
        return all.firstOrNull { it.name == n }
    }

    private fun c(name: String, group: String, css: String) = TailwindClass(name, css, group)

    // ---------------- 内置清单（常用子集） ----------------
    private val CLASSES: List<TailwindClass> = listOf(
        // ---- 布局 ----
        c("block", "layout", "display: block"),
        c("inline-block", "layout", "display: inline-block"),
        c("inline", "layout", "display: inline"),
        c("flex", "layout", "display: flex"),
        c("inline-flex", "layout", "display: inline-flex"),
        c("grid", "layout", "display: grid"),
        c("inline-grid", "layout", "display: inline-grid"),
        c("hidden", "layout", "display: none"),
        c("table", "layout", "display: table"),
        c("relative", "layout", "position: relative"),
        c("absolute", "layout", "position: absolute"),
        c("fixed", "layout", "position: fixed"),
        c("sticky", "layout", "position: sticky"),
        c("static", "layout", "position: static"),

        // ---- Flexbox 容器 ----
        c("flex-row", "flex", "flex-direction: row"),
        c("flex-row-reverse", "flex", "flex-direction: row-reverse"),
        c("flex-col", "flex", "flex-direction: column"),
        c("flex-col-reverse", "flex", "flex-direction: column-reverse"),
        c("flex-wrap", "flex", "flex-wrap: wrap"),
        c("flex-nowrap", "flex", "flex-wrap: nowrap"),
        c("flex-wrap-reverse", "flex", "flex-wrap: wrap-reverse"),
        c("flex-1", "flex", "flex: 1 1 0%"),
        c("flex-auto", "flex", "flex: 1 1 auto"),
        c("flex-initial", "flex", "flex: 0 1 auto"),
        c("flex-none", "flex", "flex: none"),
        c("grow", "flex", "flex-grow: 1"),
        c("grow-0", "flex", "flex-grow: 0"),
        c("shrink", "flex", "flex-shrink: 1"),
        c("shrink-0", "flex", "flex-shrink: 0"),
        c("order-first", "flex", "order: -9999"),
        c("order-last", "flex", "order: 9999"),

        // ---- Flexbox 对齐 ----
        c("justify-start", "flex", "justify-content: flex-start"),
        c("justify-end", "flex", "justify-content: flex-end"),
        c("justify-center", "flex", "justify-content: center"),
        c("justify-between", "flex", "justify-content: space-between"),
        c("justify-around", "flex", "justify-content: space-around"),
        c("justify-evenly", "flex", "justify-content: space-evenly"),
        c("items-start", "flex", "align-items: flex-start"),
        c("items-end", "flex", "align-items: flex-end"),
        c("items-center", "flex", "align-items: center"),
        c("items-baseline", "flex", "align-items: baseline"),
        c("items-stretch", "flex", "align-items: stretch"),
        c("self-start", "flex", "align-self: flex-start"),
        c("self-end", "flex", "align-self: flex-end"),
        c("self-center", "flex", "align-self: center"),
        c("self-stretch", "flex", "align-self: stretch"),
        c("content-start", "flex", "align-content: flex-start"),
        c("content-end", "flex", "align-content: flex-end"),
        c("content-center", "flex", "align-content: center"),
        c("content-between", "flex", "align-content: space-between"),
        c("content-around", "flex", "align-content: space-around"),

        // ---- Grid ----
        c("grid-cols-1", "grid", "grid-template-columns: repeat(1, minmax(0, 1fr))"),
        c("grid-cols-2", "grid", "grid-template-columns: repeat(2, minmax(0, 1fr))"),
        c("grid-cols-3", "grid", "grid-template-columns: repeat(3, minmax(0, 1fr))"),
        c("grid-cols-4", "grid", "grid-template-columns: repeat(4, minmax(0, 1fr))"),
        c("grid-cols-6", "grid", "grid-template-columns: repeat(6, minmax(0, 1fr))"),
        c("grid-cols-12", "grid", "grid-template-columns: repeat(12, minmax(0, 1fr))"),
        c("grid-rows-1", "grid", "grid-template-rows: repeat(1, minmax(0, 1fr))"),
        c("grid-rows-2", "grid", "grid-template-rows: repeat(2, minmax(0, 1fr))"),
        c("grid-flow-row", "grid", "grid-auto-flow: row"),
        c("grid-flow-col", "grid", "grid-auto-flow: column"),
        c("col-span-1", "grid", "grid-column: span 1 / span 1"),
        c("col-span-2", "grid", "grid-column: span 2 / span 2"),
        c("col-span-full", "grid", "grid-column: 1 / -1"),
        c("row-span-1", "grid", "grid-row: span 1 / span 1"),
        c("row-span-2", "grid", "grid-row: span 2 / span 2"),
        c("gap-0", "grid", "gap: 0px"),
        c("gap-1", "grid", "gap: 0.25rem"),
        c("gap-2", "grid", "gap: 0.5rem"),
        c("gap-4", "grid", "gap: 1rem"),
        c("gap-x-4", "grid", "column-gap: 1rem"),
        c("gap-y-4", "grid", "row-gap: 1rem"),

        // ---- 间距 ----
        c("p-0", "spacing", "padding: 0px"),
        c("p-1", "spacing", "padding: 0.25rem"),
        c("p-2", "spacing", "padding: 0.5rem"),
        c("p-3", "spacing", "padding: 0.75rem"),
        c("p-4", "spacing", "padding: 1rem"),
        c("px-4", "spacing", "padding-left: 1rem; padding-right: 1rem"),
        c("py-4", "spacing", "padding-top: 1rem; padding-bottom: 1rem"),
        c("pt-4", "spacing", "padding-top: 1rem"),
        c("pr-4", "spacing", "padding-right: 1rem"),
        c("pb-4", "spacing", "padding-bottom: 1rem"),
        c("pl-4", "spacing", "padding-left: 1rem"),
        c("m-0", "spacing", "margin: 0px"),
        c("m-1", "spacing", "margin: 0.25rem"),
        c("m-2", "spacing", "margin: 0.5rem"),
        c("m-4", "spacing", "margin: 1rem"),
        c("mx-auto", "spacing", "margin-left: auto; margin-right: auto"),
        c("mx-4", "spacing", "margin-left: 1rem; margin-right: 1rem"),
        c("my-4", "spacing", "margin-top: 1rem; margin-bottom: 1rem"),
        c("mt-4", "spacing", "margin-top: 1rem"),
        c("-mt-4", "spacing", "margin-top: -1rem"),
        c("space-y-4", "spacing", "margin-top: 1rem"),
        c("space-x-4", "spacing", "margin-left: 1rem"),

        // ---- 尺寸 ----
        c("w-0", "size", "width: 0px"),
        c("w-1", "size", "width: 0.25rem"),
        c("w-4", "size", "width: 1rem"),
        c("w-8", "size", "width: 2rem"),
        c("w-16", "size", "width: 4rem"),
        c("w-1/2", "size", "width: 50%"),
        c("w-1/3", "size", "width: 33.333333%"),
        c("w-2/3", "size", "width: 66.666667%"),
        c("w-full", "size", "width: 100%"),
        c("w-screen", "size", "width: 100vw"),
        c("w-auto", "size", "width: auto"),
        c("h-0", "size", "height: 0px"),
        c("h-4", "size", "height: 1rem"),
        c("h-8", "size", "height: 2rem"),
        c("h-16", "size", "height: 4rem"),
        c("h-full", "size", "height: 100%"),
        c("h-screen", "size", "height: 100vh"),
        c("h-auto", "size", "height: auto"),
        c("min-h-full", "size", "min-height: 100%"),
        c("min-w-full", "size", "min-width: 100%"),
        c("max-w-full", "size", "max-width: 100%"),
        c("max-w-sm", "size", "max-width: 24rem"),
        c("max-w-md", "size", "max-width: 28rem"),
        c("max-w-lg", "size", "max-width: 32rem"),
        c("max-w-xl", "size", "max-width: 36rem"),
        c("max-w-2xl", "size", "max-width: 42rem"),

        // ---- 排版 ----
        c("text-xs", "typography", "font-size: 0.75rem; line-height: 1rem"),
        c("text-sm", "typography", "font-size: 0.875rem; line-height: 1.25rem"),
        c("text-base", "typography", "font-size: 1rem; line-height: 1.5rem"),
        c("text-lg", "typography", "font-size: 1.125rem; line-height: 1.75rem"),
        c("text-xl", "typography", "font-size: 1.25rem; line-height: 1.75rem"),
        c("text-2xl", "typography", "font-size: 1.5rem; line-height: 2rem"),
        c("text-left", "typography", "text-align: left"),
        c("text-center", "typography", "text-align: center"),
        c("text-right", "typography", "text-align: right"),
        c("text-justify", "typography", "text-align: justify"),
        c("font-thin", "typography", "font-weight: 100"),
        c("font-light", "typography", "font-weight: 300"),
        c("font-normal", "typography", "font-weight: 400"),
        c("font-medium", "typography", "font-weight: 500"),
        c("font-semibold", "typography", "font-weight: 600"),
        c("font-bold", "typography", "font-weight: 700"),
        c("font-extrabold", "typography", "font-weight: 800"),
        c("font-black", "typography", "font-weight: 900"),
        c("italic", "typography", "font-style: italic"),
        c("not-italic", "typography", "font-style: normal"),
        c("underline", "typography", "text-decoration-line: underline"),
        c("line-through", "typography", "text-decoration-line: line-through"),
        c("no-underline", "typography", "text-decoration-line: none"),
        c("uppercase", "typography", "text-transform: uppercase"),
        c("lowercase", "typography", "text-transform: lowercase"),
        c("capitalize", "typography", "text-transform: capitalize"),
        c("truncate", "typography", "overflow: hidden; text-overflow: ellipsis; white-space: nowrap"),
        c("whitespace-nowrap", "typography", "white-space: nowrap"),
        c("break-words", "typography", "overflow-wrap: break-word"),
        c("leading-none", "typography", "line-height: 1"),
        c("leading-tight", "typography", "line-height: 1.25"),
        c("leading-snug", "typography", "line-height: 1.375"),
        c("leading-normal", "typography", "line-height: 1.5"),
        c("leading-loose", "typography", "line-height: 2"),

        // ---- 文本颜色 ----
        c("text-white", "color", "color: #fff"),
        c("text-black", "color", "color: #000"),
        c("text-gray-500", "color", "color: #6b7280"),
        c("text-gray-700", "color", "color: #374151"),
        c("text-red-500", "color", "color: #ef4444"),
        c("text-red-600", "color", "color: #dc2626"),
        c("text-blue-500", "color", "color: #3b82f6"),
        c("text-blue-600", "color", "color: #2563eb"),
        c("text-green-500", "color", "color: #22c55e"),
        c("text-green-600", "color", "color: #16a34a"),
        c("text-yellow-500", "color", "color: #eab308"),
        c("text-purple-500", "color", "color: #a855f7"),
        c("text-pink-500", "color", "color: #ec4899"),
        c("text-indigo-500", "color", "color: #6366f1"),

        // ---- 背景 ----
        c("bg-white", "background", "background-color: #fff"),
        c("bg-black", "background", "background-color: #000"),
        c("bg-transparent", "background", "background-color: transparent"),
        c("bg-gray-500", "background", "background-color: #6b7280"),
        c("bg-gray-100", "background", "background-color: #f3f4f6"),
        c("bg-red-500", "background", "background-color: #ef4444"),
        c("bg-blue-500", "background", "background-color: #3b82f6"),
        c("bg-green-500", "background", "background-color: #22c55e"),
        c("bg-yellow-400", "background", "background-color: #facc15"),
        c("bg-purple-500", "background", "background-color: #a855f7"),
        c("bg-pink-500", "background", "background-color: #ec4899"),
        c("bg-indigo-500", "background", "background-color: #6366f1"),
        c("bg-cover", "background", "background-size: cover"),
        c("bg-contain", "background", "background-size: contain"),
        c("bg-center", "background", "background-position: center"),
        c("bg-no-repeat", "background", "background-repeat: no-repeat"),
        c("bg-gradient-to-r", "background", "background-image: linear-gradient(to right, var(--tw-gradient-stops))"),
        c("bg-gradient-to-b", "background", "background-image: linear-gradient(to bottom, var(--tw-gradient-stops))"),

        // ---- 边框 ----
        c("border", "border", "border-width: 1px"),
        c("border-0", "border", "border-width: 0px"),
        c("border-2", "border", "border-width: 2px"),
        c("border-4", "border", "border-width: 4px"),
        c("border-t", "border", "border-top-width: 1px"),
        c("border-b", "border", "border-bottom-width: 1px"),
        c("border-l", "border", "border-left-width: 1px"),
        c("border-r", "border", "border-right-width: 1px"),
        c("border-solid", "border", "border-style: solid"),
        c("border-dashed", "border", "border-style: dashed"),
        c("border-dotted", "border", "border-style: dotted"),
        c("border-none", "border", "border-style: none"),
        c("border-gray-500", "border", "border-color: #6b7280"),
        c("border-red-500", "border", "border-color: #ef4444"),
        c("border-blue-500", "border", "border-color: #3b82f6"),
        c("rounded-none", "border", "border-radius: 0px"),
        c("rounded-sm", "border", "border-radius: 0.125rem"),
        c("rounded", "border", "border-radius: 0.25rem"),
        c("rounded-md", "border", "border-radius: 0.375rem"),
        c("rounded-lg", "border", "border-radius: 0.5rem"),
        c("rounded-xl", "border", "border-radius: 0.75rem"),
        c("rounded-2xl", "border", "border-radius: 1rem"),
        c("rounded-full", "border", "border-radius: 9999px"),
        c("rounded-t", "border", "border-top-left-radius: 0.25rem; border-top-right-radius: 0.25rem"),
        c("rounded-b", "border", "border-bottom-left-radius: 0.25rem; border-bottom-right-radius: 0.25rem"),

        // ---- 效果 ----
        c("shadow-sm", "effect", "box-shadow: 0 1px 2px 0 rgb(0 0 0 / 0.05)"),
        c("shadow", "effect", "box-shadow: 0 1px 3px 0 rgb(0 0 0 / 0.1), 0 1px 2px -1px rgb(0 0 0 / 0.1)"),
        c("shadow-md", "effect", "box-shadow: 0 4px 6px -1px rgb(0 0 0 / 0.1), 0 2px 4px -2px rgb(0 0 0 / 0.1)"),
        c("shadow-lg", "effect", "box-shadow: 0 10px 15px -3px rgb(0 0 0 / 0.1), 0 4px 6px -4px rgb(0 0 0 / 0.1)"),
        c("shadow-xl", "effect", "box-shadow: 0 20px 25px -5px rgb(0 0 0 / 0.1), 0 8px 10px -6px rgb(0 0 0 / 0.1)"),
        c("shadow-2xl", "effect", "box-shadow: 0 25px 50px -12px rgb(0 0 0 / 0.25)"),
        c("shadow-none", "effect", "box-shadow: 0 0 #0000"),
        c("opacity-0", "effect", "opacity: 0"),
        c("opacity-50", "effect", "opacity: 0.5"),
        c("opacity-100", "effect", "opacity: 1"),

        // ---- 过渡 & 动画 ----
        c("transition", "transition", "transition-property: color, background-color, border-color, text-decoration-color, fill, stroke, opacity, box-shadow, transform, filter, -webkit-backdrop-filter; transition-timing-function: cubic-bezier(0.4, 0, 0.2, 1); transition-duration: 150ms"),
        c("transition-colors", "transition", "transition-property: color, background-color, border-color, text-decoration-color, fill, stroke; transition-timing-function: cubic-bezier(0.4, 0, 0.2, 1); transition-duration: 150ms"),
        c("transition-opacity", "transition", "transition-property: opacity; transition-timing-function: cubic-bezier(0.4, 0, 0.2, 1); transition-duration: 150ms"),
        c("transition-shadow", "transition", "transition-property: box-shadow; transition-timing-function: cubic-bezier(0.4, 0, 0.2, 1); transition-duration: 150ms"),
        c("transition-transform", "transition", "transition-property: transform; transition-timing-function: cubic-bezier(0.4, 0, 0.2, 1); transition-duration: 150ms"),
        c("duration-75", "transition", "transition-duration: 75ms"),
        c("duration-150", "transition", "transition-duration: 150ms"),
        c("duration-300", "transition", "transition-duration: 300ms"),
        c("duration-500", "transition", "transition-duration: 500ms"),
        c("ease-linear", "transition", "transition-timing-function: linear"),
        c("ease-in", "transition", "transition-timing-function: cubic-bezier(0.4, 0, 1, 1)"),
        c("ease-out", "transition", "transition-timing-function: cubic-bezier(0, 0, 0.2, 1)"),
        c("ease-in-out", "transition", "transition-timing-function: cubic-bezier(0.4, 0, 0.2, 1)"),

        // ---- 变换 ----
        c("scale-0", "transform", "--tw-scale-x: 0; --tw-scale-y: 0; transform: translate(var(--tw-translate-x), var(--tw-translate-y)) rotate(var(--tw-rotate)) skewX(var(--tw-skew-x)) skewY(var(--tw-skew-y)) scaleX(var(--tw-scale-x)) scaleY(var(--tw-scale-y))"),
        c("scale-50", "transform", "--tw-scale-x: .5; --tw-scale-y: .5; transform: ..."),
        c("scale-100", "transform", "--tw-scale-x: 1; --tw-scale-y: 1; transform: ..."),
        c("scale-110", "transform", "--tw-scale-x: 1.1; --tw-scale-y: 1.1; transform: ..."),
        c("rotate-0", "transform", "--tw-rotate: 0deg; transform: ..."),
        c("rotate-45", "transform", "--tw-rotate: 45deg; transform: ..."),
        c("rotate-90", "transform", "--tw-rotate: 90deg; transform: ..."),
        c("rotate-180", "transform", "--tw-rotate: 180deg; transform: ..."),
        c("-rotate-90", "transform", "--tw-rotate: -90deg; transform: ..."),

        // ---- 溢出 / 可见性 ----
        c("overflow-hidden", "layout", "overflow: hidden"),
        c("overflow-scroll", "layout", "overflow: scroll"),
        c("overflow-auto", "layout", "overflow: auto"),
        c("overflow-visible", "layout", "overflow: visible"),
        c("overflow-x-hidden", "layout", "overflow-x: hidden"),
        c("overflow-y-auto", "layout", "overflow-y: auto"),
        c("visible", "layout", "visibility: visible"),
        c("invisible", "layout", "visibility: hidden"),

        // ---- 光标 / 交互 ----
        c("cursor-pointer", "interaction", "cursor: pointer"),
        c("cursor-move", "interaction", "cursor: move"),
        c("cursor-not-allowed", "interaction", "cursor: not-allowed"),
        c("cursor-default", "interaction", "cursor: default"),
        c("select-none", "interaction", "user-select: none"),
        c("pointer-events-none", "interaction", "pointer-events: none"),
        c("pointer-events-auto", "interaction", "pointer-events: auto"),
    )
}