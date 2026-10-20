---
name: kadaishan
description: 相册清理工具的 iOS 质感设计系统 —— 纸白画布、内容全幅、悬浮半透 chrome、Action Blue 唯一强调。
colors:
  canvas: "#FFFFFF"
  parchment: "#F5F5F7"
  ink: "#1D1D1F"
  ink-strong: "#333333"
  ink-sub: "#6E6E73"
  action-blue: "#0066CC"
  action-blue-pressed: "#0071E3"
  pearl: "#FAFAFC"
  hairline: "#E0E0E0"
  divider-soft: "#F0F0F0"
  check-ring: "#C7C7CC"
  chip-on-media: "#A3D2D2D7"
  chrome-surface: "#CCFFFFFF"
  accent-on-dark: "#2997FF"
  surface-black: "#000000"
  on-dark: "#FFFFFF"
  body-muted-on-dark: "#CCCCCC"
  danger: "#D70015"
  media-scrim: "#80000000"
  disabled-alpha: 0.5
  blue-tint: "#D9E7F9"
  blue-tint-ink: "#003062"
  error-tint: "#FDECEA"
  error-tint-ink: "#8A0010"
  graphite-secondary: "#5F5F63"
typography:
  body:
    fontFamily: "system-ui, Roboto, sans-serif"
    fontSize: "17sp"
    fontWeight: 400
    lineHeight: "25sp"
    letterSpacing: "-0.022em"
  body-medium:
    fontFamily: "system-ui, Roboto, sans-serif"
    fontSize: "14sp"
    fontWeight: 400
    letterSpacing: "-0.016em"
  headline:
    fontFamily: "system-ui, Roboto, sans-serif"
    fontSize: "22sp"
    fontWeight: 600
    letterSpacing: "-0.011em"
  title:
    fontFamily: "system-ui, Roboto, sans-serif"
    fontSize: "16sp"
    fontWeight: 600
    letterSpacing: "-0.011em"
  label:
    fontFamily: "system-ui, Roboto, sans-serif"
    fontSize: "12sp"
    fontWeight: 400
    letterSpacing: "-0.01em"
rounded:
  xs: "5dp"
  sm: "8dp"
  md: "11dp"
  lg: "18dp"
  pill: "50dp"
spacing:
  xs: "4dp"
  sm: "8dp"
  md: "12dp"
  lg: "16dp"
  xl: "20dp"
  xxl: "24dp"
  touch-min: "48dp"
components:
  button-pill:
    backgroundColor: "{colors.action-blue}"
    textColor: "{colors.canvas}"
    rounded: "{rounded.pill}"
    padding: "22dp 11dp"
    typography: "{typography.body}"
  button-pill-danger:
    backgroundColor: "{colors.danger}"
    textColor: "{colors.on-dark}"
    rounded: "{rounded.pill}"
    padding: "22dp 11dp"
    typography: "{typography.body}"
  button-pill-on-dark:
    backgroundColor: "#24FFFFFF"
    textColor: "{colors.on-dark}"
    rounded: "{rounded.pill}"
    padding: "22dp 11dp"
    typography: "{typography.body}"
  button-pearl:
    backgroundColor: "{colors.pearl}"
    textColor: "{colors.ink-strong}"
    rounded: "{rounded.md}"
    padding: "14dp 8dp"
    typography: "{typography.body-medium}"
  chip-on-media:
    backgroundColor: "{colors.chip-on-media}"
    textColor: "{colors.ink}"
    rounded: "{rounded.sm}"
    padding: "14dp 6dp"
    typography: "{typography.body-medium}"
  segmented-tabs:
    backgroundColor: "{colors.divider-soft}"
    textColor: "{colors.ink}"
    rounded: "{rounded.sm}"
    height: "36dp"
  group-card:
    backgroundColor: "{colors.parchment}"
    rounded: "{rounded.lg}"
  circle-check:
    backgroundColor: "{colors.action-blue}"
    textColor: "{colors.canvas}"
    size: "24dp"
  nav-title-bar:
    backgroundColor: "transparent"
    textColor: "{colors.ink}"
    height: "48dp"
    typography: "{typography.body}"
---

# Design System: kadaishan（卡待删）

## Overview

**Creative North Star: "安静的画廊（The Quiet Gallery）"**

