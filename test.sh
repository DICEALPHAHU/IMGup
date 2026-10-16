#!/usr/bin/env bash
# 跑 ImgUp 的全部测试。
#
#   bash test.sh
#
# 三套测试：
#   CoreSelfTest       纯逻辑（multipart 解析、图片处理、文件名、会话）
#   HttpSelfTest       真起 HTTP 服务器，走完整上传流程
#   ConfigElementsTest 验证页面元素确实来自 messages/*.yml（改配置就生效）
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$HERE"

CACHE_ROOT="${IMGUP_BUILD_CACHE:-$HERE/.build-cache}"
OUT="$HERE/target/test-classes"
RES="$HERE/src/main/resources"

need() { command -v "$1" >/dev/null 2>&1 || { echo "缺少 $1" >&2; exit 1; }; }
need javac
need java

if [ ! -f "$CACHE_ROOT/classpath.txt" ]; then
  echo "还没有依赖缓存，先跑一次 build.sh" >&2
  exit 1
fi

CP=""
while IFS= read -r jar; do
  [ -f "$jar" ] || continue
  case "$jar" in *'${'*) continue ;; esac
  CP="${CP:+$CP:}$jar"
done < <(tr ':' '\n' < "$CACHE_ROOT/classpath.txt" | sort -u)

echo "==> 编译（主源码 + 测试）"
rm -rf "$OUT"
mkdir -p "$OUT"
mapfile -t MAIN < <(find src/main/java -name '*.java' | sort)
mapfile -t TEST < <(find src/test/java -name '*.java' | sort)
javac -encoding UTF-8 --release 21 -cp "$CP" -d "$OUT" "${MAIN[@]}" "${TEST[@]}" 2>&1 \
  | grep -v "^注:" || true
if [ ! -f "$OUT/CoreSelfTest.class" ]; then
  echo "编译失败" >&2
  exit 1
fi

RUN=(java -Dfile.encoding=UTF-8 -cp "$OUT:$CP:$RES")

status=0
for cls in CoreSelfTest HttpSelfTest ConfigElementsTest; do
  echo
  echo "================ $cls ================"
  if "${RUN[@]}" "$cls"; then
    :
  else
    status=1
  fi
done

echo
if [ "$status" -eq 0 ]; then
  echo "全部测试通过"
else
  echo "有测试失败" >&2
fi
exit "$status"
