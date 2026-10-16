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
                     "com/huhumc/imgup/ServerProperties.class",
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

    # 模板里的 $site_xxx 占位符必须都能在语言文件里找到
    missing = []
    for page in ("index", "upload", "result", "admin"):
        with zf.open("web/" + page + ".html") as fh:
            html = fh.read().decode("utf-8")
        for var in sorted(set(re.findall(r"\$site_([a-z_]+)", html))):
            if "site." + var not in tables["zh"]:
                missing.append(page + ".html 的 $site_" + var + " 没有对应的 site." + var)
    if missing:
        sys.exit("模板占位符在语言文件里找不到：" + "; ".join(missing[:5]))

    # 反向检查：site.* 声明了就必须真的被某个模板用上。
    # 否则站长改了它页面却没变，会以为「配置不生效」—— 那种坑不如构建时拦掉。
    # 三种写法都算「用到了」：
    #   $msg_site_xxx          服务端渲染时替换（静态文字）
    #   data-i18n="site.xxx"   前端引擎翻译（标签上的静态文字）
    #   t('site.xxx')          前端 JS 里取（动态生成的文字）
    #   site.xxx 出现在 i18n.js 的兜底字典里也算（离线打开页面时的兜底）
    used_site = set()
    for page in ("index", "upload", "result", "admin"):
        with zf.open("web/" + page + ".html") as fh:
            html = fh.read().decode("utf-8")
        used_site |= {"site." + v for v in re.findall(r"\$msg_site_([A-Za-z0-9_]+)", html)}
        used_site |= set(re.findall(r'data-i18n="(site\.[A-Za-z0-9_]+)"', html))
        used_site |= set(re.findall(r"t\('(site\.[A-Za-z0-9_]+)'", html))
        # data-i18n-attr="title:site.xxx,aria-label:site.yyy" —— 属性上的翻译
        for spec in re.findall(r'data-i18n-attr="([^"]+)"', html):
            for pair in spec.split(','):
                if ':' in pair:
                    key = pair.split(':', 1)[1].strip()
                    if key.startswith('site.'):
                        used_site.add(key)
    with zf.open("web/i18n.js") as fh:
        i18n_js = fh.read().decode("utf-8")
    used_site |= set(re.findall(r"'(site\.[A-Za-z0-9_]+)':", i18n_js))
    used_site |= set(re.findall(r"t\('(site\.[A-Za-z0-9_]+)'", i18n_js))
    # JS 里动态设的属性翻译：setAttribute('data-i18n-attr', 'title:site.xxx')
    for spec in re.findall(r"setAttribute\('data-i18n-attr',\s*'([^']+)'", i18n_js):
        for pair in spec.split(','):
            if ':' in pair:
                key = pair.split(':', 1)[1].strip()
                if key.startswith('site.'):
                    used_site.add(key)
    declared_site = {k for k in tables["zh"] if k.startswith("site.")}
    dead = sorted(declared_site - used_site)
    if dead:
        sys.exit("这些 site.* 键声明了但没有页面用到（改了不会生效）：" + str(dead))

    # 正向检查（这条曾经漏掉，害得第二步面板标题是空的）：
    # 模板里用到的 $msg_xxx 必须在语言文件里真的有这个键。
    # 少了键不会报错，只会让那一块渲染成空白 —— 光看页面很难发现。
    all_keys = tables["zh"]
    form = {k.replace(".", "_"): k for k in all_keys}
    missing_keys = []
    for page in ("index", "upload", "result", "admin"):
        with zf.open("web/" + page + ".html") as fh:
            html = fh.read().decode("utf-8")
        for used in re.findall(r"\$msg_([A-Za-z0-9_]+)", html):
            if form.get(used) is None:
                missing_keys.append(page + " -> $msg_" + used)
    if missing_keys:
        sys.exit("模板用了语言文件里不存在的键（那部分会渲染成空白）："
                 + str(sorted(set(missing_keys))))

    # 页面里真正用到的 $msg_ 键必须来自「当前语言」，且键名要拼得出来
    # 模板里用到的非 $msg_ 变量（$name、$plugin_url 这些）必须真的有人提供。
    # 跟缺键一样，没人提供的变量会**静默渲染成空字符串** —— 后台页脚曾经
    # 就因为 serveAdminPage 绕过了公共取值，渲染出一个 href="" 的空链接。
    with zf.open("com/huhumc/imgup/WebServer.class") as fh:
        server_class = fh.read()
    # 从源码里取（class 里的常量池不好可靠地解析字符串归属）
    with open("src/main/java/com/huhumc/imgup/WebServer.java", encoding="utf-8") as fh:
        server_src = fh.read()
    provided = set(re.findall(r'values\.put\("([a-z_]+)"', server_src))
    used_vars = set()
    for page in ("index", "upload", "result", "admin"):
        with zf.open("web/" + page + ".html") as fh:
            html = fh.read().decode("utf-8")
        for v in re.findall(r"\$([a-zA-Z_][a-zA-Z0-9_]*)", html):
            if not v.startswith("msg_"):
                used_vars.add(v)
    unprovided = sorted(used_vars - provided)
    if unprovided:
        sys.exit("模板用了但 WebServer 没提供的变量（会渲染成空白）：" + str(unprovided))
    print("    模板用到的 " + str(len(used_vars)) + " 个变量都有提供")

    print("    模板占位符都能在语言文件里找到，且没有用不上的 site.* 键")

    # 版本号只能有一个来源（plugin.yml）。WebServer 里若写死版本号，
    # /api/health 报出来的就和插件实际版本对不上 —— 这种漂移直接拦掉。
    with zf.open("com/huhumc/imgup/WebServer.class") as fh:
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
    import glob
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