照片占据白画布的中心，界面元素全部退后。整个 App 是一间日光灯下的画廊：纯白平铺的墙面（canvas #FFFFFF），照片是唯一被"打光"的展品（唯一允许投影的元素），一切控件——药丸键、分段页签、圆环勾选、分组列表——都以低对比、零高度的方式贴墙存在。用户单手滑动整理相册，每次判定都有触觉回执与弹簧动效；chrome（板块栏、动作圆片）悬浮半透，干活时自动退场，内容从不为 chrome 让位。

这是继承自代码确立的 **Daylight 世界**：Apple 语汇（Action Blue 唯一强调、圆角四档制、hairline 分隔、弹簧动效、Haptics 触觉回执）落在 Android Jetpack Compose Material3 骨架上，但视觉上不给 M3 基线留任何泄漏面——surfaceContainer 全系、inverse\*、outline 全部显式收编进 Daylight 色板，字重 500 档一律归位到 400/600 两档。始终浅色（不跟随系统深色），唯一例外是视频板：全屏黑底沉浸播放，控件转黑透圆片系。

**Key Characteristics:**

- 纸白画布 + 米白分组容器，两层浅面承载全部信息层级；禁装饰渐变
- Action Blue #0066CC 是唯一"可点"信号色；danger 红只承担破坏性/错误语义
- 圆角四档制 5/8/11/18dp + pill，无中间档
- 阴影只给影像（productShadow 一款），控件与卡片零投影零 tonal elevation
- iOS 弹簧词汇表六根（spatial / settle / tap / fling / drawer / pop，按交互原型分级）+ 触觉回执四类（tick / confirm / reject / tap）；换卡退场带松手速度接续（甩得越快飞得越远）；系统关动效时降级直切
- 内容全幅、chrome 悬浮半透覆盖，显隐不引起内容重排

## Colors

色板是"白纸上的一种蓝"：两档白（canvas / parchment）+ 三档墨（ink / ink-strong / ink-sub）+ 一支 Action Blue，加上 hairline 灰阶做分隔。全部色值唯一定义在 `Daylight` 对象（`ui/kit/DesignKit.kt`），AppTheme 只引用不写裸 hex。

### Primary

- **Action Blue**（#0066CC）：唯一强调色。药丸主键底、全部文字型可点信号（"‹ 返回"、TextButton、设置行的当前值、选中对勾）、Switch/Slider 选中态、Spinner。承担一切"这里可以点"。
- **Action Blue 按压态**（#0071E3）：按压/焦点时的替代底色。
- **危险红**（#D70015）：危险/错误语义唯一来源——永久删除药丸、错误文案、超限计数、清空回收站警示。白字对比 5.4:1。不用于装饰、不用于一般"确认"。

### Secondary

- **深底蓝**（#2997FF）：黑色媒体面（视频板）上的强调/链接色，Action Blue 的深底化身。

### Neutral

- **纸白**（#FFFFFF）：页面底色，全部平铺，无渐变无纹理。
- **米白**（#F5F5F7）：分组容器（GroupCard）、影像衬底、次级面（surfaceContainer）。
- **墨**（#1D1D1F）：浅面上全部正文与标题。
- **次墨**（#333333）：次级按钮文字、加载态文字。
- **弱墨**（#6E6E73）：弱化文字/禁用/占位（对纸白 5.1:1，正文与辅助文字均合规；AppTheme 将其映射到 onSurfaceVariant，M3 组件同源。旧值 #7A7A7A 仅 3.77:1，已弃用）。
- **珍珠白**（#FAFAFC）：浅底次级键（PearlButton）底色。
- **hairline 灰**（#E0E0E0）：1px hairline 描边/分隔（卡片描边、chrome 边框、Switch 未选轨、抽屉把手）。
- **软分隔灰**（#F0F0F0）：行间分隔线、分段页签轨、Spinner 轨、软环。
- **勾选环灰**（#C7C7CC）：圆环勾选未选态描边。
- **黑透圆牌**（#D2D2D7 @64%）：照片影像面上的半透圆牌（计数牌、首卡手势提示底），墨色字。
- **悬浮 chrome 白**（#FFFFFF @80%）：悬浮板块栏底，内容全幅、chrome 退后。

### Dark surface（视频板专用）

