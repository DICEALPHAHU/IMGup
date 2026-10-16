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
# 产物命名：插件名-版本号-git短哈希.jar（不在 git 仓库里就用 nogit）
VERSION="$(sed -n 's/^version:[[:space:]]*//p' src/main/resources/plugin.yml | head -1 | tr -d '\r')"
[ -z "$VERSION" ] && VERSION="0.0.0"
GIT_SHORT="$(git rev-parse --short HEAD 2>/dev/null || true)"
[ -z "$GIT_SHORT" ] && GIT_SHORT="nogit"
JAR_NAME="ImgUp-${VERSION}-${GIT_SHORT}.jar"
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
import glob
import re
import sys
import zipfile

jar = sys.argv[1]
with zipfile.ZipFile(jar) as zf:
    names = zf.namelist()
    for required in ("plugin.yml", "config.yml",
                     "com/github/dicealphahu/imgup/ImgUpPlugin.class",
                     "com/github/dicealphahu/imgup/ImgUpCommand.class",
                     "com/github/dicealphahu/imgup/WebServer.class",
                     "com/github/dicealphahu/imgup/UploadStore.class",
                     "com/github/dicealphahu/imgup/ImageTools.class",
                     "com/github/dicealphahu/imgup/Messages.class",
                     "com/github/dicealphahu/imgup/ServerProperties.class",
                     # 网页资源必须打进去，否则插件起来了也没有页面
                     "web/index.html", "web/upload.html", "web/result.html",
                     "web/app.css", "web/i18n.js", "web/admin.html",
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

    # 模板里的 $msg_<键名> 必须都能在语言文件里找到（键名里的点写成下划线）。
    # 找不到的话服务端会把它渲染成**空字符串** —— 页面上少一块，而且不报错，
    # 光看页面很难发现。所以这里是「模板用了但没声明」的兜底检查。
    #
    # （原来这里查的是 $site_xxx —— 那是早期约定的残留，改成统一 $msg_ 之后
    #   就再也没匹配到过任何东西，等于失效了。）
    def form_of(key):
        return key.replace(".", "_")

    by_form = {form_of(k): k for k in tables["zh"]}
    missing = []
    for page in ("index", "upload", "result", "admin"):
        with zf.open("web/" + page + ".html") as fh:
            html = fh.read().decode("utf-8")
        for var in sorted(set(re.findall(r"\$msg_([a-zA-Z0-9_]+)", html))):
            if var not in by_form:
                missing.append(page + ".html 的 $msg_" + var + " 没有对应的键")
    if missing:
        sys.exit("模板占位符在语言文件里找不到（会渲染成空白）：" + "; ".join(missing[:5]))
    print("    模板里的 $msg_ 占位符都能在语言文件里找到")

    all_keys = set(tables["zh"])

    # 死键检查：语言文件里声明了、但代码里根本没引用的键。
    # 站长改到这种键会发现「改了不生效」，所以要么用上要么删掉。
    #
    # 认这几种使用方式（缺一种就会误报，所以都要覆盖）：
    #   · 键名原样出现：data-i18n="k"、t('k')、key: 'k'、Java 里的字面量
    #   · 服务端渲染：$msg_ 加键名（点换成下划线）
    #   · 服务端动态下发：err.<code>，code 取自 error("code", ...)
    # 注意：**不能**把 i18n.js 兜底字典里出现过就算用到 —— 那正是死键藏身的地方
    # （common.footerPermBefore/After 就这么躲了很久）。
    blob = ""
    for page in ("index", "upload", "result", "admin"):
        with zf.open("web/" + page + ".html") as fh:
            blob += fh.read().decode("utf-8")
    with zf.open("web/i18n.js") as fh:
        i18n_text = fh.read().decode("utf-8")
    # i18n.js 里的兜底字典要**先摘掉**再扫 —— 字典条目自己不能证明自己「被用到」，
    # 那正是死键的藏身处（common.brand、common.footerPerm* 都这么躲过去的）。
    lines = i18n_text.split("\n")
    start = next(i for i, l in enumerate(lines) if l.strip().startswith("var DICT = {"))
    end = next(i for i in range(start + 1, len(lines)) if lines[i] == "  };")
    blob += "\n".join(lines[:start] + lines[end + 1:])
    for src in glob.glob("src/main/java/**/*.java", recursive=True):
        with open(src, encoding="utf-8") as fh:
            blob += fh.read()
    dynamic_err = {"err." + c for c in re.findall(r'error\("([a-z_]+)"', blob)}

    def key_is_used(key):
        if key in blob:
            return True
        if ("$msg_" + key.replace(".", "_")) in blob:
            return True
        return key in dynamic_err

    # 反向：服务端会下发的 err.<code> 必须都在语言文件里声明。
    # 缺一个，客户端 t() 就会把键名（err.xxx）直接显示给玩家。
    missing_err = sorted(e for e in dynamic_err if e not in all_keys)
    if missing_err:
        sys.exit("服务端会下发这些错误键，但语言文件里没声明（玩家会看到键名）："
                 + str(missing_err))

    dead_any = sorted(k for k in all_keys if not key_is_used(k))
    if dead_any:
        sys.exit("这些键声明了但代码里没有任何引用（改了不会生效，建议删掉）："
                 + str(dead_any))
    print("    语言文件里 " + str(len(all_keys)) + " 个键都在用，没有死键")

    # i18n.js 的离线兜底字典必须和语言文件保持一致。
    # 它本该是 yml 的镜像，手工维护会漂移 —— 曾经 30 个 site.* 键被放进 zh 段
    # 且值是英文，en 段则完全没有，离线打开页面时中文界面会冒出英文。
    def dict_section(text, name, nxt):
        """取出字典某一段里的键名集合。"""
        a = text.index(name + ": {")
        b = text.index(nxt, a) if nxt else text.index("\n  };", a)
        # 只关心键；每行形如   'site.brand': 'CustomImagesUp',
        return {m for m in re.findall(r"^\s*'([\w.]+)':", text[a:b], re.M)}

    zh_dict = dict_section(i18n_text, "zh", "en:")
    en_dict = dict_section(i18n_text, "en", None)
    want = {k for k in all_keys if not k.startswith("game.")}
    problems = []
    if set(zh_dict) != set(en_dict):
        problems.append("中英两段的键不一样：多 "
                        + str(sorted(set(zh_dict) - set(en_dict))[:5])
                        + " 少 " + str(sorted(set(en_dict) - set(zh_dict))[:5]))
    if want - set(zh_dict):
        problems.append("语言文件里有但字典里缺：" + str(sorted(want - set(zh_dict))[:8]))
    if set(zh_dict) - want:
        problems.append("字典里有但语言文件里没有：" + str(sorted(set(zh_dict) - want)[:8]))
    if problems:
        sys.exit("i18n.js 兜底字典和语言文件不一致（离线打开页面会显示错语言或键名）："
                 + "；".join(problems))
    print("    i18n.js 离线兜底字典与语言文件一致（" + str(len(zh_dict)) + " 键）")

    # 死配置检查：config.yml 里写出来的每一项都必须真的被 Java 读过。
    # 站长改了一个没人读的键会以为生效了，实际什么都没发生 ——
    # web.request_timeout_seconds 就这么空转了不知道多久（解析了但从没用），
    # 结果就是「挂住的请求永远不断开」。
    with zf.open("config.yml") as fh:
        cfg_text = fh.read().decode("utf-8")
    stack, cfg_keys = [], []
    for line in cfg_text.split("\n"):
        if not line.strip() or line.strip().startswith("#"):
            continue
        m = re.match(r"^(\s*)([a-z_][\w]*):(.*)$", line)
        if not m:
            continue
        indent, key, rest = len(m.group(1)), m.group(2), m.group(3).strip()
        while stack and stack[-1][0] >= indent:
            stack.pop()
        path = ".".join([k for _, k in stack] + [key])
        if rest == "":
            stack.append((indent, key))
        else:
            cfg_keys.append(path)

    java_blob = ""
    for src in glob.glob("src/main/java/**/*.java", recursive=True):
        with open(src, encoding="utf-8") as fh:
            java_blob += fh.read()
    java_literals = set(re.findall(r'"([\w.]+)"', java_blob))
    dead_cfg = sorted(k for k in cfg_keys if k not in java_literals)
    if dead_cfg:
        sys.exit("这些配置项写在 config.yml 里但 Java 从没读过（改了不会生效）：" + str(dead_cfg))
    print("    config.yml 的 " + str(len(cfg_keys)) + " 个配置项都被读取了，没有死配置")


    # 版本号只能有一个来源（plugin.yml）。WebServer 里若写死版本号，
    # /api/health 报出来的就和插件实际版本对不上 —— 这种漂移直接拦掉。
    with zf.open("com/github/dicealphahu/imgup/WebServer.class") as fh:
        web_bytes = fh.read()
    import re as _re
    version_in_yml = _re.search(rb"version: ([0-9][^\r\n]*)", head.encode() if isinstance(head, str) else head)
    # 从字节码里找形如 "1.0.0" 的字面量，如果它和 plugin.yml 的版本同时出现两次以上，
    # 说明某处写死了版本号（正常只有一个来源）
    literal = version_in_yml.group(1) if version_in_yml else b""
    if literal and web_bytes.count(literal) > 0:
        sys.exit("WebServer.class 里出现了写死的版本号 " + literal.decode()
                 + "；版本号只能来自 plugin.yml，请不要在代码里写死")
    print("    版本号没有在 WebServer 里写死")

    # 代码里引用的 game.* 键必须都在语言文件里定义了。
    # 打错一个字（game.confrim_ok）不会报错，但玩家聊天框里就会看到键名本身。
    used_game = set()
    for src in glob.glob("src/main/java/**/*.java", recursive=True):
        with open(src, encoding="utf-8") as fh:
            used_game |= set(re.findall(r'"(game\.[a-z_]+)"', fh.read()))
    missing_game = sorted(used_game - tables["zh"])
    if missing_game:
        sys.exit("代码里用了但语言文件里没定义的 game.* 键：" + str(missing_game))
    print("    用到的 " + str(len(used_game)) + " 个 game.* 键都有定义")

    # 页面里引用的 /static/xxx 必须在 jar 里真的存在。
    # 这条是血的教训：样式表从 style.css 改名成 app.css 之后，三个页面还指着
    # 旧名字，页面完全没样式，但当时所有测试照样全绿。
    jar_web = {n for n in zf.namelist() if n.startswith("web/")}
    missing_assets = []
    for page in ("index", "upload", "result", "admin"):
        with zf.open("web/" + page + ".html") as fh:
            html = fh.read().decode("utf-8")
        for ref in re.findall(r'(?:href|src)="(/static/[^"#?]+)"', html):
            if "web/" + ref[len("/static/"):] not in jar_web:
                missing_assets.append(page + " -> " + ref)
    if missing_assets:
        sys.exit("页面引用的静态资源在 jar 里不存在（页面会缺样式/脚本）："
                 + str(sorted(set(missing_assets))))
    print("    页面引用的 /static/ 资源都在 jar 里")

    # hidden 属性必须真能隐藏元素。
    # 浏览器默认的 [hidden]{display:none} 是 UA 样式，作者样式里任何一条 display
    # 都会盖过它 —— 曾经因为 .lang-menu 写了 display:flex，语言下拉菜单一直展开，
    # 挡住了页面。所以样式表里必须有一条 !important 的 [hidden]，而且不许有
    # 别的 !important display 跟它竞争。
    with zf.open("web/app.css") as fh:
        css_text = fh.read().decode("utf-8")
    css_code = re.sub(r"/\*.*?\*/", "", css_text, flags=re.S)
    if not re.search(r"\[hidden\]\s*\{\s*display\s*:\s*none\s*!important", css_code):
        sys.exit("app.css 里缺少 [hidden] { display: none !important; } —— "
                 "hidden 属性会被作者样式的 display 盖过，元素藏不住")
    rivals = [(sel.strip(), val.strip())
              for sel, body in re.findall(r"([^{}]+)\{([^}]*)\}", css_code)
              for val in re.findall(r"display\s*:\s*([^;]+?)\s*!important", body)
              if val.strip() != "none"]
    if rivals:
        sys.exit("这些 !important 的 display 会和 [hidden] 竞争，可能让元素藏不住：" + str(rivals))
    print("    hidden 属性有 !important 兜底，且无人竞争")

    # lang-switch 是 i18n.js 运行时创建的容器类，HTML 里不许用它 ——
    # 曾经后台的退出登录按钮也写成 class="lang-switch"，导致
    # 「是否已创建」的判断被它命中，后台页面永远建不出语言菜单。
    for page in ("index", "upload", "result", "admin"):
        with zf.open("web/" + page + ".html") as fh:
            if "lang-switch" in fh.read().decode("utf-8"):
                sys.exit(page + ".html 里用了 lang-switch（这个类由 i18n.js 创建，"
                         "HTML 里用它会顶掉语言菜单的创建判断）")
    print("    HTML 里没有误用 JS 专用类")
PY

ls -la "$OUT_DIR/$JAR_NAME"
echo "完成：$OUT_DIR/$JAR_NAME"
