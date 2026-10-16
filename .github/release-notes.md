# ImgUp 1.0.0

**插件自带图片上传网站。** 玩家在网页上传图片，在游戏里用 `/imgup` 确认上传码，
然后用地图把图片贴出来。

整个网站跑在插件进程里 —— 单个 jar，不需要 Docker、不需要 Python、不需要 RCON，
也没有 `shared_secret`。网页和插件直接内存通信。

## 主要特性

- **插件内置网站**：用 JDK 自带的 `com.sun.net.httpserver` 配虚拟线程，没有引入任何网络框架
- **身份绑在游戏里**：网页生成的 8 位上传码必须在游戏内 `/imgup` 确认，所以网页上填的玩家名不可伪造，玩家也不需要注册账号
- **待审和被拒的图片不公开**：`/preview/` 只有后台管理员和上传者本人能访问，其余一律 404
- **图片处理**：只收 PNG / JPG，永远重新编码（丢掉 EXIF 等元数据），带像素上限防解压炸弹，只缩不放
- **中英双语**：文案全在 `messages/` 下，可自行增删语言；玩家在游戏里看到的消息跟随其客户端语言
- **网页后台**：批量审核、删除、按状态筛选，登录失败有按 IP 的限流
- **各种限额**：请求体空闲超时、并发上传上限、每玩家每日限额、取码与后台登录按 IP 限流
- **可配置站点名与备案号**：备案号留空时页脚完全不出现这一行

## 安装

1. 把 `ImgUp-1.0.0.jar` 放进服务端的 `plugins/` 目录
2. 启动服务端，首次启动会生成 `config.yml`，并打印一个随机生成的后台管理密码
3. 打开 `http://<你的服务器IP>:8091`

记得把 `web.public_base_url` 填成对外地址，否则发给玩家的链接会是 `127.0.0.1`。

## 环境要求

| 项目 | 要求 |
| --- | --- |
| 服务端 | Paper / Purpur，API `26.3.build.194-stable` |
| Java | 21 或更高 |
| 配套插件 | [Images](https://github.com/Andavin/Images)（Custom Images）—— 真正把图片贴进游戏的那个插件 |

完整说明见 [README.md](https://github.com/DICEALPHAHU/IMGup/blob/main/README.md)。

## 许可证

[GNU General Public License v3.0](https://github.com/DICEALPHAHU/IMGup/blob/main/LICENSE)

---

## English

**An image upload site that ships inside the plugin.** Players upload an image in
a web page, confirm an upload code in game with `/imgup`, then place it on a map.

The whole site runs inside the plugin process — a single jar. No Docker, no
Python, no RCON, no `shared_secret`.

Highlights:

- **Built-in web server** using the JDK's own `com.sun.net.httpserver` with a
  virtual-thread executor; no network framework
- **Identity confirmed in game**: the 8-character code must be confirmed in game
  with `/imgup`, so the player name submitted from the web cannot be forged, and
  players never need an account
- **Pending and rejected images are not public**: `/preview/` is served only to
  an admin or the player who uploaded that image; everyone else gets a 404
- **Image handling**: PNG/JPG only, always re-encoded (EXIF and other metadata
  dropped), pixel cap against decompression bombs, downscale only
- **Bilingual** (Chinese and English); language files live in `messages/`, and
  in-game messages follow each player's client locale
- **Web admin panel** with bulk approval, deletion and status filtering; failed
  logins are rate-limited per IP
- **Limits everywhere**: request-body idle timeout, concurrent upload cap, daily
  per-player quota, per-IP limits on codes and admin logins

Install: drop `ImgUp-1.0.0.jar` into `plugins/`, start the server (a random admin
password is printed on first start), then open `http://<your-ip>:8091`. Remember
to set `web.public_base_url` to your public address.

Requires Paper/Purpur (API `26.3.build.194-stable`), Java 21+, and the
[Images](https://github.com/Andavin/Images) plugin.

Full documentation: [README.en.md](https://github.com/DICEALPHAHU/IMGup/blob/main/README.en.md).

License: [GPL-3.0](https://github.com/DICEALPHAHU/IMGup/blob/main/LICENSE)