- **纯黑面**（#000000）+ 白前景（#FFFFFF）+ 深底弱化灰（#CCCCCC）；黑透 40% 圆片（#000000 @40%）压海报可读。
- **播放器 chrome**：画面等比信箱（min 缩放，不裁切——竖屏源近满屏、横屏/录屏完整呈现）；底部 3dp 进度细线（白 30% 轨 + 白进度，拖动时出 14dp 白拇与黑透 55% 时间胶囊）；暂停 = 中央黑透 40% 播放指示圆；换卡垂直转场（海报层随方向滑入滑出，surface 单实例不动）；操作提示沿用现有底部药丸样式，图片/视频共享每次打开一次的名额，消失规则见 PRD §4.1。

### Named Rules

**The One Blue Rule.** Action Blue 承担一切可点信号；一个屏幕上的蓝色面积以文字与小控件为限。它越稀少，"能点"的信号越清楚。次级强调不引入第二支彩色。

**The Single-Source Alpha Rule.** 媒体面半透遮罩只有一档 `mediaScrim`（surfaceBlack @50%：暂停指示/已标记牌/提示牌/时间胶囊/深色 Snackbar 同取）；禁用态透明度只有一档 `disabledAlpha`（0.5）。禁止散写 0.38/0.4/0.55/0.7 等独值 alpha。

**The System Bar Follows Content Rule.** 系统栏图标色随当前页面内容动态切换：浅面（图片板/子页/引导页）= 深色图标（SystemBarStyle.light），视频板黑底沉浸 = 浅色图标（SystemBarStyle.dark）；MainContent 按 board/子页态驱动，禁全局固定一种。

**The Photograph Casts the Only Shadow.** 投影是影像的专属属性（productShadow，rgba(0,0,0,0.22)，仅照片卡）。按钮、卡片、文字、chrome 一律零投影。深度由 hairline 与米白分层表达。

## Typography

**Display / Body / Label Font:** 系统默认字体（Android 上落 Roboto；不设 fontFamily，不引入任何自定义字体）

**Character:** 单一字族、仅 400/600 两档字重、全负字距（-0.01em ~ -0.022em）——Apple 系统字排印的直接转写。层级靠字号与颜色，不靠字族切换或 500 中间档。

### Hierarchy

- **Headline**（600，22sp，-0.011em）：主张句（权限引导标题、轮末标题）。M3 titleLarge 档，全 App 无 display 档。
- **Title**（600，16sp，-0.011em）：面板/抽屉标题、卡片副标题。M3 title\* 系全部归 600 + -0.011em。
- **Body**（400，17sp / 25sp 行高，-0.022em）：正文主档，iOS body 尺寸。
- **Body-medium**（400，14sp，-0.016em）：说明文字、次级键、圆片标签、行间辅助。
- **Label**（400，12sp，-0.01em）：最小档（SectionLabel 13sp 为其在分组标题处的近邻用法）。主题层已显式归位 headlineSmall（22/600/-0.011，M3 弹窗标题槽默认来源）、bodySmall 与 labelMedium（12/400/-0.01，M3 默认正字距属泄漏面）。
- **特例登记**：DragHintBadge 手势浮标 15sp/600（媒体面浮层可读性特例，仅此一处）。

### Named Rules

**The Two-Weight Rule.** 字重只有 400 与 600。M3 默认的 500 档已在 AppTheme 全系归位（label\* 一律 400、title\* 一律 600）；新增界面不得引入中间字重。

## Layout

单 Activity、edge-to-edge、竖屏单机（vivo S17 Pro）。布局模型是**查看器式两层**：

- **内容层全幅**：照片板 = 白画布 + 一张全屏大图卡（左右留 12dp，顶 = 状态栏 + 58dp chrome 余量，底 = 手势条 + 14dp）；视频板 = 黑底满屏裁切播放。子页（设置/回收站）= 白画布自管 insets，水平 16dp 页边距，滚动列表。
- **chrome 层悬浮**：板块栏（半透白 @80% + hairline 边框，radiusLg）与动作圆片以 overlay 叠在内容上，不占布局位——显隐只影响自身，内容永不重排。单击照片切换 chrome 显隐；干活（手势/翻页）自动收起，收起时以一枚黑透 28×5dp 圆条把手唤回。
- **手势内侧起始区**：四边各留 24dp 禁区（禁边缘手势），判定阈值 56dp；跟手位移 16%（±72dp 封顶）+ 轻微倾斜，经 graphicsLayer 惰性读取，不触发重组；拖动渐显**方向语义浮标**（左/右按手势映射取色：删除 danger / 跳过白底墨 / 信息 Action Blue）。
- **间距律**：4dp 基数节奏（4/6/8/10/12/14/16/20/24/28/32）；面板水平 20dp、页面水平 16dp、卡片内 12dp；行最小高 44/48/56/60dp，全部交互面触达 ≥48dp。

