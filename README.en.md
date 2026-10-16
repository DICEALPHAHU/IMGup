# ImgUp

**An image upload site that ships inside the plugin.** Players upload an image in
a web page, confirm an upload code in game with `/imgup`, then place it on a map.

The whole site runs inside the plugin process — a single jar. No Docker, no
Python, no RCON, no `shared_secret`. The web page and the plugin talk through
memory.

Chinese (Simplified): [README.md](README.md)

---

## Why use it

With the stock setup, a player who wants an image on a map has to drop the file
into the server directory themselves and type a command, keeping an eye on the
naming rules and accepted formats. That is enough friction to put most players
off.

ImgUp turns it into: **open the page -> pick an image -> upload -> type one code
in game**. The file name, scaling, format conversion and moderation are all
automatic.

Some deliberate trade-offs:

- **Nothing is uploaded to a third party.** Images stay on your own server.
- **Player identity is bound in game.** The 8-character code from the web page
  has to be confirmed in game with `/imgup`, so the player name on the web side
  is trustworthy and nobody needs to register an account.
- **File names follow the `<player>-<image name>` format automatically.** Many
  servers require that naming scheme; the plugin just generates it, so players
  never have to remember the rule.
- **PNG and JPG only.** Animated images and PDFs are rejected; PDF matters in
  particular, because it is not an image at all but plugin flaws have let it be
  treated as one.
- **Images are scaled down, never up.** A small image is not stretched into
  mush, and a small one is not passed off as high resolution and wasted on a map.
- **Everything is re-encoded.** Whatever you upload is re-encoded through
  ImageIO, which drops EXIF and similar metadata and shuts down "script hidden
  inside an image" style problems.

---

## Requirements

| Item | Requirement |
| --- | --- |
| Server | Paper / Purpur, API `26.3.build.194-stable` (nearby versions usually work too) |
| Java | 21 or newer |
| Companion plugin | Images (CustomImages) — the plugin that actually puts images in game |

> Without the Images plugin the plugin still starts, but images go into its own
> data directory, `/image create` cannot find them, and the log tells you so.

---

## Installation

1. Put `ImgUp-<version>-<short git hash>.jar` into the server's `plugins/` folder.
2. Start the server. On first start it will:
   - create `plugins/ImgUp/config.yml`
   - generate a random admin password for the web panel and **print it to the
     console and the log**
   - start the site on the configured port
3. Open `http://<your server IP>:<port>` and check that the page loads.

If players need to reach it from the internet, remember to open the port in your
**firewall / cloud security group**.

> **Do fill in `web.public_base_url`.** It decides the address shown to players
> and the image links that get copied. When left empty it falls back to
> `web.host` plus the port, and the default `web.host` is `0.0.0.0`, which
> produces `http://127.0.0.1:8091` — reachable only from the server itself. The
> plugin prints a warning at startup when it is configured that way.

---

## How it works for players

1. Open the site and click "Get upload code" to receive an 8-character code
   (valid for 5 minutes by default).
2. In game, type `/imgup <code>` in chat.
3. The page detects the confirmation automatically and lets you pick an image.
4. When the upload finishes the page shows an in-game command; copy it.
5. Paste and run it in game, hold a map and right-click as prompted.

> **The upload page warns about aspect ratio.** Minecraft maps are square, so a
> non-square image gets stretched or letterboxed. The page carries a permanent
> note about this, and when you select a non-square image it also warns you with
> that image's actual dimensions. It is only a warning — it never blocks the
> upload.

For admins:

- In game: `/imgup list` for the pending queue, `/imgup ok <name>` to approve,
  `/imgup no <name> [reason]` to reject.
- Web panel: `http://<address>/admin`, sign in with `web.admin_password`.
  Bulk approval, deletion and status filtering are all there.

---

## Commands and permissions

