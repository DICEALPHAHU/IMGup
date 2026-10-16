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
JAR_NAME="ImgUp-2.0.0.jar"
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
import re
import sys
import zipfile

jar = sys.argv[1]
with zipfile.ZipFile(jar) as zf:
    names = zf.namelist()
    for required in ("plugin.yml", "config.yml",
                     "com/huhumc/imgup/ImgUpPlugin.class",
                     "com/huhumc/imgup/ImgUpCommand.class",
                     "com/huhumc/imgup/WebServer.class",
                     "com/huhumc/imgup/UploadStore.class",
                     "com/huhumc/imgup/ImageTools.class",
                     "com/huhumc/imgup/Messages.class",
                     # 网页资源必须打进去，否则插件起来了也没有页面
                     "web/index.html", "web/upload.html", "web/result.html",
                     "web/app.css", "web/i18n.js",
                     # 语言文件也要进 jar，否则插件启动时没有可释放的默认文案
                     "messages/zh.yml", "messages/en.yml"):
        if required not in names:
            sys.exit("产物里缺少 " + required)

    with zf.open("plugin.yml") as fh:
        head = fh.read(400).decode("utf-8", "replace")
    for key in ("name:", "version:", "main:", "api-version:"):
        if key not in head:
            sys.exit("plugin.yml 缺少 " + key)
    print("    plugin.yml / config.yml / 主类 都在，plugin.yml 头部正常")

    # 语言文件的两条硬性要求，等插件跑起来才发现就太晚了：
    #   1. 键名不能含数字 —— 模板用 $site_xxx 占位，替换器遇到数字会截断
    #      （曾把 $site_home_step1_title 只替换到 $site_home_step）
    #   2. 各语言文件的键必须一致，缺的会回退到默认语言
    tables = {}
    for lang in ("zh", "en"):
        name = "messages/" + lang + ".yml"
        with zf.open(name) as fh:
            text = fh.read().decode("utf-8")
        keys = re.findall(r"^([A-Za-z][\w.]*):", text, re.M)
        # 只有 site.* 会进模板占位符（$site_xxx），所以只有它们不能带数字。
        # 其它键（index.step1Title 之类）是前端 JS 查表用的，带数字完全没问题。
        bad = [k for k in keys if k.startswith("site.") and re.search(r"\d", k)]
        if bad:
            sys.exit(name + " 里的 site.* 键名不能含数字（模板替换会截断）：" + str(bad[:5]))
        if not keys:
            sys.exit(name + " 里一条文案都没有")
        tables[lang] = set(keys)

    only_zh = sorted(tables["zh"] - tables["en"])
    only_en = sorted(tables["en"] - tables["zh"])
    if only_zh or only_en:
        sys.exit("语言文件键不一致：en 缺 " + str(only_zh[:5]) + "，zh 缺 " + str(only_en[:5]))
    print("    语言文件：zh/en 各 " + str(len(tables["zh"]))
          + " 条键，键名合法且完全一致")

    # 非中文语言文件里不允许出现中文 —— 漏译会让玩家在英文界面看到中文，
    # 而且站长根本不知道该改哪一条。（meta.* 是语言自称，必须用该语言自己的写法）
    cjk = re.compile(r"[\u4e00-\u9fff]")
    for lang in sorted(tables):
        if lang.startswith("zh"):
            continue
        name = "messages/" + lang + ".yml"
        with zf.open(name) as fh:
            text = fh.read().decode("utf-8")
        leaked = []
        for key, value in re.findall(r'^([A-Za-z][\w.]*):\s*"(.*)"\s*$', text, re.M):
            if key.startswith("meta."):
                continue
            if cjk.search(value):
                leaked.append(key + "=" + value[:30])
        if leaked:
            sys.exit(name + " 里还有中文（漏译）：" + "; ".join(leaked[:5]))
    print("    非中文语言文件里没有漏译的中文")

    # 模板里的 $site_xxx 占位符必须都能在语言文件里找到
    missing = []
    for page in ("index", "upload", "result"):
        with zf.open("web/" + page + ".html") as fh:
            html = fh.read().decode("utf-8")
        for var in sorted(set(re.findall(r"\$site_([a-z_]+)", html))):
            if "site." + var not in tables["zh"]:
                missing.append(page + ".html 的 $site_" + var + " 没有对应的 site." + var)
    if missing:
        sys.exit("模板占位符在语言文件里找不到：" + "; ".join(missing[:5]))
    print("    模板占位符都能在语言文件里找到")
PY

ls -la "$OUT_DIR/$JAR_NAME"
echo "完成：$OUT_DIR/$JAR_NAME"
