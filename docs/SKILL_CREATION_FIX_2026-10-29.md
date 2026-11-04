# 新建技能闪退修复

## 根因

一加 Ace 6 / Android 16 的堆栈显示，新建技能调用 `SkillValidator.validate()` 时首次初始化 `SkillInterpolator`，其正则中的裸右花括号使 Android ICU 抛出 `PatternSyntaxException`，继而包装为 `ExceptionInInitializerError`，直接终止主线程。即使新建技能尚未引用变量，校验动作参数也会触发该初始化。

## 修复

`agentpaw-core/src/main/java/com/paw/agent/core/skill/SkillInterpolator.kt` 显式转义两个模板分支的全部右花括号，保留 `{{name}}`、`${name}`、默认值、点分变量名及缺失变量提示的原有语义。

## 验证

- 使用本机原生 ICU C API 对同一表达式执行前后对照：旧表达式编译失败，状态码 `66305`；修复后编译成功，状态码 `0`。
- JDK 21 下完整单元测试：385 项，383 项通过，2 项既有测试跳过，0 失败。
- 其中 9 项本地 UI 测试覆盖两套主题、中文、Android 12/14/16、大字体、完整应用入口，以及 Android 16 原生绘制路径。
- 新增模板提取和字面花括号回归测试。
- 新增 3 项 Android 设备测试，覆盖空白技能及内置技能校验、两种占位符与默认值、缺失变量和字面花括号；已编译进测试 APK。
- Debug 应用和 Android 测试 APK 构建通过，应用及核心模块 Lint 无错误。
- CI 的设备测试矩阵补充 API 36，保留 API 26、35。

宿主 JVM 与 Robolectric 的正则行为不能代替 Android ICU，因此专门保留 Android 设备回归测试。当前未连接手机，未在本机执行设备测试；一加真机修复效果需要安装新版 APK 验收。

本地验证命令（JDK 21）：

```powershell
./gradlew.bat :app:testDebugUnitTest :agentpaw-core:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug :agentpaw-core:lintDebug --offline
```