| Command | Aliases | Description |
| --- | --- | --- |
| `/imgup <code>` | | Confirm an upload code from the web page |
| `/imgup list [page]` | `ls` | List pending images (paginated) |
| `/imgup ok <name>` | `approve`, `pass` | Approve and publish |
| `/imgup no <name> [reason]` | `reject`, `deny` | Reject (the reason is shown to the player) |
| `/imgup del <name>` | `delete`, `rm` | Delete an image |
| `/imgup info` | `status` | Show site status |
| `/imgup reload` | | Reload config and messages, and **restart the site** |

> `/imgup reload` stops the site and brings it back up with the new config, so
> **changing the port does not require a server restart**. In-flight uploads get
> a 2-second grace period rather than being cut off immediately.

| Permission | Default | Description |
| --- | --- | --- |
| `imgup.use` | everyone | Confirm upload codes |
| `imgup.admin` | OP | Approve, delete, reload |

---

## Configuration

The file is `plugins/ImgUp/config.yml` and every key has a bilingual comment.
Apply changes with `/imgup reload`.

### Site

| Key | Default | Description |
| --- | --- | --- |
| `web.enabled` | `true` | Set to false and no port is opened at all |
| `web.port` | `8091` | Port the site listens on |
| `web.host` | `0.0.0.0` | Bind address. Use `127.0.0.1` for local access only |
| `web.public_base_url` | empty | **The public address** — strongly recommended. See "Installation" for what happens if it is empty |
| `web.site_name` | `CustomImagesUp` | Brand name in the header, also used in the browser tab title |
| `web.auto_publish` | `true` | Publish immediately. Turn off to send uploads to the moderation queue |
| `web.admin_enabled` | `true` | Whether `/admin` is available |
| `web.admin_password` | random | Password for the web panel, printed on first start |
| `web.icp_license` | empty | Chinese ICP filing number. When set it appears in the footer and links to the MIIT lookup page |
| `web.request_timeout_seconds` | `60` | **Idle** timeout for the request body. A client that connects but sends nothing is dropped after this |

### Images

| Key | Default | Description |
| --- | --- | --- |
| `upload.max_upload_bytes` | `5242880` | Maximum upload size (5 MB) |
| `upload.max_dimension` | `2048` | Longest-edge limit (scales down only) |
| `upload.max_pixels` | `16000000` | Total pixel limit, the decompression-bomb guard. **This directly sets peak memory** — see below |
| `upload.jpeg_quality` | `90` | JPEG encoding quality |
| `upload.max_name_length` | `64` | Maximum generated file name length |
| `upload.max_nickname_length` | `32` | Maximum image nickname length |

> **How `upload.max_pixels` maps to memory:**
> peak ≈ `max_pixels x 4 bytes x limits.max_concurrent_uploads`. The defaults
> work out to roughly 64 MB per decode and 0.5 GB across 8 concurrent uploads.
> If you raise it, lower `max_concurrent_uploads` at the same time. Measured
> case: a **123 KB** solid-colour PNG (far below the 5 MB size limit) grew the
> heap by over a hundred MB. A small file does not mean a small decode.

### Limits

| Key | Default | Description |
| --- | --- | --- |
| `limits.per_player_daily` | `20` | Uploads per player per day |
| `limits.global_pending_max` | `200` | Pending queue cap; uploads are refused once it is full |
| `limits.max_concurrent_uploads` | `8` | Simultaneous uploads, `0` for unlimited |
| `limits.newcode_per_ip_per_hour` | `30` | Upload codes issued per IP per hour |
| `limits.login_per_ip_per_hour` | `10` | Failed admin logins allowed per IP per hour |

> The login limit counts **only failures**, so normal sign-ins and sign-outs are
> never blocked. While an IP is limited, even the correct password is refused.

### Other

| Key | Default | Description |
| --- | --- | --- |
| `language` | `zh` | Default language |
| `messages_dir` | `messages` | Language file directory |
| `site.allow_rename` | `true` | Let players rename their own pending uploads |
| `code_ttl_seconds` | `300` | Upload code lifetime |
| `require_permission` | `false` | Whether uploading requires the `imgup.use` permission |
| `notify_player` | `true` | Send moderation results to the player in game |

