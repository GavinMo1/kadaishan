# AGENTS.md — 卡待删

## 项目定位与当前状态
- 自用、单机、零联网 Android 相册清理 App；目标机 vivo S17 Pro（Android 15），不上架、不商业化。
- 产品工程在 `kadaishan/`，module `:app`，包名 `dev.sxc.kadaishan`。
- 修仙战斗成长改造已实现（属性影响周期、真实轨迹试演、schema2迁移及可靠补给）；用户早期反馈基本功能可用、画面简陋，新版原生截图与真机验收仍待完成；验证范围与待办统一见 [开发说明](docs/掌中修仙开发说明.md)。图片智能混排已实现（设置主动选择、下一轮生效），验证与待验收见 [实施说明](docs/图片智能混排实施说明.md)；不自行启动相似照片或视觉改版。
- 修仙功能当前在 `codex/cultivation`，已推送但未合入 `main`。开工先查 `git status` 与分支，不从 `main` 误判功能缺失；不擅自合并或清理分支。

## 构建与安装
- 统一门禁：`kadaishan\gradlew.bat -p kadaishan assembleDebug test --console=plain --no-daemon`。
- APK：`kadaishan/app/build/outputs/apk/debug/app-debug.apk`。
- JDK 17、Android SDK 35；装机见 [开发环境安装](开发环境安装.md)。Gradle wrapper 锁腾讯镜像，Maven 依赖只走 `settings.gradle.kts` 中的阿里云镜像，禁止增加直连源。
- vivo 禁 USB 安装通道：可 `adb push` APK 后在手机文件管理器覆盖安装；不默认执行 `adb install`。

## 技术栈与界面
- Kotlin + Jetpack Compose（Material3），单 Activity；MediaStore 访问收敛在数据层，DataStore 持久化，media3 预览；无 Room、无第三方识别库。
- 相册产品面是大图卡片流，列表 UI 已退役，勿重建。沿用 iOS 精致方向的 Daylight，唯一 token/公共控件定义在 `kadaishan/app/src/main/java/dev/sxc/kadaishan/ui/kit/DesignKit.kt`。
- 图片板下滑撤回、上滑信息；视频板左滑标记删除、右滑保留、长按信息、恒静音。游戏不覆盖卡面、不干扰手势、结算和找回。
- 修仙引擎保持纯 Kotlin；游戏 DataStore 与工具设置、`PendingSettlement` 分离。隐藏入口不暂停；只有显式暂停才停止收益。改动存档或收益算法时核对现有存档兼容性与前台/离线一致性。
- 离线收益必须全额、无时长上限、不衰减；不通过删除量、文件大小或永久删除加奖，不加入氪金、广告、签到或后台保活。

## 文档与协作约定
- [README](README.md)：使用与入口；[玩法方案](照片清理App_放置养成玩法设计方案.md)：修仙需求与数值的唯一现役解释；[开发说明](docs/掌中修仙开发说明.md)：实现、验证与未闭合事项；[页面约束](.impeccable/surface-briefs/cultivation.md)：修仙页的视觉边界。
- 现役合同已按已核实现状重建为 [产品需求](docs/产品需求.md) 与 [产品架构](docs/产品架构.md)，变更需求须同步二者，专题规则只留一处详解。根目录旧 PRD/架构/未来扩展及 DESIGN 等已有删除仍保留，不擅自恢复或提交其删除；它们在 Git 中仅供历史参考，不构成并行合同。
- 同一事实保留一处权威解释，其他文件用指针。文档不维护版本号、轮次流水账和更迭日期，历史看 Git。
- 保留用户原有改动；只提交本次明确改动的文件，不使用 `git add -A` 混入原有删除或本机技能目录。
- 项目技能有 Claude 与 Codex 安装面，不能假定副本同源或 hook 已加载；原生画面验收用真实设备/模拟器截图，Web 检测不能替代。

## 删除与恢复的硬边界
1. 不声明 `INTERNET`，包括依赖合并后的 manifest；无更新检查、统计或联网 SDK。APK 权限核对不等于完整 OS 级零联网验收。
2. 删除只走 `createTrashRequest(true)` / `createDeleteRequest`；找回必须走 App 恢复入口 `createTrashRequest(uris, false)`。vivo 系统相册“最近删除”看不到第三方删除项，文案不得承诺可见，禁止自建媒体回收站。
3. 快照含失效 URI 时系统请求构建可能整批拒绝；切批前必须 `queryByIds` 验活过滤。
4. `batchCap` 初值 50；永久删除单轮 ≤30（`PERM_DELETE_CAP`，设置可降不可升）；降档逐档减半，下限 5。
5. `READ_MEDIA_IMAGES` / `READ_MEDIA_VIDEO` / `READ_MEDIA_VISUAL_USER_SELECTED` 必须同一次请求，避免 compat 模式误判 PARTIAL 为 FULL。
6. 删除成功只信 `MATCH_INCLUDE` 回查三态，不信 `RESULT_OK`；拒绝也回查一次。
