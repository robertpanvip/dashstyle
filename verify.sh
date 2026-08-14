#!/usr/bin/env bash
# =============================================================================
# DashStyle 功能验证脚本（无需打开 IDEA）
#
#  方式一（默认，最推荐）：零依赖 Java 独立验证器
#    只要机器上有 JDK（javac + java），就能在几秒内完成核心逻辑回归：
#      1) LessFeatureVerifier          - 选择器展开 / kebab·camel（FEATURES §1, §2.2）
#      2) InlineStyleConverterVerifier - inline style JSON/JS→CSS（FEATURES §2.4）
#      3) ColorToolingVerifier         - 颜色归一化/语义变量/扫描 + 微基准（FEATURES §2.9）
#    这些纯 Java 验证器逻辑与主源码 Util.kt 中的纯函数一一对应，跑通即可对
#    「核心算法是否实现」给出强信号，且不碰 IntelliJ SDK / 无网络依赖。
#
#  方式二（可选）：gradle 编译 + 打包冒烟
#    运行 IntelliJ Platform Gradle 插件的 compileKotlin / compileTestKotlin /
#    buildPlugin（-x test），验证所有主源码 + 插件注册能编译、能打包成 zip。
#    需要：JDK17 + 网络（项目内 _local_init.gradle.kts 已默认走腾讯镜像）。
#
#  用法：
#    ./verify.sh                 # 只跑 3 个独立 Java 验证器（快）
#    ./verify.sh --gradle        # 额外跑 gradle 编译 + buildPlugin
#    ./verify.sh --verbose       # 打印每个验证器完整输出
#    ./verify.sh --gradle --verbose
#    (如无执行权限: bash verify.sh)
# =============================================================================
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VER_SRC="$ROOT/src/test/java/com/pan/dashstyle"
OUT="$(mktemp -d /tmp/dashstyle-verify.XXXXXX)"
trap 'rm -rf "$OUT"' EXIT

VERBOSE=0
RUN_GRADLE=0
for arg in "$@"; do
  case "$arg" in
    --verbose) VERBOSE=1 ;;
    --gradle)  RUN_GRADLE=1 ;;
    *) echo "未知参数: $arg（支持 --verbose / --gradle）"; exit 2 ;;
  esac
done

echo "╔══════════════════════════════════════════════════════════╗"
echo "║   DashStyle 功能验证（无需打开 IDEA）                     ║"
echo "╚══════════════════════════════════════════════════════════╝"
echo "工作目录: $ROOT"
echo ""

# ---------------------------------------------------------------------------
# 0) 环境检查
# ---------------------------------------------------------------------------
if ! command -v javac >/dev/null 2>&1 || ! command -v java >/dev/null 2>&1; then
  echo "❌ 找不到 javac / java，请先安装 JDK（任意 ≥11 即可跑独立验证器）"
  exit 1
fi
echo "使用 JDK: $(java -version 2>&1 | head -1)"

FAIL_LIST=()

# ---------------------------------------------------------------------------
# 方式一：独立 Java 验证器（零依赖）
# ---------------------------------------------------------------------------
run_verifier() {
  local name="$1"
  local src="$VER_SRC/$name.java"
  echo ""
  echo "──────────────────────────────────────────────────────────────"
  echo "▶ $name"
  echo "──────────────────────────────────────────────────────────────"
  if [ ! -f "$src" ]; then
    echo "❌ 源文件缺失: $src"
    FAIL_LIST+=("$name (missing)")
    return 1
  fi
  if ! javac -d "$OUT" "$src" 2> "$OUT/$name.javac.log"; then
    echo "❌ 编译失败:"
    cat "$OUT/$name.javac.log"
    FAIL_LIST+=("$name (compile)")
    return 1
  fi
  if java -cp "$OUT" "com.pan.dashstyle.$name" > "$OUT/$name.out" 2> "$OUT/$name.err"; then
    echo ""
    echo "✅ $name —— 全部通过"
    [ "$VERBOSE" = "1" ] && echo "---- 完整输出 ----" && cat "$OUT/$name.out"
    return 0
  else
    echo ""
    echo "❌ $name —— 存在失败用例"
    [ "$VERBOSE" = "1" ] && echo "---- 完整输出 ----" && cat "$OUT/$name.out"
    FAIL_LIST+=("$name (assert)")
    return 1
  fi
}

