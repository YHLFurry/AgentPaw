# 第三方源码：miuix-glass（vendor 进 app 模块）

`app/src/main/java/top/yukonga/miuix/kmp/glass/**` 是 **vendored 的第三方源码**，不是本项目写的。

- 来源：[compose-miuix-ui/miuix](https://github.com/compose-miuix-ui/miuix) 的 `miuix-glass` 模块
  （上游未把它发布为 Maven artefact，官方推荐做法就是随应用源码 vendor，Termux Ultra 亦如此）。
- 许可：Apache-2.0（每个文件头部都保留 `SPDX-License-Identifier: Apache-2.0`）。
- 同步方式：整文件拷贝，**不要就地魔改**；需要改行为时在本项目的 `App*` 适配层里改，
  或在文件里加注释说明魔改点。当前唯一的一处裁剪见下。

## 依赖补齐

glass 只用到三部分外部能力，本项目按下表补齐：

| glass 需要 | 本项目提供方式 |
| --- | --- |
| `top.yukonga.miuix.kmp.blur.*`（背景层、模糊管线、AGSL 效果链） | `miuix-blur-android:0.9.1`（Maven Central，与 miuix 主线同版本） |
| `top.yukonga.miuix.kmp.shader.{RuntimeShader, asBrush, isRuntimeShaderSupported}` | 自写薄封装 `top/yukonga/miuix/kmp/shader/RuntimeShader.kt`（0.9.1 没有 miuix-shader artefact） |
| `top.yukonga.miuix.kmp.anim.folmeSpring` | 自写薄封装 `top/yukonga/miuix/kmp/anim/FolmeSpring.kt`（0.9.1 的 anim 包只有 easing） |

## 已裁剪的部分

- `GlassTopAppBar`（大标题可折叠版）及其 `BlurTopAppBar` 依赖被移除：
  miuix 0.9.1 的 `basic` 包没有 `BlurTopAppBar`。本项目全部页面用不折叠的
  `GlassSmallTopAppBar`，与原本的 `SmallTopAppBar` 一一对应。
- `GlassIconButton` 里与 `GlassTransformPopup` 共享 backdrop 的 anchor 逻辑被移除
  （popup 相关文件未 vendor，它们还依赖 `androidx.navigationevent` 与 glass 图标集）。
- popup / navigation bar / tab row 相关文件未 vendor——本项目暂未使用。

## minSdk

`miuix-blur` 声明 `minSdk 32`，而本项目仍要支持到 26，因此在 `AndroidManifest.xml` 里加了
`tools:overrideLibrary="top.yukonga.miuix.kmp.blur"`。安全前提：该库只在 Miuix 主题下被玻璃
顶栏用到，且内部按 `SDK_INT` 降级，不支持的设备走 `GlassSmallTopAppBar` 的实色填充分支。
