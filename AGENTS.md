# AGENTS.md — 卡待删（需求/架构合同 + 产品工程 `kadaishan/`）

## 项目定位
自用单机 Android 相册清理 App「卡待删」的需求与架构契约仓库。产品代码在 `kadaishan/`（进度见「状态」行）；`卡待删-PRD.md` + `卡待删-架构设计.md` 两份文档 = 给 AI 编程助手的开发输入（合同）。目标机：vivo S17 Pro（Android 15），只给自己用、不上架。

## 怎么跑 / 怎么开工
- 产品工程 = `kadaishan/`（module `:app`，包名 `dev.sxc.kadaishan`）。构建/门禁（任意机器统一入口）：
  `kadaishan\gradlew.bat -p kadaishan assembleDebug test --console=plain --no-daemon`
  （wrapper 发行版已锁腾讯镜像；本机另直装 `D:\tools\gradle-8.13` 同版本可直调）
  APK = `kadaishan/app/build/outputs/apk/debug/app-debug.apk`。
- 构建环境：装机/重装/换机步骤统一见 `开发环境安装.md`（JDK 17 + Android SDK 建议 `D:\Android\Sdk`；依赖只走阿里云镜像，`settings.gradle.kts` 已锁，禁止加直连源）。
- 状态（2026-09-30）：**UI/交互改版已过门禁（编译 + 136 单测 = debug/release 各 68，0 失败）**——Daylight 设计系统（悬浮半透 chrome、跟手动效+方向浮标、信息抽屉、视频等比画面+进度条、触觉回执）；图片板：下滑=撤回（栈式可连撤）、信息=上滑；视频板：左滑=标记删除 / 右滑=保留、信息=长按、恒静音，卡面零角标（Snackbar 撤回 + 回看左滑取消 + 左上角已标记指示；dwell 机制与设置项已拆；进度条带内 seek 不与手势冲突）；每轮数量当前轮立即生效 + 滑条松手落盘 + 点数值弹窗精输。**trial/ojo-design 分支**：OJO 技能审计（0 Critical、UI 层通过、动效层 2 High）+ 方向 A「原生物理」落地——弹簧词汇表六根、松手速度接续退场、SpringSheet 自绘抽屉（信息/结算全量迁移，把手拖拽 dismiss，CONFIRMING/VERIFYING 锁关闭）、按压缩放/勾选落定动画、结果页「已释放」数值滚动落定。
- 产品面 = **大图卡片流**（列表 UI 已退役删除，勿再重建）；UI 方向 = **iOS 高级精致感**——Daylight 设计系统（唯一定义点 `ui/kit/DesignKit.kt`，规范见 `DESIGN.md`）：悬浮半透 chrome、药丸键、圆环勾选、分组列表、弹簧动效 + 触觉回执，阴影只给影像。
- 真机由用户自测中（覆盖装：`adb push` APK → 手机文件管理器安装——vivo 禁 USB 安装通道；G0-1：相册「最近删除」不显示三方删除项）。
- 下一步 = **刀 5**（相似照片 MVP）+ 横切（onboarding/找回演练/验收工具）+ 真机链路实测（删除/找回/回查冒烟、回收站页验收）；切分见架构 §9，验收见 PRD §8。

## 技术栈（已定，见架构文档）
Kotlin + Jetpack Compose（Material3）+ 单 Activity；MediaStore 访问收敛单一数据层；DataStore 落盘（`PendingSettlement`）；一期无 Room、无第三方识别库（64 位 pHash 自实现）；媒体预览 media3。

## 文档地图与修改约定
- `卡待删-PRD.md`：唯一权威需求——交互口径、删除语义、边界情况、验收标准、G0 残项（§7.1）。
- `卡待删-架构设计.md`：唯一权威架构——模块、状态机、回查口径、常量表、G0 门禁（§9）。
- `卡待删-未来扩展.md`：明确暂缓项 + 预留点；扩展/加需求前先查此文件。
- `开发环境安装.md`：JDK/Gradle/SDK 装机与重装步骤（仅装机时用；日常构建看本文件）。
- `kadaishan/`：产品工程（Kotlin/Compose，包 `dev.sxc.kadaishan`）。代码改动不改文档口径；需求变更才走 PRD/架构同步规则（同事实只留一处权威，冲突以 PRD 为准）。
- 改需求必须同步 PRD 与架构两份；同一事实只保留一处权威解释，冲突时以 PRD 为准。文档不维护版本号/更迭记录/拍板日期——历史看 git，正文只陈述现状。

## 不看会犯错的边界（G0 实测，出处：PRD §5.3/§7.1、架构 §7/§9/§10）
1. **绝对零联网**：不声明 `INTERNET`（连依赖树都不能引入），无更新检查/统计 SDK；验收⑤ 要 OS 级证据链。
2. **找回必须走 App 恢复入口**（`createTrashRequest(uris, false)`）：vivo 回收站双体系，三方删除项在系统相册「最近删除」**不可见**——文案不得承诺相册可见；**禁止自建回收站**，删除一律走 `createTrashRequest(true)` / `createDeleteRequest`。
3. 快照含失效 URI 时 `create*Request` **构建时抛 `IllegalArgumentException`、整批拒绝**——切批前必须 `queryByIds` 验活过滤。
4. `batchCap` 初值 50（G0 唯一实测档）；永久删除单轮 ≤30（`PERM_DELETE_CAP`，设置可降不可升）；降档 = 逐档减半下限 5。
5. 三媒体权限（`READ_MEDIA_IMAGES`/`READ_MEDIA_VIDEO`/`READ_MEDIA_VISUAL_USER_SELECTED`）必须**同一次**请求；漏 RMVUS 进 compat 模式会把 PARTIAL 误判 FULL。
6. 成功判定**只信回查三态**（`MATCH_INCLUDE` 查询），不信 `RESULT_OK`；Deny 也回查一次。
