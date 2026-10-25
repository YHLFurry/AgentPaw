# 悬浮窗 UI 优化

日期：2026-10-26。

- 将单行胶囊拆为品牌/运行状态、两行动作说明、步数与次要操作；深蓝渐变底、青绿运行态、琥珀暂停态、红色停止按钮。
- 展开宽度上限 296dp，收起为 232dp；大字号下收起宽度也放宽到 296dp，并受屏幕可用宽度限制。
- 收起始终保留停止/继续按钮；结束态提供查看入口。步数明确标为执行上限，不显示容易误解的任务完成百分比。
- 点击区域至少 48dp 高；加入圆角涟漪、轻触反馈、独立按钮无障碍描述、中英文文案。动作说明不再预先截成 24 字符，视觉上保留两行省略。
- 复用原生 View，避免引入服务中的 Compose 生命周期管理；`AgentFloatingPanel` 独立于服务挂载、拖拽及状态观察。
- 拖拽使用系统 touch slop，点击通过 `performClick()`；限制窗口边界，考虑系统栏/刘海，屏幕配置和布局大小变化后重新约束位置。
- 状态观察改用 `collectLatest`：新任务取消结束态的 1.5 秒等待，及时更新内容；服务销毁时取消协程域。
- 保持应用前台隐藏、后台显示的既有策略，以及停止和断点继续的原有执行链路。

验证命令（JDK 21，离线缓存）：

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:assembleDebugAndroidTest --offline --console=plain --max-workers=2
.\gradlew.bat :app:assembleDebug :app:lintDebug :app:assembleDebugAndroidTest --offline --console=plain --max-workers=2
git diff --check
adb devices -l
```

PR 独立工作树中 app 57 项单元测试通过。新增 `FloatingPanelInstrumentedTest`，覆盖收起后停止/再次展开，以及 200% 字号下暂停到结束的按钮状态；已编译进测试 APK。ADB 无连接设备，因此未实际运行这两项界面测试，也未完成真机截图、旋转/刘海边界与 TalkBack 验证。

PR 范围：本轮独立工作树仅提交体验与悬浮窗改动。原工作区的任务调度修复及其 13 项测试未纳入本 PR；此前原工作区 app 70 项测试通过，本 PR 独立验证 app 57 项。