## Elevation & Depth

零高度体系。`surfaceTint = canvas`：tonal elevation 不改色，全 App 没有 M3 高度阶梯。深度只有三种表达：

1. **影像投影（唯一阴影）**：`productShadow` —— elevation 15dp、ambient/spot 各 rgba(0,0,0,0.22)、随 radiusLg 卡形。只允许出现在照片/影像上。
2. **hairline**：1px #E0E0E0 描边（卡片、悬浮 chrome 边框）与 1px #F0F0F0 行分隔。
3. **米白分层**：白画布上叠 parchment 分组容器/衬底。

### Shadow Vocabulary

- **product shadow**（elevation 15dp，ambient rgba(0,0,0,0.22) + spot rgba(0,0,0,0.22)，radiusLg 形）：照片大卡专属。视频板、按钮、卡片、抽屉、对话框均无阴影。

### Named Rules

**The Zero-Height Rule.** 卡片一律 hairlineBorder（1px hairline）或米白分层，不用阴影；ModalBottomSheet tonalElevation = 0。若一个表面看起来"浮起来了"，先检查它是不是影像——不是就去掉阴影。

## Motion（弹簧词汇表）

全部动效 = `Daylight` 六根弹簧，按交互原型取用，禁止发明第七根：

| 弹簧 | 原型（物理锚） | 参数 | 用途 |
| --- | --- | --- | --- |
| `spatial()` | 位移进场 | 0.9 / 400 | 新卡缓入、选中片滑动 |
| `settle()` | 状态过渡 | 1.0 / 1500 | chrome 显隐、跟手回弹 |
| `tap()` | 卡扣 · 磁铁吸合 | 0.62 / 2600 | 按钮按压缩放（50–100ms 手感） |
| `fling()` | 甩出 · 急促带活性 | 0.86 / 750 | 换卡退场（行程 × 松手速度系数 1.0~1.8 = 速度接续） |
| `drawer()` | 气压关门器 | 1.0 / 550 | 抽屉入场/回弹（SpringSheet） |
| `pop()` | 落定 · 轻过冲 | 0.62 / 700 | 勾选落定等达成动作 |

配套规则：手势松手速度经 `VelocityTracker` 进入退场行程（甩得越快飞得越远，双板同手感）；`SpringSheet`（自绘抽屉物理层）承接信息抽屉（InfoSheet）；结算使用独立全屏模态页——入场 drawer 到顶、把手拖拽跟手、≥140dp 判 dismiss、遮罩/返回先退场再移除；结算面板 CONFIRMING/VERIFYING 相位锁关闭（系统返回无效）。峰值时刻：结果页「已释放」数值滚动落定（settle 0→1）。

## Shapes

圆角四档制 + pill，无中间档：5（xs：最小内嵌件）/ 8（sm：圆片、缩略图、分段页签）/ 11（md：次级键）/ 18（lg：影像大卡、分组容器、悬浮 chrome）+ 50（pill：主键、计数圆牌、把手）。形态语汇：药丸（主键）、圆片（媒体面上动作）、圆环（勾选）、圆角矩形（容器）。AppTheme 的 M3 Shapes 已按此归位（medium 及以上全部 18）。

### Named Rules

**The Four-Step Corner Rule.** 新表面的圆角只能取 5/8/11/18 或 pill。发明第 9dp、第 14dp 都是在破坏档位制。

## Components

组件唯一定义点：`ui/kit/DesignKit.kt`。全部交互件：按压 scale(0.95) 系统级微交互、无 ripple indication、minTouch 48dp。

### Buttons

