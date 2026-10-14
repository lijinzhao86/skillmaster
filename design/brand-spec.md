# SkillMaster · 品牌资产

**来源**：SkillMaster 自己的仓库（`skillmaster-web/src/styles.css`）——这是本产品一切视觉事实的权威位置，优先于任何外部检索。取用日期 2026-10-09。

## 实体

- 产品名：**SkillMaster**
- 一句话：技能托管服务。作者把 AI 技能（一个 `SKILL.md` + 若干文件）发布成**带版本**的 skill；网页端是管理 skill、版本、共享授权的控制台。
- 受众：**开发者 / 技术用户**，不是消费级用户。
- 交付形态：Go CLI（`skillmaster`）+ Vue 3 网页控制台。

## 标记（logomark）

三条左对齐、长度递减的圆角横杠，纯色无渐变。24×24 viewBox：

```html
<svg viewBox="0 0 24 24" aria-hidden="true">
  <rect x="3" y="4"     width="18" height="4.5" rx="2.25" fill="currentColor"/>
  <rect x="3" y="10.5"  width="13" height="4.5" rx="2.25" fill="currentColor" opacity="0.6"/>
  <rect x="3" y="17"    width="8"  height="4.5" rx="2.25" fill="currentColor" opacity="0.32"/>
</svg>
```

## 调色板（浅色 / 暗色）

| 用途 | 浅色 | 暗色 |
|---|---|---|
| 正文 | `#1f2328` | `#e6edf3` |
| 次要文字 | `#59636e` | `#9198a1` |
| 占位符 | `#8b949e` | `#6e7681` |
| 分隔线 | `#d8dee4` | `#30363d` |
| 控件描边 | `#b7c0c9` | `#444c56` |
| **主色（链接 / 强调）** | `#2563eb` | `#6ea8fe` |
| 主色 hover / active | `#1d4ed8` / `#1e40af` | `#8bbaff` / `#5591e6` |
| 主色上的文字 | `#ffffff` | `#0d1117` |
| 主色底 tint | `#eef4ff` | `#121d33` |
| 危险 | `#b42318` | `#ff9c92` |
| 危险底 tint | `#fef3f2` | — |
| 成功 | `#1a7f37` | `#3fb950` |
| 成功底 tint | `#eaf7ee` | — |
| 卡片面 | `#ffffff` | `#161b22` |
| 悬停面 | `#f3f5f7` | `#1c232c` |
| 页面底 | `#f6f8fa` | `#0d1117` |
| 增 / 删行底 | `#e6ffec` / `#ffebe9` | — |

**主色只有一个。** 它是全站唯一的强调色，用在链接、主按钮、当前项与「在等你决定」的那块面板上。

## 字体与尺度

- 正文栈：`-apple-system, BlinkMacSystemFont, "Segoe UI", "PingFang SC", "Hiragino Sans GB", "Microsoft YaHei", sans-serif`
- 等宽栈：`ui-monospace, SFMono-Regular, Menlo, Consolas, monospace`（文件路径、代码、diff、skill 地址）
- 正文 15px / 行高 1.6；`h1` 1.35rem/700；`h2` 1.25rem/700；`h3` 1rem/600
- 圆角：控件 6px，卡片 / 面板 10px，胶囊 999px
- 过渡：统一 `140ms cubic-bezier(0.4, 0, 0.2, 1)`
- 阴影两级，都极轻：卡片 `0 1px 2px rgba(31,35,40,.06)`，浮层 `0 4px 14px rgba(31,35,40,.08)`

## 气质与禁区

- 气质：**克制的企业级开发者工具**，参考 GitHub / Linear 的密度与层级。
- 明确禁止：大圆角、大阴影、玩具感、装饰性图标堆砌、emoji 当图标、紫色渐变。
- 组件语义（必须一致）：状态胶囊 = 小圆点 + 文字 + 底色，**颜色永远不是唯一信号**；待决策面板是页面上唯一有色的面板。
- 控件复刻业内，不自创：密码可见性图标在输入框内右侧；版本用原生下拉；导航是 GitHub 那种「小标题 + 条目」的分组形状。

## 真实产品截图（内容必需的图）

`design/assets/` 下有已落地的真实界面截图，是本产品**唯一的图类资产**：

| 文件 | 内容 |
|---|---|
| `console-light.png` / `console-dark.png` | 侧栏外壳 + 我的 skill 列表 |
| `login-light.png` / `login-dark.png` | 登录页 |

它们是真 UI，不是渲染图——**任何方向都不得用 CSS 色块或手绘 SVG 替代**。
