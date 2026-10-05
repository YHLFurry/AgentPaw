# AgentPaw Icon Resources

AgentPaw 的两套品牌图标资源。SVG 为源文件，PNG 为按尺寸预导出的透明位图，全部随
`agentpaw-core` AAR 打包发布（Maven 坐标 `com.paw.agent:agentpaw-core`）。

## 资源清单（本目录 `src/main/resources/icons/`）

| 文件 | 说明 |
|---|---|
| `agentpaw-logo.svg` | App 主品牌标识，emoji 风格爪印，品牌渐变填充 |
| `agentpaw-logo-mono.svg` | 单色版爪印，`currentColor` 驱动，随宿主变色 |
| `agentpaw-logo-wordmark.svg` | 图标 + 「AgentPaw」横排组合（Inter SemiBold 已转轮廓，零字体依赖） |
| `agentpaw-settings.svg` | 设置图标：五齿齿轮 + 中心爪印，双色（靛蓝齿轮 / 紫色爪印） |
| `agentpaw-settings-mono.svg` | 设置图标单色版，`currentColor` 驱动 |
| `agentpaw-*-16/32/64/128/512.png` | 对应尺寸透明背景 PNG，含 `-mono-black-` / `-mono-white-` 变体 |
| `agentpaw-logo-wordmark-512/128.png` 等 | 横排组合 PNG，含 `-mono-white-` 变体 |

命名规则：小写连字符（kebab-case），`agentpaw-<用途>[-mono][-<色变体>]-<尺寸>.<后缀>`。

## APK 启动图标

App Logo 已直接落地为 APK Logo：`app/src/main/res/drawable/ic_launcher_foreground.xml`
内嵌同一套爪印几何（512 网格按 0.2 缩放居中于 108dp 画布，落在 66dp 安全区内），
背景层 `ic_launcher_background.xml` 为品牌渐变，`<monochrome>` 复用前景（支持 themed icon）。

## 在设置相关选项中的典型引用方式

设置图标以**单色版**为主 —— 单色版所有填充都是 `currentColor`，放进任何主题色的
菜单项 / 配置面板 / 工具栏都会自动跟随内容色，明暗主题通吃。

Jetpack Compose（加载 AAR 内 Java 资源并染色）：

```kotlin
val settingsSvg = IconLoader.load("icons/agentpaw-settings-mono.svg") // 见下文
// 或将 SVG 转为 VectorDrawable 后：
Icon(painter = painterResource(R.drawable.ic_agentpaw_settings), "设置")
Icon(..., tint = MaterialTheme.colorScheme.onSurface)  // currentColor 即随 tint 变色
```

Android XML（菜单 / toolbar）：

```xml
<item android:icon="@drawable/ic_agentpaw_settings" android:title="设置" />
<!-- app:iconTint="?attr/colorOnSurface" 即可实现随主题染色 -->
```

直接读取打包资源（无需转换，适合渲染 SVG 的场景）：

```kotlin
javaClass.classLoader!!
    .getResourceAsStream("icons/agentpaw-settings-mono.svg")!!
    .readBytes()
```

> 建议：菜单项等小尺寸场景优先用单色版 + `tint`；品牌展示位（关于页、空状态）用
> 彩色渐变版。

## 配色

| 色值 | 用途 |
|---|---|
| `#4F46E5` | 品牌靛蓝（渐变起点 / 设置图标齿轮） |
| `#7C3AED` | 品牌紫罗兰（渐变中段 52%） |
| `#9333EA` | 品牌紫（渐变终点 / 设置图标爪印） |
| `#18181B` | 单色版默认墨色（浅色底用；深色底请用 `#FFFFFF`） |

App Logo 渐变方向 135°（左上 → 右下），参数与 `app` 模块启动图标的背景层一致。

## 最小安全边距

- **App Logo（512 画布）**：图形 bbox `x 104..408 / y 132..398`，左右安全边距
  **104px（画布的 20.3%）**、上下 **132px**。外部容器再留白时，建议图形占位不小于
  可用区域的 60%。
- **设置图标（512 画布）**：齿尖到画布边 34px（6.6%），齿轮为旋转对称图形，四周等距。
- **横排 wordmark**：图标左侧 8px + 图标/文字间距 32px（128px 高画布）。

## 重新导出

源文件与导出脚本在仓库内维护：

```bash
# wordmark 重新生成（Inter 转轮廓）
python build/tmp-icon-gen/gen_wordmark.py
# 全尺寸 PNG 重导出（需 node + @resvg/resvg-js）
NODE_PATH=<node_modules> node build/tmp-icon-gen/export.js
```
