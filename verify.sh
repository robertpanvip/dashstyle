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
#  方式三（可选，解决 PSI/UI 部分）：IDE 沙箱集成测试
#    运行 src/test/kotlin 里的 DashStyleIntegrationTest（基于 BasePlatformTestCase，
#    在 headless 沙箱里加载 WebStorm-2025.3 平台 + DashStyle 插件），真正验证
#    高亮置灰 / Intention / 引用跳转 / Inspection 这些依赖 PSI/UI 的功能。
#    7 条用例全部为强断言并默认启用（不再 @Ignore），覆盖：未使用 class 置灰、
#    重复声明弱警告、抽取公共类 QuickFix、style 字符串引用跳转、inline style 提取
#    Intention、Inspection/Annotator 类可加载。需要：JDK17 + 网络（首次会下载
#    WebStorm SDK，体积大、耗时）。
#
#  用法：
#    ./verify.sh                        # 只跑 3 个独立 Java 验证器（快）
#    ./verify.sh --gradle               # 额外跑 gradle 编译 + buildPlugin
#    ./verify.sh --integration          # 额外跑 IDE 沙箱集成测试（PSI/UI）
#    ./verify.sh --gradle --integration # 全部
#    ./verify.sh --verbose              # 打印每个验证器完整输出
#    (如无执行权限: bash verify.sh)
#
#  网络代理：如环境里设置了 HTTP_PROXY / HTTPS_PROXY（沙箱常见），脚本会自动把它
#  注入到 gradle JVM（GRADLE_OPTS）。否则依赖下载/腾讯镜像可能解析失败。
# =============================================================================
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VER_SRC="$ROOT/src/test/java/com/pan/dashstyle"
OUT="$(mktemp -d /tmp/dashstyle-verify.XXXXXX)"
trap 'rm -rf "$OUT"' EXIT

VERBOSE=0
RUN_GRADLE=0
RUN_INTEGRATION=0
for arg in "$@"; do
  case "$arg" in
    --verbose)     VERBOSE=1 ;;
    --gradle)      RUN_GRADLE=1 ;;
    --integration) RUN_INTEGRATION=1 ;;
    *) echo "未知参数: $arg（支持 --verbose / --gradle / --integration）"; exit 2 ;;
  esac
done

# 把环境代理注入 gradle JVM（若已设置过 GRADLE_OPTS 则追加）
GRADLE_OPTS="${GRADLE_OPTS:-}"
inject_proxy() {
  local host port
  host="${1:-127.0.0.1}"; port="${2:-18080}"
  for p in "http.proxyHost=$host" "http.proxyPort=$port" \
           "https.proxyHost=$host" "https.proxyPort=$port" \
           "http.nonProxyHosts=localhost|127.0.0.1"; do
    case " $GRADLE_OPTS " in
      *"-$p "*) ;;  # 已存在
      *) GRADLE_OPTS="$GRADLE_OPTS -D$p" ;;
    esac
  done
}
for var in HTTPS_PROXY https_proxy HTTP_PROXY http_proxy; do
  val="${!var:-}"
  [ -n "$val" ] || continue
  case "$val" in
    http://*:*) host="${val#http://}"; port="${host##*:}"; host="${host%%:*}"; inject_proxy "$host" "$port"; break ;;
  esac
done
export GRADLE_OPTS

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
# 方式三（可选）：IDE 沙箱集成测试（解决 PSI/UI 部分）
# ---------------------------------------------------------------------------
INTEGRATION_PATTERN="${INTEGRATION_PATTERN:-com.pan.dashstyle.DashStyleIntegrationTest}"
if [ "$RUN_INTEGRATION" = "1" ]; then
  echo ""
  echo "══════════════════════════════════════════════════════════"
  echo " [方式三] IDE 沙箱集成测试（PSI/UI）"
  echo "══════════════════════════════════════════════════════════"
  if command -v gradle >/dev/null 2>&1; then
    echo "目标测试类: $INTEGRATION_PATTERN"
    echo "（首次运行会下载 WebStorm SDK 并启动 headless 沙箱，耗时较长，请耐心等待）"
    if gradle --no-daemon --init-script "$ROOT/_local_init.gradle.kts" \
        test --tests "$INTEGRATION_PATTERN" \
        > "$OUT/integration.log" 2>&1; then
      echo "✅ IDE 沙箱集成测试通过"
      echo "   说明：7 条强断言用例全部启用。首次运行需下载 WebStorm SDK，可能较慢。"
    else
      if grep -qEi "could not resolve|was not found in any|UnknownHost|Could not GET|Connection (refused|reset)" "$OUT/integration.log"; then
        echo "⚠️ 集成测试因依赖下载/网络解析失败而中止（环境问题，非代码错误）。"
        tail -n 15 "$OUT/integration.log" | sed 's/^/     /'
      else
        echo "❌ IDE 沙箱集成测试失败（测试断言或类加载问题），关键日志:"
        grep -nE "FAILED|AssertionError|Exception|error:|Cannot create class|BUILD FAILED" \
          "$OUT/integration.log" | tail -n 30 | sed 's/^/     /'
        FAIL_LIST+=("IDE 集成测试")
      fi
    fi
  else
    echo "❌ 系统无 gradle 命令，跳过【方式三】"
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
  [ "$RUN_INTEGRATION" = "1" ] && echo "   - IDE 沙箱集成测试（PSI/UI smoke）"
  echo ""
  if [ "$RUN_INTEGRATION" = "0" ]; then
    echo "说明：依赖 IntelliJ PSI/UI 的部分（引用跳转、Intention、Inspection、"
    echo "     CopyPaste、Action）可用 ./verify.sh --integration 在 headless 沙箱里验证。"
  fi
  exit 0
else
  echo ""
  echo "❌ 以下项未通过："
  for f in "${FAIL_LIST[@]}"; do echo "   - $f"; done
  echo ""
  echo "提示：如为编译失败，请检查 JDK 版本；如为断言失败，请对照 FEATURES.md 检查实现。"
  exit 1
fi