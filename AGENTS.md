# AGENTS.md — 卡待删（需求/架构合同 + 产品工程 `kadaishan/`）

## 项目定位
自用单机 Android 相册清理 App「卡待删」的需求与架构契约仓库。产品代码在 `kadaishan/`（进度见「状态」行）；`卡待删-PRD.md` + `卡待删-架构设计.md` 两份文档 = 给 AI 编程助手的开发输入（合同）。目标机：vivo S17 Pro（Android 15），只给自己用、不上架。

## 怎么跑 / 怎么开工
- 产品工程 = `kadaishan/`（module `:app`，包名 `dev.sxc.kadaishan`）。构建/门禁（本机）：
  `powershell -Command "& 'C:\Users\27017\.gradle\wrapper\dists\gradle-8.13-bin\5xuhj0ry160q40clulazy9h7d\gradle-8.13\bin\gradle.bat' -p 'D:\project\sxc\kadaishan' assembleDebug test --console=plain --no-daemon"`
  APK = `kadaishan/app/build/outputs/apk/debug/app-debug.apk`。
- 构建环境：PATH 上的 gradle 9.6 未验证勿用；wrapper 发行版下载被墙；依赖/插件只走阿里云镜像（`settings.gradle.kts` 已锁，禁止加直连源）；`sdk.dir` 在 `local.properties`。
- 状态（2026-09-27 下午）：刀 1（数据层+分类+设置）+ 刀 2（大图卡片流/手势/撤回/轮次语义）已过门禁（编译 + **66 单测**）；刀 4 播放核心已提前落地（media3 播放/默认静音/点按暂停/小喇叭，余 §10 预取验证）。**产品面 = 大图卡片流**（列表 UI 已退役删除，勿再重建）；图片三板块（照片/截图/已保存）+ 视频板块。真机由用户自测中（覆盖装：`adb push` APK → 手机文件管理器安装——vivo 禁了 USB 安装通道）。下一步 = **刀 3**（结算 + 删除状态机 + 回查，3.5 人日）→ 刀 5（相似照片 MVP）→ 横切（onboarding/找回演练/验收工具）；切分与依赖见架构 §9，验收见 PRD §8。

## 技术栈（已定，见架构文档）
Kotlin + Jetpack Compose（Material3）+ 单 Activity；MediaStore 访问收敛单一数据层；DataStore 落盘（`PendingSettlement`）；一期无 Room、无第三方识别库（64 位 pHash 自实现）；媒体预览 media3。

## 文档地图与修改约定
- `卡待删-PRD.md`（v1.3）：唯一权威需求——交互口径、删除语义、边界情况、验收标准、G0 实测结果（§7.1）。
- `卡待删-架构设计.md`（v2.2）：唯一权威架构——模块、状态机、回查口径、常量表、G0 门禁表（§9）。
- `卡待删-未来扩展.md`：明确暂缓项 + 预留点；扩展/加需求前先查此文件。
- `kadaishan/`：产品工程（Kotlin/Compose，包 `dev.sxc.kadaishan`）。代码改动不改文档口径；需求变更才走 PRD/架构同步规则（同事实只留一处权威，冲突以 PRD 为准）。
- 改需求必须同步 PRD 与架构两份，并在两份头部各加一行版本记录（绝对日期）；同一事实只保留一处权威解释，冲突时以 PRD 为准。

## 不看会犯错的边界（G0 实测，出处：PRD §5.3/§7.1、架构 §7/§9/§10）
1. **绝对零联网**：不声明 `INTERNET`（连依赖树都不能引入），无更新检查/统计 SDK；验收⑤ 要 OS 级证据链。
2. **找回必须走 App 恢复入口**（`createTrashRequest(uris, false)`）：vivo 回收站双体系，三方删除项在系统相册「最近删除」**不可见**——文案不得承诺相册可见；**禁止自建回收站**，删除一律走 `createTrashRequest(true)` / `createDeleteRequest`。
3. 快照含失效 URI 时 `create*Request` **构建时抛 `IllegalArgumentException`、整批拒绝**——切批前必须 `queryByIds` 验活过滤。
4. `batchCap` 初值 50（G0 唯一实测档）；永久删除单轮 ≤30（`PERM_DELETE_CAP`，设置可降不可升）；降档 = 逐档减半下限 5。
5. 三媒体权限（`READ_MEDIA_IMAGES`/`READ_MEDIA_VIDEO`/`READ_MEDIA_VISUAL_USER_SELECTED`）必须**同一次**请求；漏 RMVUS 进 compat 模式会把 PARTIAL 误判 FULL。
6. 成功判定**只信回查三态**（`MATCH_INCLUDE` 查询），不信 `RESULT_OK`；Deny 也回查一次。