- **Shape:** 药丸主键 pill（50dp）；浅底次级键 11dp 圆角矩形。
- **Primary（PillButton）:** Action Blue 底 + 白字 17sp/400，内距 22×11dp；深底变体 = 白底黑字（视频板终态动作）；危险变体（DangerPillButton）= danger 红底白字，只用于永久删除。
- **Pressed:** 整体 scale(0.95)；禁用 = 底色 35% 透明度。
- **Secondary（PearlButton）:** pearl 底 + 1.5dp dividerSoft 软环 + ink-strong 字 14sp/-0.016em，11dp 圆角，内距 14×8dp。
- **On-dark（OnDarkButton）:** 白 @14% 半透底 + 白字，pill，视频板终态次级动作。
- **Text button（TextActionButton，kit）:** 蓝字透明底 14sp/(-0.016)em，48dp 触达，无水波纹、按压渐隐 50%、内置 tap 回执；禁用 = 弱墨 50%。替代全部 M3 TextButton 场景（chrome 内、一键全不选、返回、全选/全不选、改授全部）。

### Alerts（DaylightAlert，kit）

iOS 居中弹窗，替代 M3 AlertDialog（其右下按钮排布 + 大圆角是禁用形态）：312dp 上限宽白卡 radiusLg、标题居中 17sp/600、说明 13sp 弱墨居中、按钮区 1px hairline 通栏分隔、按钮整行可点（1~2 枚，双钮左右均分 + 竖 hairline、17sp；取消 400 / 确认 600 / 破坏 danger+600）、按压渐隐、内置 tap 回执；content 槽承自定义内容（输入框 = parchment 填充底 + radiusSm 无描边，禁 M3 描边输入框）。

### 照片缩放（图片板 MediaCard）

iOS 照片口径：双指捏合 1x–4x（焦点 = 双指中心，变换在卡内裁切）、双击 2.5x 放大/还原（spatial 弹簧 + tap 回执，焦点 = 点击点）、放大态单指平移（按 ContentScale.Fit 实际显示尺寸钳制边界，信箱留白不计）。>1x 时滑卡/撤回/信息手势让位平移；换卡随 AnimatedContent 重组自动复位 1x。

### Snackbar（深色胶囊，视频板）

M3 默认 Snackbar 外观（深灰 4dp 圆角）为禁用形态。视频板撤回提示 = pill + mediaScrim 底 + 白字 14sp + 动作键 primaryOnDark 14sp/600；navigationBarsPadding + bottom 48dp。

### Chips / 圆片

- **媒体圆牌:** 仅"N / M"计数 pill 牌（贴卡底中）；文件名圆牌已移除——卡面只留影像，文件信息全走信息抽屉。
- **DragHintBadge（手势浮标，浅/深媒体面通用）:** pill 底 + 15sp/600，随拖动进度渐显（14px 起步 26px 满显）+ 跟手倾斜（纵向用不倾斜）；图片板按手势映射取色（删除 danger / 跳过白底墨 / 信息 Action Blue / 底缘撤回白底墨），视频板固定左「删除」红、右「保留」白。
- **已标记指示（视频板）:** 左上角黑透 pill + 8dp danger 红点 + 白字 13sp「已标记」——滑动标记后回看可辨的唯一信号。
- **零角标原则:** 图片/视频卡面不设任何常驻角标按钮（信息走手势：图片上滑 / 视频长按；撤回走图片下拉 / 视频 Snackbar；视频恒静音）。

### Cards / Containers

- **GroupCard:** 米白底、18dp 圆角、零投影零描边；行内自管 1px dividerSoft 分隔线（左端随内容缩进）。
- **SectionLabel:** 分组标题，13sp inkSub，左 20dp 上 22dp 下 7dp。
- **照片大卡（MediaCard）:** parchment 衬底 + productShadow + 18dp 裁圆（影像区 11dp 内裁）；影像占满，卡面仅计数圆牌，文件信息不落卡面。
- **信息抽屉（InfoSheet，图片/视频板共用）:** canvas 底 + hairline 把手 + tonalElevation 0；顶部当前媒体头图（150dp Crop、radiusMd、米白衬底），下方 GroupCard 分组行（label 墨 17 / 值右对齐弱墨 17、48dp 行、dividerSoft 分隔）。下滑/遮罩/返回关闭。

### Inputs / Fields