> `web.icp_license` follows mainland China's filing rules: **when empty the
> footer line does not appear at all**. Once set it is displayed and clickable
> (styled to look like ordinary footer text, by convention).

---

## Language files

Every user-visible string — page text, error messages, status labels, and the
messages players see in chat — lives in `plugins/ImgUp/messages/<language>.yml`.
Chinese and English ship built in.

- **Change wording**: edit the value, then `/imgup reload`.
- **Add a language**: copy `zh.yml` to `jp.yml` and translate the values. The
  language switcher in the top-right corner picks it up automatically, using the
  `meta.name` value from the file as its label.
- **In-game messages** follow the player's own client language.
- Backticks in a language file mark inline code and render as `<code>` on the
  page:
  ```yaml
  site.footer_note: "Server: `{motd}`. Posting images needs the `images.command.create` permission."
  ```

**Upgrading never overwrites values you changed.** A plugin upgrade only adds
newly introduced keys; existing translations are left alone. And if a reload
fails (an unreadable directory, say), the previous working copy is kept rather
than turning the whole site into raw key names.

---

## Security

This site is **public by default**, so a few notes:

- **Pending and rejected images are not public.** `/preview/<file name>` is only
  served to an admin or to **the player who uploaded that image**; everyone else
  gets a 404. Only published images are public.
- **The admin cookie carries `HttpOnly` and `SameSite=Lax`**, the password
  comparison is constant-time, and failed logins are rate-limited per IP.
- **Upload codes must be confirmed in game** and expire after 5 minutes, so the
  player name submitted from the web cannot be forged.
- **Connections cannot be parked forever.** The request body has an idle
  timeout, and the number of simultaneous uploads is capped — excess requests get
  an immediate "try again later" instead of several MB being accepted and queued.
- **Every upload is re-encoded**, so metadata (including EXIF) is discarded.

If you manually set the admin password to something weak, remember that
`limits.login_per_ip_per_hour` is your only backstop.

---

## Building from source

No Maven or Gradle — just the JDK's `javac` / `jar` and Python 3:

```bash
bash build.sh
```

The artifact lands in `target/ImgUp-<version>-<short git hash>.jar`.

The first run uses `fetch-deps.py` to download the Paper API and its compile
dependencies into `.build-cache/`; after that it builds offline. Set the
`IMGUP_BUILD_CACHE` environment variable to move the cache.

Besides compiling and packaging, `build.sh` runs a batch of artifact checks and
fails outright if any of them does not pass. They all exist because something
broke in exactly that way once, for example:

- language files agree on their keys, have no dead keys, and no untranslated
  Chinese in the non-Chinese files
- no hard-coded visible Chinese in the templates (it leaks into the English UI)
- every `$msg_` placeholder and `/static/` asset referenced by a page exists
- every asset extension under `web/` has a matching `Content-Type` branch
- config and code agree in both directions (no dead config entries, and no key
  the code reads that is missing from `config.yml`)
- SVG assets are well-formed XML

## Tests

```bash
bash test.sh
```

Three suites:

| Suite | Items | Coverage |
| --- | --- | --- |
| `CoreSelfTest` | 128 | Pure logic: multipart parsing, image processing, file naming, sessions, message rendering, reload behaviour |
| `HttpSelfTest` | 229 | A real HTTP server running the full get-code -> confirm -> upload -> result flow, including permissions and rate limits |
| `ConfigElementsTest` | all | Page elements really do come from the language files, so config changes take effect |

---

## Implementation notes

**The HTTP server is the JDK's own `com.sun.net.httpserver`** with a virtual
thread executor. No network framework is involved, so the artifact is a single
jar with no runtime dependencies beyond the server itself.

**Uploaded images are written straight into the Images plugin's data directory**,
because that is the only directory `/image create` looks at. The moderation index
belongs to this plugin, and losing it does not affect already-published images
(the game only checks whether the file exists). At startup, files present on disk
but missing from the index are registered back into it.

**The site only listens on the port you configure.** With `web.enabled: false`
no port is opened at all.

---

## License

[GNU General Public License v3.0](LICENSE)