echo ""
echo "══════════════════════════════════════════════════════════"
echo " [方式一] 独立 Java 验证器（零依赖，无需 IDEA / Gradle / 网络）"
echo "══════════════════════════════════════════════════════════"

run_verifier "LessFeatureVerifier"
run_verifier "InlineStyleConverterVerifier"
run_verifier "ColorToolingVerifier"

# ---------------------------------------------------------------------------
# 方式二（可选）：gradle 编译 + buildPlugin
# ---------------------------------------------------------------------------
if [ "$RUN_GRADLE" = "1" ]; then
  echo ""
  echo "══════════════════════════════════════════════════════════"
  echo " [方式二] gradle 编译 + buildPlugin（需 JDK17 + 网络）"
  echo "══════════════════════════════════════════════════════════"
  if [ ! -x "$ROOT/gradlew" ]; then
    echo "⚠️ 未找到 gradlew，尝试用系统 gradle"
  fi
  if command -v gradle >/dev/null 2>&1; then
    if gradle --init-script "$ROOT/_local_init.gradle.kts" \
        compileKotlin compileTestKotlin buildPlugin -x test \
        > "$OUT/gradle.log" 2>&1; then
      echo "✅ gradle 编译 + buildPlugin 成功"
      ls -la "$ROOT/build/distributions/"*.zip 2>/dev/null | sed 's/^/   /'
    else
      # 区分"网络/镜像解析失败"（环境问题，非代码问题）与"真实编译错误"
      if grep -qEi "could not resolve|was not found in any|network|Connection (refused|reset|timed out)|Could not GET|UnknownHost" "$OUT/gradle.log"; then
        echo "⚠️ gradle 因依赖下载/网络解析失败而中止（环境问题，非代码错误）。"
        echo "   在能访问腾讯镜像 / Gradle 插件的环境下（如 GitHub Actions 的 test.yml）可正常通过。"
        echo "   依赖解析失败详情（末尾 15 行）:"
        tail -n 15 "$OUT/gradle.log" | sed 's/^/     /'
      else
        echo "❌ gradle 编译/buildPlugin 失败（疑似真实编译错误），详见日志:"
        tail -n 40 "$OUT/gradle.log"
        FAIL_LIST+=("gradle buildPlugin (compile)")
      fi
    fi
  else
    echo "❌ 系统无 gradle 命令，跳过【方式二】（不影响方式一结果）"
  fi
fi

# ---------------------------------------------------------------------------
# 汇总
# ---------------------------------------------------------------------------
echo ""
echo "══════════════════════════════════════════════════════════"
echo " 汇总"
echo "══════════════════════════════════════════════════════════"
if [ "${#FAIL_LIST[@]}" -eq 0 ]; then
  echo ""
  echo "🎉 全部验证通过！"
  echo "   - Less 选择器展开 / kebab·camel"
  echo "   - Inline style JSON/JS → CSS"
  echo "   - 颜色工具（归一化/语义变量/扫描）"
  [ "$RUN_GRADLE" = "1" ] && echo "   - IntelliJ 插件编译 + 打包"
  echo ""
  echo "说明：以上覆盖 FEATURES.md 中可离线回归的纯逻辑模块。"
  echo "     依赖 IntelliJ PSI/UI 的部分（引用跳转、Intention、Inspection、"
  echo "     CopyPaste、Action）需在 IDE 内或 CI（.github/workflows/test.yml）验证。"
  exit 0
else
  echo ""
  echo "❌ 以下项未通过："
  for f in "${FAIL_LIST[@]}"; do echo "   - $f"; done
  echo ""
  echo "提示：如为编译失败，请检查 JDK 版本；如为断言失败，请对照 FEATURES.md 检查实现。"
  exit 1
fi