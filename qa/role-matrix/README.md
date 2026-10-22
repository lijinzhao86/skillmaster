# 角色矩阵（role matrix）

**一句话**：六个账号、一条梯子（invoke < browse < write < maintain < admin < owner），把本地这套服务**从外面**量一遍——CLI、浏览器面、控制台页面各一层，每个门都问「这一档允许吗」。

它不是一个临时脚本，而是一个可以一直跑的测试项目：夹具由 `seed` 造，断言集中在 `role_matrix/expectations.py` 一张表里，加一档、加一个探针、加一层界面都只改一处。

## 先决条件

- 本地服务已经起来：服务端 8080，Vite 5173（`/web`、`/api/v1`、`/oauth` 都从 5173 代理进去）。
- `uv`（`~/.local/bin/uv`）。
- 跑控制台那一层时本机要有 Chrome——用 `channel="chrome"` 直接驱动已装的浏览器，**不下载任何浏览器**。
- 服务端开了 `SKILLMASTER_SMS_ACCEPT_ANY_CODE=true`（`seed` 注册账号时用固定的验证码；真短信的栈会卡在注册那一步）。

## 第一次：配置与造夹具

```bash
cd qa/role-matrix
cp .env.example .env        # 六个角色的用户名 / 手机号 / 密码都在这一个文件里（密码是占位符，填自己的）
uv run python -m role_matrix.seed
```

`seed` 做的事：

| 步骤 | 说明 |
|---|---|
| 账号 | 先登录；登录说「没这个账号」才走注册（验证码从本地库里解——仅限本地库，见 `role_matrix/captcha.py`） |
| 夹具 skill | `ROLE_MATRIX_SKILL`：线上 1.0.2 · 历史 1.0.0 · 已下架 1.0.1 · 一个已丢弃的草稿 · 一个留着的草稿 |
| 临时 skill | `ROLE_MATRIX_SCRATCH`：0.9.0（历史）+ 1.0.0（线上），所有**会改变内容**的探针都在它身上跑 |
| 授权 | 两个 skill 都按梯子共享给五个非 owner 账号 |
| 旁证账号 | `role-bystander`：存在、但没有任何授权（用来探「撤销一个没被共享过的人」） |

它**不会删除或改写任何 skill**。夹具已经存在且身份还在 `.cache/fixture.json` 里，就原样复用；身份丢了会直接报错，让你换一个名字，而不是替你清空。

## 跑

```bash
uv run pytest            # CLI + 浏览器面（约 2 秒）
uv run pytest --ui       # 再加控制台：2 个屏 × 6 档 = 12 条用例，约 30 秒
```

| 文件 | 量什么 |
|---|---|
| `tests/test_cli_matrix.py` | 每个梯子 × 每个命令：`skill files` / `cat` / `versions` / `skill shares` / `version show·diff·list` / `publish`（所有档都拒绝，且交接到 `version open … --sub actions`）/ 自己草稿的写·改·注·丢弃 |
| `tests/test_web_matrix.py` | `/web/**` 的读与写门：`rung` 视图、grants、access-requests、publish（当前版本重复上线 = 不写入）、offline、revoke；再加 owner 在临时 skill 上的 offline/online、revoke/restore |
| `tests/test_ui_matrix.py` | 真 Chrome：版本列表的**行数 / 筛选器 / 新建按钮**，以及 CLI 交接打开的**操作页**——选中哪一档 bump、版本号字段、按钮文案；打不开的那一档是「没有这一版」 |

控制台那层每档出两张图，落在 `output/`（不跟踪）：`<档>-list.png`、`<档>-handoff.png`——invoke / browse / write / maintain / admin / owner 各一对。**截图不是视觉验收**——它们给看的人用，断言只证明结构没坏。

## 目录

```
role_matrix/
  config.py        .env → 设置与六个账号（环境变量优先）
  expectations.py  梯子与所有期望 —— 唯一一处「谁可以做什么」
  cli.py           从本仓库构建 CLI，用一个档的凭据跑它
  web.py           浏览器面：会话、CSRF、登录、签 API key
  roles.py         每个档一把 key，缓存在 .cache/，失效时自动重签
  captcha.py       注册时解验证码（dev 专用，拒绝非本地库）
  seed.py          造账号、夹具、授权
  state.py         .cache/fixture.json：夹具的版本号与草稿码
  ui.py            Playwright + 系统 Chrome（截图写到 output/）
tests/
  test_cli_matrix.py / test_web_matrix.py / test_ui_matrix.py
output/            截图，不跟踪
```

## 加东西

- **加一档**：`config.RUNGS` 插进去 → `.env` 加 `ROLE_MATRIX_<档>_*` → `expectations.py` 的门自然按等级算。
- **加一个 CLI 探针**：往 `CLI_READ_PROBES` 加一行 `(名字, 最低档, 参数)`；`None` 表示任何档都该拒绝。
- **改一个门**：只改 `expectations.py` 里那个最低档；所有层的断言跟着走。
- **加一屏**：在 `ui.py` 里加一个读取方法，在 `test_ui_matrix.py` 里加一个按档参数化的断言。

## 它不做什么

- **不点上线 / 不点离线**：那是人的动作，也会改变后面每一档看到的东西。允许侧的 offline / revoke 只在临时 skill 上跑，跑完放回去。
- **不碰夹具的内容**：所有会留下痕迹的写入（废一个草稿、撤一版、下线）都落在 `ROLE_MATRIX_SCRATCH` 上。夹具只读——控制台断言的 5 行 / 3 行才立得住。
- **不改产品代码**：发现门不对，改的是 `expectations.py` 或回写文档，不是让测试绕过去。
