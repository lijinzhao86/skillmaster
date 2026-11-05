# v43-download-domain · 技术设计

> **最后更新**：2026-11-04
> **所属版本**：[`README.md`](README.md)

## 架构

**产物的唯一存放处不变，只换用户面前的那一层地址。**

```
用户 / agent
   │  https://dl.skillmasterai.com/…
   ▼
ECS nginx (443, dl.skillmasterai.com)
   │  proxy_pass + proxy_ssl_verify
   ▼
OSS bucket skillmaster-dl (cn-hangzhou)   ← 产物与 checksums 仍只存在这里
```

- `dl.skillmasterai.com` 的反代、证书与 `proxy_ssl_verify` 已经在生产，本版不改 `dl.` server 块的行为，只改其中的注释。
- 工作流仍然用 OSS endpoint **写**桶、从桶**回读**校验；它们是控制面与产物的权威源，不是用户链接。
- 首页与安装说明只改**展示给用户/agent 的地址**；桶的直连地址继续可用，已装好的旧 CLI 仍从它自查更新。

**改动的面与文件**：

| 面 | 文件 | 改什么 |
|---|---|---|
| 设计件 | `design/src/home/HomeApp.tsx`、`design/src/home/styles.css`、`design/scripts/home-check.mjs`、`design/README.md`、`design/skillmaster-首页演示.html`（构建产物） | prompt 地址；页脚底栏备案号与链接；断言与规格 |
| 官网 | `skillmaster-site/src/App.tsx`、`skillmaster-site/src/styles.css`、`skillmaster-site/src/main.tsx`（仅注释）、`skillmaster-site/tests/home-page.test.tsx`、`skillmaster-site/tests/session.test.tsx`、`skillmaster-site/public/install/skillmaster.md`、`skillmaster-site/README.md` | 同上；安装说明页全部地址与附录 C；README 的备案说明；`main.tsx` 记录样式合并的刻意差异 |
| CLI | `skillmaster-cli/install/install.sh`、`skillmaster-cli/install/install.ps1`、`skillmaster-cli/install/README.md`、`skillmaster-cli/internal/version/version.go`、`skillmaster-cli/RELEASE.md` | 默认 `BASE_URL` / `BaseUrl`；失败处置；`UpdateBase` 注释；发布文档 |
| 平台技能 | `skills/skillmaster/SKILL.md` | 装载入口那一句 |
| 流水线 | `.github/workflows/cli-beta.yml`、`.github/workflows/cli-release.yml`、`.github/workflows/site.yml` | 两个 `UPDATE_BASE` 与投影注释；`site.yml` 只改注释，上传/回读代码不动 |
| 部署文档 | `deploy/README.md`、`deploy/nginx/skillmaster.conf`、`deploy/acme/auth.sh` | 检查命令、§TLS 与备案、端口 80 与 `dl.` 块注释、DNS-01 理由注释 |
| 现状待办 | `docs/current/TODO.md` 的「CLI 自动更新」 | 把「还缺 CLI 的 `dl.` 编译期默认值」改成当前事实 |
| 版本登记 | `docs/README.md`、`docs/versions/README.md` | 生成物：版本数与 v43 索引行 |

## 数据模型

本版不涉及表结构、实体或存储格式变更。

## 接口

**公开下载地址（对外契约）**：

| 通道 | 基址 | 对象 |
|---|---|---|
| 正式 | `https://dl.skillmasterai.com/` | `install.sh`、`install.ps1`、`checksums.txt`、`version.txt`、六个平台包、`releases/<版号>/…` |
| 内测 | `https://dl.skillmasterai.com/beta/` | 同一组对象，另有 `skillmaster.tgz`、`skillmaster.md` |

- 键名与桶里完全相同；`dl.` 只是同一批对象的前门。
- 旧基址 `https://skillmaster-dl.oss-cn-hangzhou.aliyuncs.com` **继续可用**，不在本版下线。
- 安装脚本仍接受 `SKILLMASTER_DL_URL` 覆盖基址（测试与镜像用）；本版只改默认值。
- CLI 的 `version.UpdateBase` 是**编译进去**的：新构建指向 `dl.`，旧二进制保持原值。

**首页页脚（界面契约）**：

- 底栏文案：`© 2026 SkillMaster · 粤ICP备2026172880号`
- 备案号的链接目标：`https://beian.miit.gov.cn/`
- 外观：同色、无下划线，悬停转 `--ink`，键盘焦点沿用页脚的 `--accent` 外圈。

## 分期

| 期 | 交付 | 验收 |
|---|---|---|
| P0 | 设计件与规格：prompt 地址、页脚备案号、`home-check` 断言、`design/README.md` | 第 2 关；A5 / A6 |
| P1 | 官网：`App.tsx`、样式、安装说明页、README、测试 | A1 / A5 / A7 |
| P2 | CLI 与流水线：两个安装脚本、`version.go` 注释、两个 `UPDATE_BASE`、`RELEASE.md` | A1 / A3 / A4 |
| P3 | 部署文档与现状待办；端到端复验 | A2 / A3 / A8 |

## 开放问题

1. **旧桶地址保留到什么时候？** 当前倾向：不设日期，直到不再有在用旧版 CLI 的机器；真要清理时先看 `version.txt` 与发布记录，再另开一版。
2. **控制台要不要也挂备案号？** 当前倾向：本版不做。控制台不是营销首页，设计稿没有这一项；若监管要求覆盖所有入口，先改设计件再实现。
3. **工作流要不要改成从 `dl.` 回读？** 当前倾向：不改。桶是产物的权威源；而且 `deploy/README.md` 记过 runner→ECS 的 TLS 曾经不稳，回读走桶是刻意的。代价是站点发布不覆盖 `dl.` 的可用性——`dl.` 只由 CLI 发布流水线在主机上探一次 `install.sh`，这个空白记在这里。
4. **证书续期要不要从 DNS-01 改回 HTTP-01？** 当前倾向：不改。备案通过后端口 80 已由 nginx 接管，但现有续期配置与钩子都按 DNS-01 工作；换挑战方式是独立的运维改动，本版不碰。