- **Switch:** Action Blue 轨 + 白拇选中；未选 hairline 轨 + canvas 拇（SwitchDefaults 显式收编）；**拇子纯白无图标**（`thumbContent = {}` 去除 M3 默认内嵌对勾）。
- **Slider:** Action Blue 活轨 + canvas 拇 + hairline 底轨；**拇子 = 28dp 白圆 + 1px hairline 描边自绘**（iOS UISlider 语汇，替代 M3 竖条拇 + 按压圆晕）。
- **文本输入（仅精输弹窗一处）:** parchment 填充底 + radiusSm 无描边（TextField 指示线显式透明），禁 OutlinedTextField 描边形态。

### 列表行按压（daylightRowClickable，kit）

页面层可点行/控件一律 `indication = null`（禁水波纹——Material 签名式反馈）；iOS 语汇的按压反馈 = 整行叠加 6% 墨高亮（daylightRowClickable 内置，背景先于 padding 上链）或文字键渐隐 50%（TextActionButton 内置）。

### Navigation

- **分段页签（SegTabs）:** dividerSoft 轨 + 纯白选中滑片（settle 弹簧滑动）+ 3dp 轨距；选中 600/未选 400，14sp/-0.016em。
- **推入子页导航（NavTitleBar）:** "‹ 返回"蓝字 17sp 左置（48dp 触达）、标题 17sp/600 居中、透明底。
- **悬浮板块栏:** chrome 白 @80% + hairline 边框 + 18dp 圆角；左 SegTabs 右"回收站/设置"蓝字键，1×24dp hairline 竖分隔。

### 圆环勾选（CircleCheck）

iOS 多选语汇：24dp 圆、未选 1.5dp checkRing 灰环 → 选中 Action Blue 实底 + 白勾（16dp）。结算勾选行、回收站行共用。

### 结算页（SettlementPanel）

全屏复核页沿用 Daylight canvas、单字族与弹簧入场。顶部只保留返回和全选/全不选，标题左对齐，副文案交代本轮浏览数与标记数。主体为自适应缩略图网格（最小列宽 96dp、8dp 间隔、radiusMd、右上圆环勾选），不以文件名列表占据视觉中心。底部固定主操作，显示勾选数量和容量；永久删除收在删除方式「更改」中，开启后始终显示危险提示，超限禁止提交但允许继续取消勾选。

无删除项直接继续整理。完整回查全部成功后回到卡片流，用 Snackbar 给出数量与找回入口；异常结果或冷启动恢复才显示结果页。异常页突出未处理数量，只列差异项，非零状态计数保持可追查；内容滚动，底部操作区固定且可在大字体下滚动，统一「继续整理」出口。CONFIRMING/VERIFYING 消费返回键并锁关闭。

### Signature：触觉回执（Haptics）

判定达成 tick（GESTURE_TICK）、正向确认 confirm（CONFIRM）、逆向回退 reject（REJECT）、控件点按 tap（KEYBOARD_TAP）。每个改变状态的操作必须伴一类回执——无声的成功不属于这个世界。

## Do's and Don'ts

### Do:

- **Do** 全部色值/圆角/弹簧引用 `Daylight` 单一来源；AppTheme 与新界面只引用，不写裸 hex。
- **Do** 给每个状态改变的操作配触觉回执与弹簧动效（按词汇表取原型：位移 spatial / 状态 settle / 按压 tap / 甩出 fling / 抽屉 drawer / 落定 pop）；尊重系统"移除动画"开关降级直切。
- **Do** chrome 悬浮于内容之上（半透白 @80% + hairline），让内容保持全幅；显隐用 slide+fade，不重排布局。
- **Do** 危险动作用红色药丸 + 系统二次确认，结果一律三态可追（已执行/未执行/结果未知）。
- **Do** 保证每个终态有出口（"查看结算 / 开始下一轮"双按钮），不留零按钮死局。

### Don't:

- **Don't** 给影像以外的任何元素加阴影，或引入 tonal elevation / 渐变 / 第二支强调色。
- **Don't** 使用 5/8/11/18/pill 之外的圆角，或 400/600 之外的字重。
- **Don't** 让 chrome 占据布局位、或让内容为 chrome 让位。
- **Don't** 在浅媒体面上放不透明控件牌——媒体面控件一律半透（黑透圆牌 / 黑 @40% 圆片），保证影像透出。
- **Don't** 把 danger 红用于非破坏性语义，或把 Action Blue 用于不可点元素。
