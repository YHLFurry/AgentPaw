# AgentPaw 0.4.0 发版自检

日期：2026-10-27。版本：0.4.0，Android versionCode：13。

## 本版内容

- 修复停止后立即启动新任务的状态竞争；断点写删按顺序处理，保留已完成步骤与授权操作回执。
- 改进聊天文字草稿恢复、新对话确认、通知权限恢复及模型配置入口。
- 更新悬浮任务面板，支持展开/收起、停止/继续、边界限制及大字号。
- 修复设备回归发现的展开按钮定位问题：使用独立箭头图标，避免旋转整个按钮影响自动化点击坐标。
- 沿用现有 GitHub CI 和发布 Actions；本地完成双构建类型单元测试、Lint 和仪器测试 APK 编译。

## 自检结果

使用 JDK 21 和本机依赖缓存执行：

```powershell
.\gradlew.bat testDebugUnitTest testReleaseUnitTest :app:lintDebug :agentpaw-core:lintDebug :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest --offline --console=plain --max-workers=2
git diff --check
```

- 最终合并和版本更新后构建成功；222 项任务，31 项执行、191 项 up-to-date。
- app：Debug/Release 各 70 项测试，全部通过。
- core：Debug/Release 各 283 项测试，分别 281 通过、2 条件跳过；无失败或错误。跳过项涉及公网搜索及符号链接环境。
- Lint：app 91 条警告、core 24 条警告，均无错误。
- Debug、Release 及 Android 仪器测试 APK 编译成功。
- 差异空白检查通过；远端聊天和悬浮窗改动已合并，保留本地调度修复及全部回归测试。

## 验证边界

ADB 无连接设备，未执行仪器测试；Root/Shizuku、系统授权、OEM 后台限制、悬浮窗旋转和 TalkBack 仍需设备验证。本地校验复用了未变化任务的 Gradle 结果，Actions 将在独立环境重新校验及构建发布产物。

当前 GitHub OAuth 凭据缺少 `workflow` scope，因此发布分支不包含工作流修改；改为调用已有 CI 工作流验证发布分支，再推送标签触发已有发布工作流。
