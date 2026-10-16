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

# 首次运行（或者换了 IMGUP_BUILD_CACHE）时缓存还没有，直接自己引导一次。
# 以前这里是「报错让人先跑 build.sh」—— 结果 CI 里全新检出必然失败：
# Release 工作流的「跑测试」那一步就是这么挂掉的。
if [ ! -f "$CACHE_ROOT/classpath.txt" ]; then
  echo "==> 还没有依赖缓存，先跑一次 build.sh 准备（首次需要联网下载）"
  bash "$HERE/build.sh"
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

# 测试不许往工作区里拉屎。
#
# 起因：某次新加的测试把相对路径 "__messages__" 当成输出目录传给了
# createDirectories，测试就在仓库根目录建了个目录，紧接着 git add -A
# 把它提交进了公开仓库。这类污染光看测试输出是发现不了的。
#
# 做法：跑之前记一份工作区快照，跑完再记一份，多出来的条目就报错。
# 只统计「文件系统里真实存在的路径」，避免 git 状态里那些跟本次无关的差异。
# LC_ALL=C：comm 按字节序比较，而 sort 默认跟着 locale 走，两边不一致会刷
# "input is not in sorted order" 的噪音警告。
snapshot() {
  LC_ALL=C find . -path ./.git -prune -o -path ./target -prune -o \
      -path ./.build-cache -prune -o -print 2>/dev/null | LC_ALL=C sort
}
BEFORE="$(snapshot)"

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

AFTER="$(snapshot)"
LEFTOVER="$(LC_ALL=C comm -13 <(printf '%s\n' "$BEFORE") <(printf '%s\n' "$AFTER") || true)"
if [ -n "$LEFTOVER" ]; then
  echo
  echo "测试往工作区里留下了东西（不该提交，请改成用临时目录）：" >&2
  printf '%s\n' "$LEFTOVER" | sed 's/^/  /' >&2
  status=1
fi

echo
if [ "$status" -eq 0 ]; then
  echo "全部测试通过"
else
  echo "有测试失败" >&2
fi
exit "$status"
