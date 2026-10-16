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
      /* ---- 通用 ---- */
      'common.brand': '图片上传站',
      'common.adminEntry': '站长入口',
      'common.backHome': '🏠 返回首页',
      'common.uploadAnother': '⬆️ 再传一张',
      'common.footerTagline': '图片上传站 · 深色 Minecraft 风格 · 纯静态页面，不加载任何外部资源。',
      'common.footerPermBefore': '游戏内贴海报需要 ',
      'common.footerPermAfter': '$site_footer_permission_suffix',
      'common.langSwitchTitle': '切换到英文',
      'common.copy': '📋 复制',
      'common.copied': '✅ 已复制',
      'common.copyManual': '⚠️ 请长按手动复制',
      'common.netError': '网络错误，无法连接服务器，请稍后重试。',
      'common.badCsrf': '页面令牌已失效（bad_csrf），请刷新页面后重试。',
      'common.sessionExpired': '会话已失效，请重新获取上传码。',

      /* ---- 首页：获取上传码 ---- */
      'index.title': '获取上传码 · 图片上传站',
      'index.heading': '上传海报',
      'index.lede': '先在网页上获取一个 8 位上传码，再进游戏输入 ',
      'index.ledeSuffix': ' 完成确认，就可以上传图片了。',
      'index.step1Title': '第一步 · 获取上传码',
      'index.getHint': '点下面的按钮生成上传码，然后在游戏聊天框里输入 ',
      'index.getHintSuffix': '。上传码 5 分钟内有效，网页会自动检测你的确认。',
      'index.newCode': '🔑 获取上传码',
      'index.newCodeAgain': '🔑 重新获取上传码',
      'index.generating': '生成中…',
      'index.yourCode': '你的上传码（点击可复制）',
      'index.codeBoxTitle': '点击复制上传码',
      'index.gameCommand': '在游戏里输入的指令是：',
      'index.step2Title': '备用方式 · 手动输入上传码',
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
      'upload.title': '上传图片 · 图片上传站',
      'upload.heading': '上传图片',
      'upload.lede': '选一张截图或海报，上传后会生成游戏内可用的图片名。',
      'upload.chooseTitle': '选择图片',
      'upload.dropTitle': '点击选择图片',
      'upload.dropTitleChosen': '已选择图片，点击可更换',
      'upload.dropHint': '也可以把图片拖到这里，或直接粘贴截图',
      'upload.pickAgain': '点击上面的区域可以重新选择图片。',
      'upload.limits': '限制：PNG / JPG，单张不超过 5MB，会自动缩放。',
      'upload.nicknameTitle': '图片昵称',
      'upload.nicknameLabel': '昵称',
      'upload.nicknamePlaceholder': '例如：我的海报',
      'upload.nicknameHint': '选好图片后会自动填成原文件名，你可以直接改成想要的名字。',
      'upload.namePreviewLabel': '游戏内的文件名',
      'upload.namePreviewHint': '由「玩家名-昵称」自动生成；如果已经有同名的图，会自动加 -2。上传后还可以再改。',
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
      'upload.errEmpty': '这个文件是空的，请重新选择。',
      'upload.errTooBig': '图片太大了（{size}），请压缩到 5MB 以内再上传。',
      'upload.errFailed': '上传失败（HTTP {status}）',
      'upload.errExpired': '验证已过期，请回到首页重新获取上传码。',
      'upload.errCancelled': '上传已取消。',

      /* ---- 结果页 ---- */
      'result.title': '上传成功 · 图片上传站',
      'result.heading': '上传完成',
      'result.statusDefault': '图片已处理完成。',
      'result.previewTitle': '图片预览',
      'result.uploader': '上传玩家：',
      'result.fileNameTitle': '文件名',
      'result.copyName': '📋 复制文件名',
      'result.fileNameNote': '图片以这个完整文件名保存在服务器上，游戏插件按完整文件名匹配。',
      'result.renameTitle': '不满意这个名字？现在还能改',
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
      'result.step2': '回到游戏，在聊天框里粘贴并发送（PC 端 Ctrl+V，手机端长按输入框粘贴）。',
      'result.step3': '手持地图按游戏内的提示右键放置海报。',
      'result.permissionNote': '贴海报还需要你在游戏里拥有 images.command.create 权限；如果提示没有权限，请联系站长。',
      'result.footerFormat': '文件名格式：游戏ID-图片昵称.png；重名会自动加 -2，总长不超过 64 个字符。',
      'result.thumbAlt': '已上传的图片：{name}',
      'result.renameTaken': '这个名字已经被别的图片用了，换一个吧。',
      'result.renameBadName': '这个名字不能用，换一个吧。',
      'result.renameEmpty': '昵称不能是空的。',
      'result.renameFailed': '改名失败（HTTP {status}）',
      'result.renameNetError': '网络错误，改名失败，请稍后重试。',

      /* ---- 后台（站长用，默认中文）---- */
      'admin.title': '站长后台 · 图片上传站',
      'admin.heading': '站长后台',
      'admin.login': '登录',
      'admin.password': '管理密码',
      'admin.logout': '退出登录',
      'admin.filterAll': '全部',
      'admin.filterPending': '待审核',
      'admin.filterPublished': '已发布',
      'admin.filterRejected': '已拒绝',
      'admin.approve': '通过',
      'admin.reject': '拒绝',
      'admin.delete': '删除',
      'admin.rejectReason': '拒绝原因（会私聊通知玩家）',
      'admin.expired': '登录已过期，请重新登录。',
      'admin.confirmReject': '确定拒绝「{name}」吗？图片会被移入 _rejected/ 目录。',
      'admin.previewFailed': '无法预览',

      /* ---- 错误提示（键名与 app/core/app.py 的 ERROR_KEY 对应）---- */
      'err.bad_player': '游戏 ID 不合法。',
      'err.bad_csrf': '安全校验失败，请刷新页面后重试。',
      'err.bad_request': '请求格式不对。',
      'err.bad_code': '上传码格式不对，应该是 8 位字符。',
      'err.bad_name': '文件名不合法。',
      'err.bad_nickname': '昵称不合法。',
      'err.bad_password': '密码不对。',
      'err.no_session': '还没有上传会话，请先获取上传码。',
      'err.not_verified': '还没在游戏里确认上传码。',
      'err.unauthorized': '请先登录。',
      'err.not_found': '找不到这个内容。',
      'err.not_owner': '这不是你上传的图片。',
      'err.not_renamable': '只有待审核的图片可以改名；已经发布的图片改了名，游戏里就找不到了。',
      'err.name_taken': '已经有叫这个名字的图片了，换一个昵称吧。',
      'err.no_file': '没有收到文件，请选择一张图片。',
      'err.too_large': '图片太大了，请压缩后再上传。',
      'err.bad_image': '这个文件不是有效的 PNG / JPG 图片。',
      'err.animated_not_allowed': '不支持动图（GIF），请换成静态的 PNG 或 JPG。',
      'err.pdf_not_allowed': '不支持 PDF，请上传 PNG 或 JPG。',
      'err.image_too_big': '图片尺寸太大了，请先缩小再上传。',
      'err.plugin_missing': '服务端没有加载 ImgUp 插件，或插件还没启用，无法生成上传码。',
      'err.plugin_denied': '插件拒绝了请求，请检查两侧的 shared_secret 是否一致。',
      'err.rate_limited': '你今天已经上传了 {daily} 张，明天再来吧。',
      'err.too_many_codes': '获取上传码太频繁了，请过一会儿再试。',
      'err.queue_full': '待审核的图片已经有 {max} 张，等站长处理完再来吧。',
      'err.busy': '服务器正忙，请稍后重试。',
      'err.storage_error': '保存失败，请稍后重试。',
      'err.template_missing': '页面模板缺失，请联系站长。',
      'err.internal': '服务器内部错误，请稍后重试。',
      'err.timeout': '请求超时，请重试。',

      /* ---- 结果页状态句（服务端只给键和插值）---- */
      'status.published': '已发布，可以直接在游戏里贴了。',
      'status.pending': '已提交，等待站长审核。审核通过后就能在游戏里贴了。',
      'status.rejected': '这张图没有通过审核。',
      'status.rejected_reason': '这张图没有通过审核（原因：{reason}）。',
      'status.unknown': '状态未知。',
      'status.renamed': '名字已更新。',
    },

    en: {
      /* ---- Common ---- */
      'common.brand': 'Image Upload',
      'common.adminEntry': 'Admin',
      'common.backHome': '🏠 Home',
      'common.uploadAnother': '⬆️ Upload another',
      'common.footerTagline': 'Image Upload · dark Minecraft style · fully self-contained, no external assets.',
      'common.footerPermBefore': 'Posting in game requires the ',
      'common.footerPermAfter': ' permission — ask your admin if you do not have it.',
      'common.langSwitchTitle': 'Switch to Chinese',
      'common.copy': '📋 Copy',
      'common.copied': '✅ Copied',
      'common.copyManual': '⚠️ Long-press to copy',
      'common.netError': 'Network error — could not reach the server. Please try again.',
      'common.badCsrf': 'Your page token expired (bad_csrf). Please refresh and try again.',
      'common.sessionExpired': 'Your session expired. Please get a new upload code.',

      /* ---- Home: get an upload code ---- */
      'index.title': 'Get upload code · Image Upload',
      'index.heading': 'Upload a poster',
      'index.lede': 'Get an 8-character upload code here, then type ',
      'index.ledeSuffix': ' in game to confirm. After that you can upload images.',
      'index.step1Title': 'Step 1 · Get an upload code',
      'index.getHint': 'Click the button below to generate a code, then type ',
      'index.getHintSuffix': ' in the in-game chat. The code is valid for 5 minutes and this page detects your confirmation automatically.',
      'index.newCode': '🔑 Get upload code',
      'index.newCodeAgain': '🔑 Get a new code',
      'index.generating': 'Generating…',
      'index.yourCode': 'Your upload code (click to copy)',
      'index.codeBoxTitle': 'Click to copy the upload code',
      'index.gameCommand': 'Type this in game:',
      'index.step2Title': 'Fallback · enter the code manually',
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
      'upload.title': 'Upload an image · Image Upload',
      'upload.heading': 'Upload an image',
      'upload.lede': 'Pick a screenshot or poster. After uploading you get a file name that works in game.',
      'upload.chooseTitle': 'Choose an image',
      'upload.dropTitle': 'Click to choose an image',
      'upload.dropTitleChosen': 'Image selected — click to change',
      'upload.dropHint': 'You can also drag an image here, or paste a screenshot',
      'upload.pickAgain': 'Click the area above to choose a different image.',
      'upload.limits': 'Limits: PNG / JPG, up to 5MB each, resized automatically.',
      'upload.nicknameTitle': 'Image nickname',
      'upload.nicknameLabel': 'Nickname',
      'upload.nicknamePlaceholder': 'e.g. my-poster',
      'upload.nicknameHint': 'Filled in automatically from the original file name — just edit it to whatever you like.',
      'upload.namePreviewLabel': 'File name in game',
      'upload.namePreviewHint': 'Built from “player-nickname”; if an image with that name already exists, -2 is appended. You can still change it after uploading.',
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
      'upload.errEmpty': 'This file is empty. Please choose another one.',
      'upload.errTooBig': 'The image is too large ({size}). Please compress it below 5MB and try again.',
      'upload.errFailed': 'Upload failed (HTTP {status})',
      'upload.errExpired': 'Your verification expired. Please go back home and get a new upload code.',
      'upload.errCancelled': 'Upload cancelled.',

      /* ---- Result page ---- */
      'result.title': 'Upload complete · Image Upload',
      'result.heading': 'Upload complete',
      'result.statusDefault': 'Your image has been processed.',
      'result.previewTitle': 'Preview',
      'result.uploader': 'Uploaded by:',
      'result.fileNameTitle': 'File name',
      'result.copyName': '📋 Copy file name',
      'result.fileNameNote': 'The image is stored under this exact file name; the game plugin matches on the full name.',
      'result.renameTitle': 'Not happy with this name? You can still change it',
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
      'result.step2': 'Back in game, paste it into the chat box and send (Ctrl+V on PC, long-press the input on mobile).',
      'result.step3': 'Hold a map and right-click as prompted in game to place the poster.',
      'result.permissionNote': 'Posting also requires the images.command.create permission in game; ask your admin if you are told you lack permission.',
      'result.footerFormat': 'File name format: gameID-image-nickname.png; duplicates get a -2 suffix, at most 64 characters.',
      'result.thumbAlt': 'Uploaded image: {name}',
      'result.renameTaken': 'Another image already uses this name. Please pick a different one.',
      'result.renameBadName': 'That name cannot be used. Please pick a different one.',
      'result.renameEmpty': 'The nickname cannot be empty.',
      'result.renameFailed': 'Rename failed (HTTP {status})',
      'result.renameNetError': 'Network error — rename failed. Please try again later.',

      /* ---- Admin (used by the site owner) ---- */
      'admin.title': 'Admin · Image Upload',
      'admin.heading': 'Admin',
      'admin.login': 'Log in',
      'admin.password': 'Admin password',
      'admin.logout': 'Log out',
      'admin.filterAll': 'All',
      'admin.filterPending': 'Pending',
      'admin.filterPublished': 'Published',
      'admin.filterRejected': 'Rejected',
      'admin.approve': 'Approve',
      'admin.reject': 'Reject',
      'admin.delete': 'Delete',
      'admin.rejectReason': 'Rejection reason (the player is notified in game)',
      'admin.expired': 'Your login expired. Please log in again.',
      'admin.confirmReject': 'Reject “{name}”? The image will be moved to the _rejected/ folder.',
      'admin.previewFailed': 'Preview unavailable',

      /* ---- Errors (keys mirror ERROR_KEY in app/core/app.py) ---- */
      'err.bad_player': 'That game ID is not valid.',
      'err.bad_csrf': 'Security check failed. Please refresh the page and try again.',
      'err.bad_request': 'Malformed request.',
      'err.bad_code': 'An upload code is 8 characters long.',
      'err.bad_name': 'That file name is not valid.',
      'err.bad_nickname': 'That nickname is not valid.',
      'err.bad_password': 'Wrong password.',
      'err.no_session': 'No upload session yet — please get an upload code first.',
      'err.not_verified': 'The upload code has not been confirmed in game yet.',
      'err.unauthorized': 'Please log in first.',
      'err.not_found': 'Not found.',
      'err.not_owner': 'This image was not uploaded by you.',
      'err.not_renamable': 'Only pending images can be renamed; renaming a published image would break it in game.',
      'err.name_taken': 'An image with that name already exists. Please pick another nickname.',
      'err.no_file': 'No file received — please choose an image.',
      'err.too_large': 'The image is too large. Please compress it and try again.',
      'err.bad_image': 'That file is not a valid PNG / JPG image.',
      'err.animated_not_allowed': 'Animated GIFs are not supported. Please use a static PNG or JPG.',
      'err.pdf_not_allowed': 'PDF is not supported. Please upload a PNG or JPG.',
      'err.image_too_big': 'The image dimensions are too large. Please scale it down first.',
      'err.plugin_missing': 'The ImgUp plugin is not loaded or not enabled on the server, so no upload code can be generated.',
      'err.plugin_denied': 'The plugin rejected the request. Please check that both sides share the same shared_secret.',
      'err.rate_limited': 'You have already uploaded {daily} images today. Come back tomorrow.',
      'err.too_many_codes': 'Too many upload codes requested. Please wait a moment.',
      'err.queue_full': 'There are already {max} images awaiting review. Please try again after the admin clears the queue.',
      'err.busy': 'The server is busy. Please try again later.',
      'err.storage_error': 'Saving failed. Please try again later.',
      'err.template_missing': 'A page template is missing. Please contact the admin.',
      'err.internal': 'Internal server error. Please try again later.',
      'err.timeout': 'The request timed out. Please try again.',

      /* ---- Result page status lines (server sends key + params) ---- */
      'status.published': 'Published — you can post it in game now.',
      'status.pending': 'Submitted and waiting for the admin to review. Once approved you can post it in game.',
      'status.rejected': 'This image was not approved.',
      'status.rejected_reason': 'This image was not approved (reason: {reason}).',
      'status.unknown': 'Unknown status.',
      'status.renamed': 'Name updated.',
    }
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

  /** 服务端有哪些语言文件（messages/*.yml），语言开关据此列出选项。 */
  var AVAILABLE = [];
  var DEFAULT_LANG = (typeof window.IMGUP_LANG === 'string' && window.IMGUP_LANG) || FALLBACK;

  function buildSwitcher() {
    var nav = document.querySelector('.topnav');
    if (!nav || nav.querySelector('.lang-switch')) { return; }

    var btn = document.createElement('button');
    btn.type = 'button';
    btn.className = 'lang-switch';
    btn.setAttribute('data-i18n-attr', 'title:common.langSwitchTitle');
    var label = document.createElement('span');
    // 按钮上写「切到哪门语言」，名字来自目标语言的 meta.name
    label.textContent = LANGUAGE_NAMES[FALLBACK] || FALLBACK.toUpperCase();
    btn.appendChild(label);
    btn.addEventListener('click', function () {
      cycleLanguage(btn);
    });
    // 放在导航最左边：切语言是个「全局开关」，不该挤在末尾
    nav.insertBefore(btn, nav.firstChild);

    // 问服务端有哪些语言可用（一个语言文件就是一个选项）
    fetch('/api/languages', { headers: { 'Accept': 'application/json' } })
      .then(function (r) { return r.json(); })
      .then(function (data) {
        if (data && data.ok && data.available && data.available.length) {
          AVAILABLE = data.available;
          DEFAULT_LANG = data.default || DEFAULT_LANG;
          updateSwitchLabel(label);
        }
      })
      .catch(function () { /* 拿不到就退化成默认的两门语言 */ });
  }

  /** 按钮上写「切到哪门语言」：中文界面显示 English，英文界面显示 中文。 */
  function updateSwitchLabel(label) {
    var others = AVAILABLE.filter(function (code) { return code !== lang; });
    if (!others.length) { return; }
    var next = others[0];
    label.textContent = languageName(next);
  }

  /**
   * 语言开关上显示的名字，来自各语言文件里的 meta.name（写这门语言自己的叫法）。
   * 加语言时不用改这里 —— 复制一份 yml 填好 meta.name 就会出现。
   */
  var LANGUAGE_NAMES = { zh: '中文', en: 'English' };

  function languageName(code) {
    if (LANGUAGE_NAMES[code]) { return LANGUAGE_NAMES[code]; }
    // 当前语言表里没有别的语言的 meta.name，就退回语言代码
    return code.toUpperCase();
  }

  /** 用当前语言文件里的 meta.name 补齐已知语言名（至少能显示自己）。 */
  function rememberOwnName() {
    var name = DICT[lang] && DICT[lang]['meta.name'];
    if (typeof name === 'string' && name) {
      LANGUAGE_NAMES[lang] = name;
    }
  }

  /**
   * 切到下一门语言：先让服务端记住（Set-Cookie），再带 ?lang= 刷新页面，
   * 这样服务端会用新语言把整页重新渲染一遍 —— 首屏就是对的，不会闪。
   */
  function cycleLanguage(btn) {
    var others = AVAILABLE.filter(function (code) { return code !== lang; });
    if (!others.length) {
      // 没拿到语言列表（接口失败）时退化成两门语言互切
      setLang(lang === 'zh' ? 'en' : 'zh');
      return;
    }
    if (others.length > 1) {
      // 超过两门语言时让玩家选，避免「点好几下才轮到想要的」
      var picked = window.prompt(others.map(function (c, i) {
        return (i + 1) + '. ' + languageName(c);
      }).join('\n'), '1');
      var index = parseInt(picked, 10);
      if (!index || index < 1 || index > others.length) { return; }
      switchTo(others[index - 1]);
      return;
    }
    switchTo(others[0]);
  }

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
    rememberOwnName();
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
