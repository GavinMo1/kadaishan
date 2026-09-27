# 卡待删 · 交接文档（2026-09-27，会话交接）

> 给接手的新代理。项目入口 = `D:/project/sxc/AGENTS.md`（今天刚收尾过，是现役指挥面）；本文件只记**其他文档里没有的会话态**，不复述合同内容。

## 一、项目与文档地图（只列指针）

- 需求合同：`D:/project/sxc/卡待删-PRD.md`（v1.3，唯一权威需求）
- 架构合同：`D:/project/sxc/卡待删-架构设计.md`（v2.2，模块/状态机/常量/G0 门禁/开发切分 §9）
- 暂缓项：`D:/project/sxc/卡待删-未来扩展.md`（加需求前先查）
- 产品工程：`D:/project/sxc/kadaishan/`（Kotlin/Compose，module `:app`，包 `dev.sxc.kadaishan`）
- 构建命令、环境坑（gradle 8.13 直调、阿里云镜像锁、sdk.dir、vivo 装机绕行）、六条「不看会犯错」的 G0 硬边界：**全在 AGENTS.md，先读它**。

## 二、当前进度（2026-09-27 下午收口时点）

| 刀 | 状态 | 证据 |
|---|---|---|
| 刀 1 数据层+分类+设置 | ✅ 门禁绿 | 66 单测全绿（Paging 12 / Settings 17 / Classify 16 / BoardSession 13 / Sort 8） |
| 刀 2 大图卡片流/手势/撤回/轮次 | ✅ 门禁绿，用户自测中 | 产品面 = 大图卡片流；**列表 UI 已退役删除，勿重建** |
| 刀 4 播放核心（提前） | ✅ media3 播放/静音/点按暂停/小喇叭 | 余 §10 media3 预取验证 |
| **刀 3 结算+删除状态机+回查（心脏，3.5 人日）** | ⬜ **下一步** | DoD = PRD 验收①②链路真机可测 |
| 刀 5 相似照片 MVP、横切（onboarding/找回演练/验收工具） | ⬜ 待排 | 切分见架构 §9 |

- 运行态：新版 APK 已 `adb push` 至手机 `/sdcard/Download/kadaishan-0.1.0-debug.apk`，**等用户文件管理器覆盖安装**（vivo 禁 USB 安装通道）；手机上可能仍是旧包。当前版本**删不了任何文件**（删除链路 = 刀 3），用户可安全试用。
- 单发任务（子代理）会随会话消亡，前任 agent 不可复用；重新派发即可。

## 三、待用户裁决（不许替用户拍板）

1. **视频播完行为**：现实现 = 单曲循环（`REPEAT_MODE_ALL`，PRD 未写死）；备选 = 播完自动下一条（抖音式常解）/ 停末帧。等用户试用反馈后改一处常量。
2. **洁癖收尾的删除候选**（未确认前一个都不许删）：`D:/project/sxc/smoke1.png`、`D:/project/sxc/kadaishan/gate2.log`、`gate3.log`、`kadaishan/.kotlin/errors/errors-*.log`。`kadaishan/local.properties` 是构建必需，保留。

## 四、工作协议（用户确立的常设规则，必须遵守）

1. **每个任务（子代理）必须设执行时间上限**：写进任务文字（如「45 分钟内交付」）+ 编排侧 `wait(timeout)` 兜底 + 超时 `write proc://<id>/kill` 拆小重派；同批均值 3 倍无交付即视为挂死。实测有效的组合：预算 40~45 分钟 + 强制「先落文件后润色」。
2. **产品面即验收面**：用户以「打开屏幕认得出的产品行为」验收，不认工程切片中间形态（两次教训：列表 UI 被否、视频没播放被问）。交付/装机前自问「用户看到的和他脑中的产品对得上吗」，分期必须显式说清哪些按钮还没接。
3. **技术事实调研必须派全能力 agent（task 型）**，禁止用轻量 scout 做 API 语义类调研——用户会检查代理类型。
4. 改需求必须 PRD+架构同步并在两份头部加版本记录（绝对日期）；同一事实只留一处权威，冲突以 PRD 为准（详见 AGENTS.md 文档地图约定）。
5. AI 辩论/产出文件的布局偏好与「不自动恢复用户删掉的产出」规则见项目记忆（learned lessons）。

## 五、环境速记（AGENTS.md 之外的补充）

- 手机经 USB 连接（adb 可用，vivo S17 Pro / Android 15）；`adb install` 会被秒拒，装机走 push + 手机文件管理器（AGENTS.md 状态行有细节）。
- powershell 调 gradle 退出码常假报 `EXIT=1`（stderr 误导）；以输出 `BUILD SUCCESSFUL` 为准。
- Kotlin 反引号测试名禁 `;`（JVM 方法名限制），会以 `Name contains illegal characters` 编译失败。
- 本仓库不是 git 仓库；勿为验证跑 git 子命令。
