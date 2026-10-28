# 卡待删

自用、单机、零联网的 Android 相册清理工具。产品工程位于 `kadaishan/`，包名 `dev.sxc.kadaishan`，目标设备 Android 15。

## 构建

需要 JDK 17、Android SDK 35。装机步骤见 [开发环境安装](开发环境安装.md)。

```powershell
.\kadaishan\gradlew.bat -p kadaishan assembleDebug test --console=plain --no-daemon
```

APK：`kadaishan/app/build/outputs/apk/debug/app-debug.apk`。

## 掌中修仙

设置中进入修仙；未授权相册时也可独立进入。包含挂机修炼、自动打怪、随机掉装、突破、装备位强化与转世传承。离线收益无时长上限、不衰减，无付费、广告、签到或后台服务。

- [玩法方案](照片清理App_放置养成玩法设计方案.md)
- [实现与验证说明](docs/掌中修仙开发说明.md)

修仙独立保存，隐藏入口不暂停挂机，显式暂停才停止收益。相册整理只提供少量额外历练，保留和删除同奖；不需要为了游戏删除照片。

媒体恢复须走 App 的找回入口，不能保证 vivo 系统相册“最近删除”能看到第三方删除项。
