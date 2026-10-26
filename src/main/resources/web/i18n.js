/* ============================================================================
 * 图片上传站 · 中英文双语
 * ============================================================================
 *
 * 设计原则（很重要，改之前先读）：
 *
 *   1. 每段文案的中文原文**直接写在 HTML 标签里**，标签上带一个 data-i18n="键"。
 *      切到英文时用字典覆盖 textContent；切回中文时把原始中文还回去。
 *      这样即使 JS 没跑起来，页面也是完整可读的中文，不会出现空白或键名。
 *
 *   2. 需要在 placeholder / title / aria-label 上翻译的，用
 *      data-i18n-attr="placeholder:键,title:另一个键"。
 *
 *   3. JS 里运行时拼出来的句子**不要写死**，一律走 t('键')。
 *      需要插值就用 {name} 占位：t('index.countdown', { time: '01:23' })。
 *
 *   4. 服务端只在出错时多返回一个语言中立的 message_key，
 *      页面优先用 t(message_key)，拿不到才回退到服务端的中文 message。
 *      这样加语言不用动 Python。
 *
 * 新增文案的步骤：HTML 里写中文 + data-i18n 键 → 在 DICT 的 zh/en 各补一条
 * → 跑 tools/check-i18n.py 确认两边键一致、模板里的键都有定义。
 */
