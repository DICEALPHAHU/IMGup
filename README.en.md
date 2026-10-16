# ImgUp

**An image upload site that ships inside the plugin.** Players upload an image in
a web page, confirm an upload code in game with `/imgup`, then place it on a map.

The whole site runs inside the plugin process — a single jar. No Docker, no
Python, no RCON, no `shared_secret`. The web page and the plugin talk through
memory.

Chinese: [README.md](README.md)

---

## Why

Without this, a player who wants a picture in game has to copy the file into the
server directory themselves and then run a command. For most players that step
is where the idea dies.

ImgUp turns it into: **open a page, pick an image, upload, type one code in game.**
File naming, scaling, format conversion and moderation are all automatic.

A few deliberate choices:

- **Nothing is uploaded to a third-party service.** Images stay on your server.
- **Player identity is bound in game.** The 8-character upload code created on
  the web page has to be confirmed in game with `/imgup`, so the player name on
  the web page is trustworthy without any account system.
- **Images are only ever scaled down, never up.** Small images are not stretched
  into mush, and large ones do not waste map space.
- **Everything is re-encoded.** Whatever is uploaded is decoded and re-encoded to
  JPEG through ImageIO, which also drops EXIF and similar metadata and avoids
  "script hidden in an image" style problems.

---

## Requirements

| | |
| --- | --- |
| Server | Paper / Purpur, API `26.3.build.194-stable` (nearby versions usually work) |
| Java | 21 or newer |
| Companion plugin | Images (CustomImages) — the plugin that actually places images in game |

> Without the Images plugin ImgUp still starts, but uploads land in its own data
> directory and `/image create` will not find them. It says so in the log.

---

## Installation

1. Drop `ImgUp-<version>-<git-short-hash>.jar` into your server's `plugins/`.
2. Start the server. On first start it will:
   - create `plugins/ImgUp/config.yml`
   - generate a random admin password and **print it to the console and the log**
   - start the web server on the configured port
3. Open `http://<your-server-ip>:<port>` and check the page loads.

If players should reach it from the internet, open that port in your **firewall
and your cloud provider's security group**. `web.public_base_url` is used to
build shared links; set it to the address players actually use.

---

## Flow

For players:

1. Open the site and click "get upload code" — you get an 8-character code
   (valid for 5 minutes by default).
2. In game, type `/imgup <code>` in chat.
3. The page notices the confirmation and lets you pick an image.
4. When the upload finishes, copy the in-game command it shows you.
5. Run it in game, then right-click while holding a map to place the image.

For admins:

- In game: `/imgup list` for the pending queue, `/imgup ok <name>` to approve,
  `/imgup no <name> [reason]` to reject.
- On the web: `http://<address>/admin`, log in with `web.admin_password`.
  Bulk approve, delete, and filter by status.

---

## Commands and permissions

| Command | Description |
| --- | --- |
| `/imgup <code>` | Confirm an upload code created on the web page |
| `/imgup list` | List images waiting for review |
| `/imgup ok <name>` | Approve and publish |
| `/imgup no <name> [reason]` | Reject (the reason is shown to the player) |
| `/imgup del <name>` | Delete an image |
| `/imgup info` | Show web server status |
| `/imgup reload` | Reload config and language files (changing the port needs a restart) |

| Permission | Default | Description |
| --- | --- | --- |
| `imgup.use` | everyone | Confirm upload codes |
| `imgup.admin` | op | Review, delete, reload |

---

## Configuration

The file is `plugins/ImgUp/config.yml`; every key has a bilingual comment.
Apply changes with `/imgup reload` (**exception**: `web.port` needs a server
restart).

The keys you are most likely to touch:

| Key | Default | Description |
| --- | --- | --- |
| `web.port` | `8091` | Listen port |
| `web.public_base_url` | empty | Public address; inferred from the request when empty |
| `web.site_name` | `CustomImagesUp` | Header brand, also used in the browser tab title |
| `web.admin_password` | generated | Password for the web admin panel |
| `web.auto_publish` | `true` | Publish immediately; turn off to send uploads to a review queue |
| `web.icp_license` | empty | ICP filing number. When set, it is shown at the bottom of the footer and links to the MIIT lookup page |
| `web.request_timeout_seconds` | `60` | **Idle** timeout for request bodies; a client that stalls is disconnected |
| `upload.max_upload_bytes` | `5242880` | Size limit per image (5 MB) |
| `upload.max_dimension` | `2048` | Longest-edge limit (downscale only) |
| `limits.per_player_daily` | `20` | Uploads per player per day |
| `limits.max_concurrent_uploads` | `8` | Uploads processed at once; 0 = unlimited |
| `language` | `zh` | Default language |

> `web.icp_license` exists for mainland China's filing requirement: when it is
> **empty the footer line is not rendered at all**. When set, it is shown and is
> clickable, styled to look like ordinary text rather than a link.

---

## Language files

Every string — page text, error messages, status text, and the messages players
see in game chat — lives in `plugins/ImgUp/messages/<lang>.yml`. Chinese and
English ship built in.

- **Editing text**: change the value, then `/imgup reload`.
- **Adding a language**: copy `zh.yml` to `jp.yml` and translate the values. The
  language switcher in the top right picks it up automatically; the display name
  comes from `meta.name` inside the file.
- **In-game messages** follow each player's client locale.
- Backticks in a language file mark inline code and render as `<code>` on the page:
  ```yaml
  site.footer_note: "Server: `{motd}`. Posting in game requires the `images.command.create` permission."
  ```

**Upgrading never overwrites your edits.** New keys are appended; existing
translations are left exactly as they are.

---

## Building from source

No Maven or Gradle — you only need the JDK's `javac` / `jar` and Python 3:

```bash
bash build.sh
```

The jar lands in `target/ImgUp-<version>-<git-short-hash>.jar`.

The first run uses `fetch-deps.py` to download the Paper API and its compile
dependencies into `.build-cache/`; after that it builds offline. Set
`IMGUP_BUILD_CACHE` to move the cache elsewhere.

Besides compiling, `build.sh` runs a set of artifact checks (language files have
identical key sets, no dead keys, every static asset referenced by a page is in
the jar, every config key is actually read, and so on). Any failure aborts the
build. Every one of those checks exists because something actually broke once.

## Tests

```bash
bash test.sh
```

| Test | Covers |
| --- | --- |
| `CoreSelfTest` | Pure logic: multipart parsing, image processing, file naming, sessions, message rendering |
| `HttpSelfTest` | Starts a real HTTP server and walks the whole get-code / confirm / upload / result flow |
| `ConfigElementsTest` | Page elements really come from the language files, so editing config takes effect |

---

## Implementation notes

**The HTTP server is the JDK's own `com.sun.net.httpserver`** with a
virtual-thread executor. No network framework is pulled in, so the artifact
stays a single jar with no runtime dependencies beyond the server itself.

**Uploads are written straight into the Images plugin's data directory**, because
that is the only place `/image create` looks. The review index belongs to ImgUp;
losing it does not affect images that are already published, since the game only
cares whether the file exists.

**Connections cannot be parked forever.** Reading a request body has an idle
timeout: a client that connects and stops sending is disconnected after
`web.request_timeout_seconds`. The number of uploads processed at once is capped
too, and excess requests are told to come back later instead of buffering several
megabytes first.

**The site only listens on the port you configured.** With `web.enabled: false`
no port is opened at all.

---

## License

[GNU General Public License v3.0](LICENSE)
