# 个人工具箱

V2.0 新增「任务提醒」：常用地点、地址/时间/组合提醒、关联每日记录未完成任务、通知响铃和震动。使用方式、后台运行边界与真机验收见 [V2.0 提醒说明](docs/reminders-v2.md)。

基于现有 Kotlin / Jetpack Compose / Material 3 工程开发。第一阶段提供「每日记录」。

## 功能

- 工具箱首页进入每日记录。
- 每日固定清单与当日临时任务，独立的完成状态与完成时间。
- 完整月历、前后日期切换、日期栏左滑前一天/右滑后一天、历史补记及未来临时任务规划。
- 月历仅显示当前月，前后空位留白；用颜色区分状态。日期下的状态文字默认隐藏，可在日历“设置”中开启并自动保存。
- 总完成及分类统计；未完成在上，完成任务移动到已完成区域并保留完成时间。
- 临时任务支持改名、删除，未来固定清单动态预览，当天再生成完成记录。
- 固定模板新增、改名、停用，历史记录保持不变。
- Room 本地持久化，支持深浅色主题。
- “每日记录”卡片内的“备份与恢复”仅处理每日记录数据，支持导出 JSON、定位/分享备份、预览后确认恢复。
- 使用用户提供的工具箱图标，配置 Adaptive Icon 及 Android 7 启动图标。

## 开发与运行

在 IntelliJ IDEA 打开现有项目并同步 Gradle，选择 `app` 和连接的 Android 手机运行。
最低 Android 7.0（API 24），需要 Android SDK 36。
当前 IDEA 配置使用本机 Gradle 9.5.0；保留项目的 AGP 9.0.0-alpha06。
命令行构建可使用 IntelliJ IDEA 自带 JBR 25。本次功能更新不新增依赖、不执行 Gradle，编译和运行测试由项目维护者完成。
Compose 编译插件与内置 Kotlin 对齐为 2.2.10，Java/Kotlin 字节码目标为 11。

Windows PowerShell：设置有效的 `JAVA_HOME`，并将本机 Gradle 的 `bin` 目录加入 PATH 后执行：

```powershell
gradle :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
gradle :app:connectedDebugAndroidTest
```

第二条命令需要连接已开启 USB 调试并授权的 Android 手机或模拟器。
也可以直接使用 IDEA 的 Gradle 面板执行对应任务。现有仓库没有完整的 Gradle Wrapper，不能直接使用 `gradlew.bat`。
首次构建需要下载依赖；网络代理只配置在本机或命令行，不提交到工程。
`local.properties` 保持本机 SDK 路径，不提交。

APK：`app/build/outputs/apk/debug/app-debug.apk`。
测试报告：`app/build/reports/tests/testDebugUnitTest`、`app/build/reports/androidTests/connected`。

架构、文件阅读顺序及数据规则见 [docs/architecture.md](docs/architecture.md)。

## 导出、查找和恢复记录

1. 在“个人工具箱”首页的“每日记录”卡片内点“备份与恢复”；点击卡片其他区域进入每日记录。
2. 点“导出备份 · 选择保存位置”，在系统文件选择器中选择 **内部存储 → Download（下载）**，也可创建自己的备份文件夹。
3. 确认顶部文件夹后保存。建议文件名自动包含导出日期和时间，例如 `每日记录备份_2026-09-27_23-10-00.json`。
4. 返回 App 后可查看最近备份文件名及位置，点“定位文件”“复制文件名”或“分享备份”。也可在手机文件管理器的下载目录直接查找，或按文件名搜索。
5. 恢复时点“选择文件导入”，找到备份，检查日期、模板和任务数量，再确认“替换每日记录数据”。**只完整替换每日记录，不合并，也不处理其他工具的数据；先导出当前记录再恢复更便于撤回。**

备份包含每日记录的历史/未来临时任务、完成状态与时间、固定模板的所有版本，不包含其他工具数据、手机专属文件权限或未来固定任务预览。
新备份标记 `module: daily-record`，拒绝其他工具模块的备份；兼容此前未带 module 字段、仅包含每日记录数据的 v1 备份。
文件格式版本为 1，单文件上限 20 MB，三类记录各最多 100,000 项。解析、字段与关联关系校验失败时不修改数据库；恢复在一个事务内完成。
本机标准存储提供器可以显示可读目录；网盘或部分厂商提供器不提供真实路径，此时显示文件提供器及“定位文件”入口，不把文件 URI 当成文件系统路径。
系统文件选择器是否自动定位由手机/提供器决定；文件移动、删除或权限失效后，请重新选择。卸载 App 后仍需从自己选择的公共目录手动选取备份。
实现使用 Android [Storage Access Framework](https://developer.android.com/training/data-storage/shared/documents-files)，不申请全盘存储权限。

## 图标与验收

图标源文件为用户提供的 `app/src/main/ic_launcher-playstore.png`，保持原文件不变。
Windows 下运行 `./scripts/sync_launcher_icon.ps1` 可同步原图副本和多密度启动资源，不需要下载依赖。
Adaptive Icon 使用白色背景和留有裁切余量的原图前景；旧版图标仅按比例缩放原图。
资源名为 `toolbox_launcher`，未使用自绘替代图或另造单色图案。

测试代码已更新，但本次未执行。人工验收步骤见 [docs/acceptance.md](docs/acceptance.md)。