(function () {
  'use strict';

  var STORAGE_KEY = 'imgup_lang';
  var COOKIE_KEY = 'imgup_lang';
  var SUPPORTED = ['zh', 'en'];
  var FALLBACK = 'zh';

  /* ------------------------------------------------------------- 字典 -- */

  var DICT = {
    zh: {
      'common.cancel': '取消',
      'site.admin_colActions': '操作',
      'site.admin_colFile': '文件',
      'site.admin_colPlayer': '玩家',
      'site.admin_deleteConfirm': '确定删除「{name}」吗？文件会从服务器上删掉，已经用了这张图的地图会变成空白。',
      'site.admin_deleteConfirmTitle': '删除图片',
      'site.admin_rejectConfirm': '确定拒绝「{name}」吗？图片会挪到 rejected 目录，玩家可以重新上传。',
      'site.admin_rejectConfirmTitle': '拒绝图片',
      'site.admin_viewGrid': '网格',
      'site.admin_viewList': '列表',
      /* ---- 这个语言自己的名字 ---- */
      'meta.name': '中文（简体）',
      /* ---- 网站元素（页面上显示给玩家的文字） ---- */
      'site.title_suffix': '-图片上传站',
      'site.og_description': '在网页上传图片，在游戏里用地图把它贴出来。',
      'site.expired_reason': '超过保留期限仍未被审核，已自动作废。',
      'site.home_heading': '上传图片',
      'site.home_lede': '先在网页上获取一个 8 位上传码，再进游戏输入 `/imgup 你的上传码` 完成确认，就可以上传图片了。',
      'site.home_first_step_title': '第一步 · 获取上传码',
      'site.home_second_step_title': '备用方式 · 手动输入上传码',
      'site.home_button': '获取上传码',
      'site.upload_heading': '上传图片',
      'site.upload_lede': '选一张截图或海报，上传后会生成游戏内可用的图片名。',
      'site.upload_button': '上传',
      'site.upload_nickname_title': '图片昵称',
      'site.result_heading': '上传完成',
      'site.result_rename_title': '不满意这个名字？现在还能改',
      'site.result_rename_button': '保存新名字',
      'site.footer_note': '服务器：`{motd}`，游戏内贴海报需要 `images.command.create` 权限，没有权限请找管理员。',
      'site.footer_plugin': '本插件 {name} {version} · 开源地址',
      'site.footer_icp': '备案号：{number}',
      'site.upload_footer': '上传成功后请复制游戏内指令 /image create 文件名，手持地图按提示右键放置。',
      'site.nav_reupload': '⬆️ 再传一张',
      'site.nav_reverify': '重新验证',
      'site.nav_admin': '管理员入口',
      'site.footer_copyright': 'Copyright 2026 {server} · 最终解释权归本站所有',
      'site.admin_heading': '图片管理',
      'site.admin_lede': '这里可以查看、通过、拒绝和删除玩家上传的所有图片。',
      'site.admin_login_title': '管理员登录',
      'site.admin_login_hint': '输入 config.yml 里 web.admin_password 设置的密码。',
      'site.admin_password_label': '管理密码',
      'site.admin_login_button': '登录',
      'site.admin_logout_button': '退出登录',
      'site.admin_empty': '还没有任何图片。',
      'site.admin_loading': '加载中…',
      'site.admin_filter_all': '全部',
      'site.admin_filter_pending': '待审核',
      'site.admin_filter_published': '已发布',
      'site.admin_filter_rejected': '已拒绝',
      'site.admin_approve': '通过',
      'site.admin_reject': '拒绝',
      'site.admin_delete': '删除',
      'site.admin_bulk_approve': '全部通过',
      'site.admin_stats': '共 {total} 张：待审核 {pending}，已发布 {published}，已拒绝 {rejected}',
      /* ---- 通用 ---- */
      'common.backHome': '🏠 返回首页',
      'common.uploadAnother': '⬆️ 再传一张',
      'common.langSwitchTitle': '切换语言',
      'common.copy': '📋 复制',
      'common.copied': '✅ 已复制',
      'common.copyManual': '⚠️ 请长按手动复制',
      'common.unknownPlayer': '玩家',
      'common.originalFileName': '原文件名',
      'common.netError': '网络错误，无法连接服务器，请稍后重试。',
      'common.badCsrf': '页面令牌已失效（bad_csrf），请刷新页面后重试。',
      'common.sessionExpired': '会话已失效，请重新获取上传码。',
      /* ---- 首页 · 获取上传码 ---- */
      'index.title': '获取上传码',
      'index.getHint': '点下面的按钮生成上传码，然后在游戏聊天框里输入 `/imgup 上传码`。上传码 5 分钟内有效，网页会自动检测你的确认。',
      'index.newCode': '🔑 获取上传码',
      'index.newCodeAgain': '🔑 重新获取上传码',
      'index.generating': '生成中…',
      'index.yourCode': '你的上传码（点击可复制）',
      'index.codeBoxTitle': '点击复制上传码',
      'index.gameCommand': '在游戏里输入的指令是：',
      'index.step2Hint': '如果自动检测没有反应，可以在游戏里确认之后，把同一个上传码填到这里手动进入。',
      'index.codeLabel': '8 位上传码',
      'index.codeHint': '不区分大小写，会自动去掉空格；不含容易看错的 I、O、0、1。',
      'index.claimBtn': '我已确认，进入上传',
      'index.claiming': '验证中…',
      'index.privacyNote': '上传码只用于证明「这个浏览器和这个游戏 ID 是同一个人」，不会要求你输入游戏密码。',
      'index.expired': '已过期',
      'index.codeExpired': '上传码已过期。',
      'index.codeExpiredRetry': '上传码已过期，请重新获取。',
      'index.countdown': '剩余 {time}',
      'index.waiting': '等待你在游戏里确认…（每 2 秒自动检查一次）',
      'index.claimedWho': '已确认：{player}',
      'index.claimed': '已确认',
      'index.claimedRedirectWho': '已确认：{player}，正在进入上传页…',
      'index.claimedRedirect': '已确认，正在进入上传页…',
      'index.pollFailed': '无法查询确认状态，请重新获取上传码。',
      'index.netUnstable': '网络不稳定，正在重试…',
      'index.copiedCode': '已复制上传码：{code}',
      'index.copyFailed': '复制失败，请长按上面的码手动复制。',
      'index.genFailed': '生成上传码失败（HTTP {status}），请稍后重试。',
      'index.badCodeLength': '上传码是 8 位字符，请检查后重试。',
      'index.claimFailed': '验证失败，请重新获取上传码。',
      /* ---- 上传页 ---- */
      'upload.title': '上传图片',
      'upload.chooseTitle': '选择图片',
      'upload.dropTitle': '点击选择图片',
      'upload.dropTitleChosen': '已选择图片，点击可更换',
      'upload.dropHint': '也可以把图片拖到这里，或直接粘贴截图',
      'upload.fileInfo': '{size} · {kind} · 会自动缩放',
      'upload.untitled': '(未命名)',
      'upload.pickAgain': '点击上面的区域可以重新选择图片。',
      'upload.limits': '限制：PNG / JPG，单张不超过 5MB，会自动缩放。',
      'upload.squareWarn': '这张图不是正方形（{size}），贴到地图上会被拉伸。建议先裁成正方形再上传。',
      'upload.squareHint': 'Minecraft 地图是正方形的。非正方形的图贴上去会被拉伸或留白，建议先裁成正方形再上传。',
      'upload.nicknameLabel': '昵称',
      'upload.nicknamePlaceholder': '例如：我的海报',
      'upload.nicknameHint': '选好图片后会自动填成原文件名，你可以直接改成想要的名字。',
      'upload.namePreviewLabel': '游戏内的文件名',
      'upload.namePreviewHint': '由「玩家名-昵称」自动生成；如果已经有同名的图，会自动加 `-2`。上传后还可以再改。',
      'upload.namePreviewNone': '先选择一张图片',
      'upload.submit': '上传',
      'upload.uploading': '上传中…',
      'upload.progress': '已上传 {pct}%',
      'upload.progressPrep': '准备上传…',
      'upload.progressStart': '正在上传… 0%',
      'upload.progressProcessing': '服务器正在处理图片…',
      'upload.progressDone': '上传完成，正在跳转…',
      'upload.note': '服务端会再次校验图片格式与大小，并按站长设置决定是否需要人工审核。',
      'upload.errNoFile': '请先选择一张图片。',
      'upload.errType': '只支持 PNG / JPG 图片，当前文件是「{type}」。',
      'upload.unknownType': '未知类型',
      'upload.errEmpty': '这个文件是空的，请重新选择。',
      'upload.errTooBig': '图片太大了（{size}），请压缩到 5MB 以内再上传。',
      'upload.errFailed': '上传失败（HTTP {status}）',
      'upload.errExpired': '验证已过期，请回到首页重新获取上传码。',
      'upload.errCancelled': '上传已取消。',
      'upload.verifiedAs': '已通过验证：',
      'upload.previewAlt': '待上传图片预览',
      /* ---- 结果页 ---- */
      'result.title': '上传成功',
      'result.statusDefault': '图片已处理完成。',
      'result.previewTitle': '图片预览',
      'result.uploader': '上传玩家：',
      'result.fileNameTitle': '文件名',
      'result.copyName': '📋 复制文件名',
      'result.fileNameNote': '图片以这个完整文件名保存在服务器上，游戏插件按完整文件名匹配。',
      'result.renameLabel': '图片昵称',
      'result.renamePlaceholder': '例如：我的海报',
      'result.renamePreviewPrefix': '最终文件名会是 ',
      'result.renameSave': '✏️ 保存新名字',
      'result.renameSaving': '保存中…',
      'result.renameNote': '审核通过之后就改不了了 —— 那时候名字已经进了游戏世界，改了会让已经贴出去的海报找不到图。',
      'result.renameLocked': '这张图已经发布，名字不能再改了。',
      'result.useInGame': '在游戏里使用',
      'result.copyCommand': '📋 复制指令',
      'result.step1': '点上面的按钮复制指令。',
      'result.step2': '回到游戏，在聊天框里粘贴并发送（PC 端 `Ctrl+V`，手机端长按输入框粘贴）。',
      'result.step3': '手持地图按游戏内的提示右键放置海报。',
      'result.permissionNote': '贴海报还需要你在游戏里拥有 `images.command.create` 权限；如果提示没有权限，请联系站长。',
      'result.footerFormat': '文件名格式：`游戏ID-图片昵称.png`；重名会自动加 `-2`，总长不超过 64 个字符。',
      'result.thumbAlt': '已上传的图片：{name}',
      'result.renameTaken': '这个名字已经被别的图片用了，换一个吧。',
      'result.renameBadName': '这个名字不能用，换一个吧。',
      'result.renameEmpty': '昵称不能是空的。',
      'result.renameFailed': '改名失败（HTTP {status}）',
      'result.renameNetError': '网络错误，改名失败，请稍后重试。',
      'result.thumbAltPlain': '已上传的图片',
      /* ---- 状态句（服务端只给键，文案在这里） ---- */
      'status.published': '已发布，可以直接在游戏里贴了。',
      'status.pending': '已提交，等待站长审核。审核通过后就能在游戏里贴了。',
      'status.rejected': '这张图没有通过审核。',
      'status.rejected_reason': '这张图没有通过审核（原因：{reason}）。',
      'status.unknown': '状态未知。',
      'status.renamed': '名字已更新。',
      /* ---- 错误提示（键名与错误码对应） ---- */
      'err.bad_request': '请求格式不对。',
      'err.bad_code': '上传码格式不对，应该是 8 位字符。',
      'err.bad_name': '文件名不合法。',
      'err.bad_nickname': '昵称不合法。',
      'err.bad_password': '密码不对。',
      'err.too_many_attempts': '登录尝试次数太多，请过一会儿再试。',
      'err.no_session': '还没有上传会话，请先获取上传码。',
      'err.not_verified': '还没在游戏里确认上传码。',
      'err.unauthorized': '请先登录。',
      'err.not_found': '找不到这个内容。',
      'err.not_confirmed': '这个上传码还没有在游戏里确认过，请先在游戏聊天框输入 /imgup 上传码。',
      'err.not_owner': '这不是你上传的图片。',
      'err.not_renamable': '只有待审核的图片可以改名；已经发布的图片改了名，游戏里就找不到了。',
      'err.name_taken': '已经有叫这个名字的图片了，换一个昵称吧。',
      'err.no_file': '没有收到文件，请选择一张图片。',
      'err.too_large': '图片太大了，请压缩后再上传。',
      'err.rate_limited': '你今天已经上传了 {daily} 张，明天再来吧。',
      'err.queue_full': '待审核的图片已经有 {max} 张，等站长处理完再来吧。',
      'err.busy': '服务器正忙，请稍后重试。',
      'err.storage_error': '保存失败，请稍后重试。',
      'err.template_missing': '页面模板缺失，请联系站长。',
      'err.internal': '服务器内部错误，请稍后重试。',
      /* ---- 站长后台 ---- */
      'admin.title': '站长后台',
    },

    en: {
      'common.cancel': 'Cancel',
      'site.admin_colActions': 'Actions',
      'site.admin_colFile': 'File',
      'site.admin_colPlayer': 'Player',
      'site.admin_deleteConfirm': 'Delete "{name}"? The file is removed from the server and any map already using this image will go blank.',
      'site.admin_deleteConfirmTitle': 'Delete image',
      'site.admin_rejectConfirm': 'Reject "{name}"? The image is moved to the rejected folder and the player can upload it again.',
      'site.admin_rejectConfirmTitle': 'Reject image',
      'site.admin_viewGrid': 'Grid',
      'site.admin_viewList': 'List',
      /* ---- This language's own name ---- */
      'meta.name': 'English',
      /* ---- Site elements (text shown to players) ---- */
      'site.title_suffix': '-Image Upload',
      'site.og_description': 'Upload an image on the web, then place it on a map in game.',
      'site.expired_reason': 'Not reviewed within the retention period, so it expired automatically.',
      'site.home_heading': 'Upload an image',
      'site.home_lede': 'Get an 8-character upload code here, then type `/imgup <your code>` in game to confirm. After that you can upload images.',
      'site.home_first_step_title': 'Step 1 · Get an upload code',
      'site.home_second_step_title': 'Alternative: enter the code manually',
      'site.home_button': 'Get upload code',
      'site.upload_heading': 'Upload an image',
      'site.upload_lede': 'Pick a screenshot or poster. After uploading you get a file name that works in game.',
      'site.upload_button': 'Upload',
      'site.upload_nickname_title': 'Image nickname',
      'site.result_heading': 'Upload complete',
      'site.result_rename_title': 'Not happy with this name? You can still change it',
      'site.result_rename_button': 'Save new name',
      'site.footer_note': 'Server: `{motd}`. Posting in game requires the `images.command.create` permission — ask your admin if you do not have it.',
      'site.footer_plugin': 'Plugin {name} {version} · source',
      'site.footer_icp': 'ICP filing: {number}',
      'site.upload_footer': 'After uploading, copy the in-game command /image create <file name> and right-click while holding a map to place it.',
      'site.nav_reupload': '⬆️ Upload another',
      'site.nav_reverify': 'Verify again',
      'site.nav_admin': 'Admin',
      'site.footer_copyright': 'Copyright 2026 {server} · All rights reserved',
      'site.admin_heading': 'Image management',
      'site.admin_lede': 'Review, approve, reject and delete every image players have uploaded.',
      'site.admin_login_title': 'Admin login',
      'site.admin_login_hint': 'Enter the password set in config.yml under web.admin_password.',
      'site.admin_password_label': 'Admin password',
      'site.admin_login_button': 'Log in',
      'site.admin_logout_button': 'Log out',
      'site.admin_empty': 'No images yet.',
      'site.admin_loading': 'Loading…',
      'site.admin_filter_all': 'All',
      'site.admin_filter_pending': 'Pending',
      'site.admin_filter_published': 'Published',
      'site.admin_filter_rejected': 'Rejected',
      'site.admin_approve': 'Approve',
      'site.admin_reject': 'Reject',
      'site.admin_delete': 'Delete',
      'site.admin_bulk_approve': 'Approve all',
      'site.admin_stats': '{total} total: {pending} pending, {published} published, {rejected} rejected',
      /* ---- Common ---- */
      'common.backHome': '🏠 Home',
      'common.uploadAnother': '⬆️ Upload another',
      'common.langSwitchTitle': 'Switch language',
      'common.copy': '📋 Copy',
      'common.copied': '✅ Copied',
      'common.copyManual': '⚠️ Long-press to copy',
      'common.unknownPlayer': 'Player',
      'common.originalFileName': 'original file name',
      'common.netError': 'Network error — could not reach the server. Please try again.',
      'common.badCsrf': 'Your page token expired (bad_csrf). Please refresh and try again.',
      'common.sessionExpired': 'Your session expired. Please get a new upload code.',
      /* ---- Home · get an upload code ---- */
      'index.title': 'Get upload code',
      'index.getHint': 'Click the button below to generate a code, then type `/imgup <code>` in the in-game chat. The code is valid for 5 minutes and this page detects your confirmation automatically.',
      'index.newCode': '🔑 Get upload code',
      'index.newCodeAgain': '🔑 Get a new code',
      'index.generating': 'Generating…',
      'index.yourCode': 'Your upload code (click to copy)',
      'index.codeBoxTitle': 'Click to copy the upload code',
      'index.gameCommand': 'Type this in game:',
      'index.step2Hint': 'If automatic detection does not work, confirm in game first and then enter the same code here.',
      'index.codeLabel': '8-character upload code',
      'index.codeHint': 'Case-insensitive, spaces are removed automatically. The ambiguous characters I, O, 0 and 1 are never used.',
      'index.claimBtn': 'I have confirmed — continue',
      'index.claiming': 'Checking…',
      'index.privacyNote': 'The upload code only proves that this browser and that game ID belong to the same person. You will never be asked for your game password.',
      'index.expired': 'Expired',
      'index.codeExpired': 'The upload code has expired.',
      'index.codeExpiredRetry': 'The upload code has expired. Please get a new one.',
      'index.countdown': '{time} left',
      'index.waiting': 'Waiting for you to confirm in game… (checked every 2 seconds)',
      'index.claimedWho': 'Confirmed: {player}',
      'index.claimed': 'Confirmed',
      'index.claimedRedirectWho': 'Confirmed: {player} — opening the upload page…',
      'index.claimedRedirect': 'Confirmed — opening the upload page…',
      'index.pollFailed': 'Could not check the confirmation status. Please get a new upload code.',
      'index.netUnstable': 'Network is unstable, retrying…',
      'index.copiedCode': 'Upload code copied: {code}',
      'index.copyFailed': 'Copy failed — long-press the code above to copy it manually.',
      'index.genFailed': 'Could not generate an upload code (HTTP {status}). Please try again later.',
      'index.badCodeLength': 'An upload code is 8 characters long. Please check and try again.',
      'index.claimFailed': 'Verification failed. Please get a new upload code.',
      /* ---- Upload page ---- */
      'upload.title': 'Upload an image',
      'upload.chooseTitle': 'Choose an image',
      'upload.dropTitle': 'Click to choose an image',
      'upload.dropTitleChosen': 'Image selected — click to change',
      'upload.dropHint': 'You can also drag an image here, or paste a screenshot',
      'upload.fileInfo': '{size} · {kind} · scaled automatically',
      'upload.untitled': '(unnamed)',
      'upload.pickAgain': 'Click the area above to choose a different image.',
      'upload.limits': 'Limits: PNG / JPG, up to 5MB each, resized automatically.',
      'upload.squareWarn': 'This image is not square ({size}); it will be stretched on the map. Cropping it square first is recommended.',
      'upload.squareHint': 'Minecraft maps are square. A non-square image will be stretched or letterboxed on the map, so cropping it square first is recommended.',
      'upload.nicknameLabel': 'Nickname',
      'upload.nicknamePlaceholder': 'e.g. my-poster',
      'upload.nicknameHint': 'Filled in automatically from the original file name — just edit it to whatever you like.',
      'upload.namePreviewLabel': 'File name in game',
      'upload.namePreviewHint': 'Built from “player-nickname”; if an image with that name already exists, `-2` is appended. You can still change it after uploading.',
      'upload.namePreviewNone': 'Choose an image first',
      'upload.submit': 'Upload',
      'upload.uploading': 'Uploading…',
      'upload.progress': 'Uploaded {pct}%',
      'upload.progressPrep': 'Preparing…',
      'upload.progressStart': 'Uploading… 0%',
      'upload.progressProcessing': 'The server is processing your image…',
      'upload.progressDone': 'Upload complete — redirecting…',
      'upload.note': 'The server re-checks the format and size, and the admin decides whether manual review is required.',
      'upload.errNoFile': 'Please choose an image first.',
      'upload.errType': 'Only PNG / JPG images are supported. This file is “{type}”.',
      'upload.unknownType': 'unknown type',
      'upload.errEmpty': 'This file is empty. Please choose another one.',
      'upload.errTooBig': 'The image is too large ({size}). Please compress it below 5MB and try again.',
      'upload.errFailed': 'Upload failed (HTTP {status})',
      'upload.errExpired': 'Your verification expired. Please go back home and get a new upload code.',
      'upload.errCancelled': 'Upload cancelled.',
      'upload.verifiedAs': 'Verified as:',
      'upload.previewAlt': 'Preview of the image to upload',
      /* ---- Result page ---- */
      'result.title': 'Upload complete',
      'result.statusDefault': 'Your image has been processed.',
      'result.previewTitle': 'Preview',
      'result.uploader': 'Uploaded by:',
      'result.fileNameTitle': 'File name',
      'result.copyName': '📋 Copy file name',
      'result.fileNameNote': 'The image is stored under this exact file name; the game plugin matches on the full name.',
      'result.renameLabel': 'Image nickname',
      'result.renamePlaceholder': 'e.g. my-poster',
      'result.renamePreviewPrefix': 'The final file name will be ',
      'result.renameSave': '✏️ Save new name',
      'result.renameSaving': 'Saving…',
      'result.renameNote': 'Once the admin approves it you can no longer rename it — the name is already part of the game world, and renaming would break posters that are already placed.',
      'result.renameLocked': 'This image is published, so its name can no longer be changed.',
      'result.useInGame': 'Use it in game',
      'result.copyCommand': '📋 Copy command',
      'result.step1': 'Click the button above to copy the command.',
      'result.step2': 'Back in game, paste it into the chat box and send (`Ctrl+V` on PC, long-press the input on mobile).',
      'result.step3': 'Hold a map and right-click as prompted in game to place the poster.',
      'result.permissionNote': 'Posting also requires the `images.command.create` permission in game; ask your admin if you are told you lack permission.',
      'result.footerFormat': 'File name format: `gameID-image-nickname.png`; duplicates get a `-2` suffix, at most 64 characters.',
      'result.thumbAlt': 'Uploaded image: {name}',
      'result.renameTaken': 'Another image already uses this name. Please pick a different one.',
      'result.renameBadName': 'That name cannot be used. Please pick a different one.',
      'result.renameEmpty': 'The nickname cannot be empty.',
      'result.renameFailed': 'Rename failed (HTTP {status})',
      'result.renameNetError': 'Network error — rename failed. Please try again later.',
      'result.thumbAltPlain': 'Uploaded image',
      /* ---- Status lines (the server sends the key, text lives here) ---- */
      'status.published': 'Published — you can post it in game now.',
      'status.pending': 'Submitted and waiting for the admin to review. Once approved you can post it in game.',
      'status.rejected': 'This image was not approved.',
      'status.rejected_reason': 'This image was not approved (reason: {reason}).',
      'status.unknown': 'Unknown status.',
      'status.renamed': 'Name updated.',
      /* ---- Errors (keys mirror the error codes) ---- */
      'err.bad_request': 'Malformed request.',
      'err.bad_code': 'An upload code is 8 characters long.',
      'err.bad_name': 'That file name is not valid.',
      'err.bad_nickname': 'That nickname is not valid.',
      'err.bad_password': 'Wrong password.',
      'err.too_many_attempts': 'Too many login attempts. Please try again later.',
      'err.no_session': 'No upload session yet — please get an upload code first.',
      'err.not_verified': 'The upload code has not been confirmed in game yet.',
      'err.unauthorized': 'Please log in first.',
      'err.not_found': 'Not found.',
      'err.not_confirmed': 'This upload code has not been confirmed in game yet — type /imgup <code> in the in-game chat first.',
      'err.not_owner': 'This image was not uploaded by you.',
      'err.not_renamable': 'Only pending images can be renamed; renaming a published image would break it in game.',
      'err.name_taken': 'An image with that name already exists. Please pick another nickname.',
      'err.no_file': 'No file received — please choose an image.',
      'err.too_large': 'The image is too large. Please compress it and try again.',
      'err.rate_limited': 'You have already uploaded {daily} images today. Come back tomorrow.',
      'err.queue_full': 'There are already {max} images awaiting review. Please try again after the admin clears the queue.',
      'err.busy': 'The server is busy. Please try again later.',
      'err.storage_error': 'Saving failed. Please try again later.',
      'err.template_missing': 'A page template is missing. Please contact the admin.',
      'err.internal': 'Internal server error. Please try again later.',
      /* ---- Admin page ---- */
      'admin.title': 'Admin',
    },
  };

  /* --------------------------------------------- 服务端注入的文案表 --
   *
   * 页面上所有文字的唯一来源是 messages/<语言>.yml。插件渲染页面时会把
   * **当前语言**的整张表塞进 window.IMGUP_MESSAGES（键名和 yml 里完全一致，
   * 比如 site.brand、upload.errTooBig），语言代码放在 window.IMGUP_LANG。
   *
   * 这里用它覆盖字典：
   *   · 覆盖当前语言那一张表 —— 于是 yml 里改一句，页面立刻跟着变
   *   · 同时用默认语言（window.IMGUP_DEFAULT）的表补齐缺的键
   *
   * 本文件里写死的字典是**离线兜底**：万一没注入（比如直接用浏览器打开
   * 磁盘上的 html），页面仍然是完整可读的，不会出现键名。
   */
  (function applyInjectedMessages() {
    var injected = window.IMGUP_MESSAGES;
    var lang = window.IMGUP_LANG;
    if (!injected || typeof injected !== 'object') { return; }
    if (typeof lang === 'string' && SUPPORTED.indexOf(lang) >= 0) {
      DICT[lang] = DICT[lang] || {};
      Object.keys(injected).forEach(function (key) {
        var value = injected[key];
        if (typeof value === 'string' && value !== '') {
          DICT[lang][key] = value;
        }
      });
    }
  })();

  /* ------------------------------------------------------------ 状态 -- */

  var docEl = document.documentElement;

  function fromUrl() {
    var m = (window.location.search || '').match(/[?&]lang=(zh|en)(?:&|$)/);
    return m ? m[1] : '';
  }

  function fromStorage() {
    try { return window.localStorage.getItem(STORAGE_KEY) || ''; } catch (e) { return ''; }
  }

  function fromCookie() {
    var m = document.cookie.match(/(?:^|;\s*)imgup_lang=(zh|en)/);
    return m ? m[1] : '';
  }

  function detect() {
    var lang = fromUrl() || fromStorage() || fromCookie();
    return SUPPORTED.indexOf(lang) >= 0 ? lang : FALLBACK;
  }

  var lang = detect();

  /* ------------------------------------------------------------ 工具 -- */

  /** 翻译：t('键') 或 t('键', { 占位名: 值 })。找不到就回退中文，再找不到返回原文。 */
  function t(key, params) {
    var table = DICT[lang] || DICT[FALLBACK];
    var text = table[key];
    if (text === undefined) { text = (DICT[FALLBACK] || {})[key]; }
    if (text === undefined) { text = key; }
    if (params) {
      text = String(text).replace(/\{(\w+)\}/g, function (whole, name) {
        return Object.prototype.hasOwnProperty.call(params, name) ? String(params[name]) : whole;
      });
    }
    return text;
  }

  /** 元素上原本的中文，切回中文时要还回去。 */
  var ORIGINAL = new WeakMap();
  var ATTR_ORIGINAL = new WeakMap();

  function remember(el) {
    if (!ORIGINAL.has(el)) { ORIGINAL.set(el, el.textContent); }
  }

  function rememberAttrs(el) {
    if (ATTR_ORIGINAL.has(el)) { return; }
    var spec = el.getAttribute('data-i18n-attr') || '';
    var snapshot = {};
    spec.split(',').forEach(function (pair) {
      var idx = pair.indexOf(':');
      if (idx < 0) { return; }
      var attr = pair.slice(0, idx).trim();
      if (attr) { snapshot[attr] = el.getAttribute(attr); }
    });
    ATTR_ORIGINAL.set(el, snapshot);
  }

  /* ------------------------------------------------- 应用 / 还原文案 -- */

  /** 读元素上的 data-i18n-params（服务端注入的 {reason} 之类插值值）。 */
  function paramsOf(el) {
    var raw = el.getAttribute('data-i18n-params');
    if (!raw) { return null; }
    try { return JSON.parse(raw); } catch (e) { return null; }
  }

  /**
   * 属性翻译的插值参数，按属性名分开取。
   * 用法：data-i18n-attr-params="alt:{&quot;name&quot;:&quot;Steve-海报.png&quot;}"
   * 用在 alt 这种「既要翻译又要插文件名」的属性上。
   */
  function attrParamsOf(el, attr) {
    var raw = el.getAttribute('data-i18n-attr-params');
    if (!raw) { return null; }
    // 值形如 alt:{"name":"x.png"}；多个属性用逗号分隔。
    // 用第一个冒号切开，剩下整体交给 JSON.parse（JSON 里的冒号不影响）。
    var parts = String(raw).split(',');
    for (var i = 0; i < parts.length; i++) {
      var piece = parts[i];
      var idx = piece.indexOf(':');
      if (idx < 0) { continue; }
      if (piece.slice(0, idx).trim() !== attr) { continue; }
      try {
        return JSON.parse(piece.slice(idx + 1).trim());
      } catch (e) {
        return null;
      }
    }
    return null;
  }

  function applyStatic() {
    var nodes = document.querySelectorAll('[data-i18n]');
    Array.prototype.forEach.call(nodes, function (el) {
      remember(el);
      var key = el.getAttribute('data-i18n');
      if (lang === FALLBACK) {
        // 中文：还回原文（原文里已经带着服务端插好的值）
        el.textContent = ORIGINAL.get(el);
      } else {
        el.textContent = t(key, paramsOf(el));
      }
    });

    var attrNodes = document.querySelectorAll('[data-i18n-attr]');
    Array.prototype.forEach.call(attrNodes, function (el) {
      rememberAttrs(el);
      var spec = el.getAttribute('data-i18n-attr') || '';
      var snapshot = ATTR_ORIGINAL.get(el) || {};
      spec.split(',').forEach(function (pair) {
        var idx = pair.indexOf(':');
        if (idx < 0) { return; }
        var attr = pair.slice(0, idx).trim();
        var key = pair.slice(idx + 1).trim();
        if (!attr || !key) { return; }
        var original = snapshot[attr];
        el.setAttribute(attr, lang === FALLBACK
          ? (original || '')
          : t(key, attrParamsOf(el, attr)));
      });
    });
  }

  function applyMeta() {
    docEl.setAttribute('lang', lang === 'en' ? 'en' : 'zh-CN');
    var titleEl = document.querySelector('title');
    if (titleEl) {
      if (!titleEl.hasAttribute('data-i18n-title')) {
        titleEl.setAttribute('data-i18n-title', titleEl.textContent);
      }
      var key = titleEl.getAttribute('data-i18n');
      if (lang === FALLBACK) {
        titleEl.textContent = titleEl.getAttribute('data-i18n-title');
      } else if (key) {
        titleEl.textContent = t(key);
      }
    }
  }

  /* ------------------------------------------------------- 语言开关 -- */
  //
  // 右上角一个地球图标，点开是下拉菜单：
  //   🌐  [ 中文（简体） ✓ ]
  //       [ English        ]
  //
  // 每个选项的文字来自该语言文件里的 meta.name —— 也就是「这门语言自己的叫法」，
  // 所以中文选项写「中文（简体）」、英文选项写「English」，不随界面语言变。
  // 加语言只需往 messages/ 里放一份新的 yml 并填好 meta.name，菜单自动多一项。

  /** 服务端有哪些语言：[{code, name}] */
  var AVAILABLE = [];
  /** 当前界面语言（服务端渲染时注入的 IMGUP_LANG） */
  var CURRENT = (typeof window.IMGUP_LANG === 'string' && window.IMGUP_LANG) || FALLBACK;
  /** 语言开关容器的 id，用来判断是否已经创建过 */
  var SWITCH_ID = 'imgupLangSwitch';

  /** 兜底：拿不到 /api/languages 时至少显示这两门 */
  var FALLBACK_NAMES = { zh: '中文（简体）', en: 'English' };

  function buildSwitcher() {
    // 放在导航最后 —— 页面是 RTL 友好布局时 .topnav 靠右，所以它在右上角
    var nav = document.querySelector('.topnav');
    // 用专门的 id 判断「是不是已经建过了」。
    // 早先用的是 nav.querySelector('.lang-switch')，结果后台那个按钮不小心
    // 也叫 lang-switch，于是后台页面永远建不出语言菜单。
    if (!nav || document.getElementById(SWITCH_ID)) { return; }

    var box = document.createElement('div');
    box.className = 'lang-switch';
    box.id = SWITCH_ID;

    var btn = document.createElement('button');
    btn.type = 'button';
    btn.className = 'lang-btn';
    btn.setAttribute('aria-haspopup', 'true');
    btn.setAttribute('aria-expanded', 'false');
    btn.setAttribute('data-i18n-attr', 'title:common.langSwitchTitle,aria-label:common.langSwitchTitle');

    var icon = document.createElement('span');
    icon.className = 'lang-icon';
    icon.setAttribute('aria-hidden', 'true');
    icon.textContent = '🌐';
    btn.appendChild(icon);

    var caret = document.createElement('span');
    caret.className = 'lang-caret';
    caret.setAttribute('aria-hidden', 'true');
    caret.textContent = '▾';
    btn.appendChild(caret);

    var menu = document.createElement('div');
    menu.className = 'lang-menu';
    menu.setAttribute('role', 'menu');
    menu.hidden = true;

    btn.addEventListener('click', function (event) {
      event.stopPropagation();
      if (menu.hidden) { openMenu(menu, btn); } else { closeMenu(menu, btn); }
    });

    // 点别处 / 按 Esc 关掉
    document.addEventListener('click', function () { closeMenu(menu, btn); });
    document.addEventListener('keydown', function (event) {
      if (event.key === 'Escape') { closeMenu(menu, btn); }
    });
    menu.addEventListener('click', function (event) { event.stopPropagation(); });

    box.appendChild(btn);
    box.appendChild(menu);
    nav.appendChild(box);

    // 先按兜底渲染一遍，接口回来后再用真实列表重画
    renderMenu(menu, btn);
    fetch('/api/languages', { headers: { 'Accept': 'application/json' } })
      .then(function (r) { return r.json(); })
      .then(function (data) {
        if (!data || !data.ok || !data.languages || !data.languages.length) { return; }
        AVAILABLE = data.languages;
        if (typeof data.current === 'string' && data.current) { CURRENT = data.current; }
        renderMenu(menu, btn);
      })
      .catch(function () { /* 拿不到就用兜底列表，菜单仍然是可用的 */ });
  }

  /** 可用语言列表：优先用接口给的，拿不到就把兜底的两门拼出来。 */
  function languageList() {
    if (AVAILABLE.length) { return AVAILABLE; }
    return Object.keys(FALLBACK_NAMES).map(function (code) {
      return { code: code, name: FALLBACK_NAMES[code] };
    });
  }

  function renderMenu(menu, btn) {
    menu.innerHTML = '';
    languageList().forEach(function (item) {
      var entry = document.createElement('button');
      entry.type = 'button';
      entry.className = 'lang-item';
      entry.setAttribute('role', 'menuitem');
      var isCurrent = item.code === CURRENT;
      if (isCurrent) { entry.classList.add('is-current'); }
      entry.setAttribute('lang', item.code);

      var text = document.createElement('span');
      // 注意：这里写的是「这门语言自己的叫法」，不是当前界面语言的翻译，
      // 所以不能挂 data-i18n —— 那会被翻译引擎按当前语言改写掉。
      text.textContent = item.name || item.code.toUpperCase();
      entry.appendChild(text);

      var tick = document.createElement('span');
      tick.className = 'lang-tick';
      tick.setAttribute('aria-hidden', 'true');
      tick.textContent = isCurrent ? '✓' : '';
      entry.appendChild(tick);

      entry.addEventListener('click', function () {
        closeMenu(menu, btn);
        if (!isCurrent) { switchTo(item.code); }
      });
      menu.appendChild(entry);
    });
  }

  function openMenu(menu, btn) {
    menu.hidden = false;
    btn.setAttribute('aria-expanded', 'true');
  }

  function closeMenu(menu, btn) {
    if (menu.hidden) { return; }
    menu.hidden = true;
    btn.setAttribute('aria-expanded', 'false');
  }

  /**
   * 切语言：先让服务端记住（写 cookie），再带 ?lang= 刷新页面。
   *
   * 之所以刷新而不是前端换文案：服务端会用新语言把整页重新渲染一遍，
   * 首屏就是对的，不会出现「先看到中文再跳成英文」的闪烁。
   */
  function switchTo(code) {
    fetch('/api/lang?lang=' + encodeURIComponent(code), { method: 'POST' })
      .catch(function () { /* 记不住也没关系，下面的 ?lang= 仍然生效 */ })
      .then(function () {
        var url = new URL(window.location.href);
        url.searchParams.set('lang', code);
        window.location.replace(url.toString());
      });
  }

  function persist(next) {
    // 语言选择以服务端 cookie 为准（/api/lang 负责写），这里只做本地冗余，
    // 万一 cookie 被清了，下次仍能记住。
    try { window.localStorage.setItem(STORAGE_KEY, next); } catch (e) { /* 隐私模式忽略 */ }
  }

  function setLang(next) {
    if (SUPPORTED.indexOf(next) < 0) { next = FALLBACK; }
    lang = next;
    persist(next);
    applyStatic();
    applyMeta();
    // 页面里运行时生成的文案（状态行、计数、按钮标签…）由各处监听重画。
    // 两条路都发：图片上传站自己的页面用 DOM 事件（最简单），
    // 需要更细粒度控制的可以走 onChange(fn)。
    try {
      window.dispatchEvent(new CustomEvent('imgup:languagechange', { detail: { lang: lang } }));
    } catch (e) { /* 很老的浏览器没有 CustomEvent，忽略即可 */ }
    if (listeners.length) {
      Array.prototype.forEach.call(listeners, function (fn) {
        try { fn(lang); } catch (e) { /* 单个监听器出错不影响其它 */ }
      });
    }
  }

  var listeners = [];
  function onChange(fn) {
    if (typeof fn === 'function') {
      listeners.push(fn);
      // 立即用当前语言跑一次，省得页面自己再调一遍
      try { fn(lang); } catch (e) { /* 忽略 */ }
    }
  }

  /* ------------------------------------------------------------ 导出 -- */

  window.IMGUP_I18N = {
    t: t,
    get lang() { return lang; },
    setLang: setLang,
    onChange: onChange,
    supported: SUPPORTED.slice()
  };

  // 这个脚本在 <head> 里同步加载，执行时 body 还没解析 ——
  // 直接调用会匹配到 0 个元素、.topnav 也是 null，等于初始化根本没跑。
  // 所以挂到 DOMContentLoaded；如果文档已经就绪（defer / 动态插入的情况）就直接跑。
  function boot() {
    buildSwitcher();
    applyStatic();
    applyMeta();
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', boot);
  } else {
    boot();
  }
})();
