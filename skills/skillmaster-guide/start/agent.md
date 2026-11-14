# 让 Agent 每次都记得用 skmr

> 两件事：把 `skmr` 的说明写进 Agent 的常驻文件，让它先查库再用；第一次接入用 `skmr startup` 走一遍新人引导（主线约 10 分钟；电脑上原来有技能的话，最后可以再传上来）。

## 写进 Agent 文件

```bash
skmr prompt print
```

把打印出来的整段放进 Agent 的常驻文件：Claude Code 用 `CLAUDE.md`；Codex 用 `AGENTS.md`（有 `AGENTS.override.md` 时它优先）。其他客户端用哪个文件由它自己决定，不确定就问它。

- **完成信号**：新会话里问「你能用 SkillMaster 做什么」，Agent 会先跑 `skmr skills search`。

## 第一次接入

```bash
skmr startup
```

先确保这台机器登录；然后加载官方入门技能 `skillmaster/skillmaster-startup`，正文原样打印出来，照它做。

- **完成信号**：正文里出现三块主线——找 → 加进库并加载 → 做一条自己的并发布（第三块是可选的批量搬本机技能）。

## 没有浏览器时

在 https://console.skillmasterai.com/account/keys 签一把 `skm_…`，放进 `SKILLMASTER_API_KEY`，用 `skmr auth status` 验证。

## 接入时要注意的

- **`prompt print` 断网也能用**：那段说明随 CLI 发布，不从服务端取。
- **写完要重新开会话**：常驻文件只在会话开始时读。
