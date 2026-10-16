#!/usr/bin/env bash
# 编译并打包 ImgUp 插件。
#
# 不依赖 Maven/Gradle —— 只需要 JDK 的 javac / jar，加上 Paper API 及其依赖。
# 依赖由 fetch-deps.py 自动下载到 .build-cache/deps 并缓存，之后离线也能编译。
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$HERE"

API_GROUP="io.papermc.paper"
API_ARTIFACT="paper-api"
API_VERSION="26.3.build.194-stable"
CACHE_ROOT="${IMGUP_BUILD_CACHE:-$HERE/.build-cache}"
DEPS_DIR="$CACHE_ROOT/deps"
OUT_DIR="$HERE/target"
JAR_NAME="ImgUp-1.0.0.jar"
JAVAC_RELEASE="${IMGUP_JAVA_RELEASE:-21}"

need() { command -v "$1" >/dev/null 2>&1 || { echo "缺少 $1，请先安装" >&2; exit 1; }; }
need javac
need jar
need python3

echo "==> 准备依赖（Paper API $API_VERSION）"
mkdir -p "$CACHE_ROOT"
python3 "$HERE/fetch-deps.py" "$DEPS_DIR" "$API_GROUP" "$API_ARTIFACT" "$API_VERSION" \
  > "$CACHE_ROOT/classpath.txt"

# 组装 classpath：跳过名字里还带未展开 ${...} 占位符的条目
CLASSPATH=""
COUNT=0
while IFS= read -r jar; do
  [ -f "$jar" ] || continue
  case "$jar" in *'${'*) continue ;; esac
  CLASSPATH="${CLASSPATH:+$CLASSPATH:}$jar"
  COUNT=$((COUNT + 1))
done < <(tr ':' '\n' < "$CACHE_ROOT/classpath.txt" | sort -u)

if [ "$COUNT" -lt 5 ]; then
  echo "依赖数量异常（只有 $COUNT 个），检查网络或 $DEPS_DIR" >&2
  exit 1
fi
echo "    共 $COUNT 个依赖 jar"

rm -rf "$OUT_DIR"
mkdir -p "$OUT_DIR/classes"

echo "==> 编译（release $JAVAC_RELEASE）"
mapfile -t SOURCES < <(find src/main/java -name '*.java' | sort)
if [ "${#SOURCES[@]}" -eq 0 ]; then
  echo "没有找到任何 Java 源文件" >&2
  exit 1
fi
echo "    源文件 ${#SOURCES[@]} 个"

# 刻意不用 -Werror：ChatColor / getDescription 在本版本已标记过时，
# 但它们仍是最兼容的写法，把警告升级成错误只会让构建变脆。
javac -encoding UTF-8 \
      --release "$JAVAC_RELEASE" \
      -cp "$CLASSPATH" \
      -d "$OUT_DIR/classes" \
      "${SOURCES[@]}" 2> "$OUT_DIR/javac.log" || {
        echo "编译失败：" >&2
        cat "$OUT_DIR/javac.log" >&2
        exit 1
      }
if grep -qE "错误|error:" "$OUT_DIR/javac.log" 2>/dev/null; then
  echo "编译有问题：" >&2; cat "$OUT_DIR/javac.log" >&2; exit 1
fi

echo "==> 打资源进 jar"
cp -r src/main/resources/. "$OUT_DIR/classes/"

echo "==> 打包 $JAR_NAME"
jar --create --file "$OUT_DIR/$JAR_NAME" -C "$OUT_DIR/classes" .

echo "==> 校验产物"
python3 - "$OUT_DIR/$JAR_NAME" <<'PY'
import sys, zipfile
jar = sys.argv[1]
with zipfile.ZipFile(jar) as zf:
    names = zf.namelist()
    for required in ("plugin.yml", "config.yml",
                     "com/huhumc/imgup/ImgUpPlugin.class",
                     "com/huhumc/imgup/ImgUpCommand.class"):
        if required not in names:
            sys.exit("产物里缺少 " + required)
    with zf.open("plugin.yml") as fh:
        head = fh.read(400).decode("utf-8", "replace")
    for key in ("name:", "version:", "main:", "api-version:"):
        if key not in head:
            sys.exit("plugin.yml 缺少 " + key)
print("    plugin.yml / config.yml / 主类 都在，plugin.yml 头部正常")
PY

ls -la "$OUT_DIR/$JAR_NAME"
echo "完成：$OUT_DIR/$JAR_NAME"
