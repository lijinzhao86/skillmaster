# v72-fixed-labeling · 技术设计

> **最后更新**：2026-11-14
> **状态**：实现中（代码与测试已落；金标集测量待跑）
> **所属版本**：[`README.md`](README.md)
> **决策**：[ADR 0193](../../decisions/0193-public-labels-are-extensible-claims.md)（标签是可扩展主张）· [ADR 0196](../../decisions/0196-label-extraction-reads-public-text-only.md)（只读公域文本）· [ADR 0197](../../decisions/0197-production-tables-hold-only-final-claims.md)（生产表只存最终结果）· [ADR 0198](../../decisions/0198-api-planes-are-named-by-audience.md)（`/inner/v1` 是服务间面）· [ADR 0208](../../decisions/0208-fixed-rules-are-a-label-producer.md)（规则是独立产出者）
> **这一版只写与 [`current/`](../../current/README.md) 的差异。** 模块边界、命令语法、配置键见 [`system.md`](../../current/system.md)、[`cli.md`](../../current/cli.md)、[`config.md`](../../current/config.md)；标签词表的唯一权威是 [v66 PRD §运行环境词表](../v66-skill-labels/prd.md#运行环境词表21-个非默认值--默认)。

## 架构

**一句话**：OPS 持有一个纯规则函数与每次评估的证据；server 持有最终表与三个 `/inner/v1` 接口。导入与刷新调用同一个函数，规则输出直接替换一条 skill 的 `runtime` 标签集合。

### 组件与职责

| 组件 | 改动 | 边界 |
|---|---|---|
| **OPS · `labels`** | `RuleLabeler`（纯函数）、`SkillFacts`、`LabelClaim`、`Evidence`、`RuleSet`（规则表与 `rule_version`） | 不依赖 Spring、不读网、不读库；输入只有 `SKILL.md` 文本、文件清单与 frontmatter |
| **OPS · 导入钩子** | `ImportJob` 在提交成功后调用 `RuleLabeler`，写 OPS 库、推 server；失败只记结果 | 不改导入的判定与草稿写入 |
| **OPS · 刷新任务** | `LabelRefreshJob` + `POST /inner/v1/jobs/label/runs`；分页遍历公域、逐条调用、整组替换 | 与导入同一把 `job_lock` 机制，`kind='label'` |
| **OPS · 存储** | 迁移 V2：`label_claim`、`label_entry`、`job_lock` 的 `label` 行 | 证据与规则版本的唯一权威 |
| **server · `/inner/v1`** | 新 `InnerLabelController`：列公域、读当前版本文本、替换标签；沿用 `X-Inner-Token` 与私有端口 | 只被 ops 调用；不对外、不发用户 |
| **server · M12** | `LabelWriteService.replaceRuntimeLabels(skillId, definitionIds)`；`LabelRepository` 读模型简化 | 只写最终表，不判断规则 |
| **server · 迁移 V55** | `skill_label` 收成两列；删 `label_extraction_run` / `label_audit_sample`；删 `skill.outcome_*` 七列 | 生产库当前无标签行，迁移不丢数据 |

### 数据流

**导入（新 skill 或新版本）**

1. `ImportJob.prepare` 已经拿到 `files`、`SKILL.md` 与 frontmatter。
2. 提交成功后，用同一份 `files` 调 `RuleLabeler.classify(facts)` → `List<LabelClaim>`。
3. 调 `PUT /inner/v1/skills/skillmaster/{name}/labels`（带 `version_id`）整组替换。**若这条 skill 已有另一个上线版本，server 回 `applied=false`**：草稿的标签不覆盖线上版本，等该版本上线后的刷新再写。
4. 写 OPS：`label_claim` 每次评估一行、`label_entry` 记这一条的结果（`labeled` / `deferred` / `push_failed` / `failed`）；`run_id` 是这次导入的 `job_run`。**先推后写**：`label_entry` 是下一次刷新的跳过键，不能为一次没推成功的评估留下「已处理」。
5. 推送失败不改写导入结果：`label_entry.result='push_failed'`，下一次导入或刷新补。

**刷新（全部公域）**

1. `POST /inner/v1/jobs/label/runs` 取 `kind='label'` 锁、建 `job_run`、交给 worker。
2. 分页 `GET /inner/v1/skills?cursor=…`，每条带 `skill_id / namespace / name / current_version_id`。
3. 对每条：若最近一次成功评估的 `(current_version_id, rule_version)` 与这次相同，记 `unchanged` 跳过；否则 `GET …/content` 取 `SKILL.md` 与文件清单，调同一个 `RuleLabeler`。
4. 写 OPS，再 `PUT` 整组替换；服务器上不再命中的值因此被删除。
5. 每条独立记结果；一条失败不中断整轮。

### 信号与判定模型

**默认负向。** 没有强信号就不写这个值。不做「同类推断」——没写操作系统的 skill 不标任何 OS，不是把三个都标上；没有证据的运行时不猜。

| 信号 | 来源 | 强度 | 用途 |
|---|---|---|---|
| `F` frontmatter | `compatibility`、`allowed-tools` | 强 | 作者显式声明：OS 限定、API key、联网工具 |
| `R` 前置声明 | Requirements / Prerequisites / 安装 / 前置 / 依赖 / 要求 小节里的显式句 | 强 | 显式要求：Docker、GPU、浏览器、账号 |
| `C` 命令行 | shell 类代码块、`$ ` 开头的行、以已知可执行名开头的行内代码 | 强 | 语言与工具的实际调用 |
| `M` 文件清单 | 扩展名、清单文件、锁文件 | 强 | 语言运行时（文件在，运行时多半在） |
| `E` 环境变量名 | 命令行或 shell 代码块里的 `*_API_KEY` / `*_TOKEN` / `*_SECRET` / `*_ACCESS_KEY` | 强 | `api-key` |
| 正文提及 | 描述、简介、正文散文里出现关键词 | **弱，不命中** | 只用于解释，不产生标签 |

**代码块的语言决定它是不是命令。** 只有 `bash` / `sh` / `shell` / `zsh` / `console` / `terminal` / `powershell` / `ps1` / `bat` / `cmd` 或无语言信息的围栏，以及 `$ ` 开头的行，才进入 `C`；`yaml` / `json` / `dockerfile` 围栏里的 `docker` 不是命令。

**证据规则。** 每个值按 §规则表「证据首选」那一列的顺序取一条；同一类里取 `SKILL.md` 行号最早的那一行，行号相同再按 relpath。证据形状：

```json
{"kind": "command", "file": "SKILL.md", "line": 18, "quote": "docker run --gpus all ..."}
{"kind": "file", "file": "requirements.txt", "line": 0, "quote": "requirements.txt"}
{"kind": "frontmatter", "file": "SKILL.md", "line": 4, "quote": "compatibility: macOS only"}
```

`line > 0` 时 `quote` 必须等于该行去掉首尾空白后的内容；`line = 0` 时 `quote` 必须是清单里的 relpath；围栏语言证据的 `quote` 是围栏首行。校验不过的值不写，并计入 `rejected`。

### 准确率与召回怎么保证

规则不可能既全又要：**精度优先**——宁可少标，不把用户带错方向；召回用多信号覆盖来补，用金标集测量后逐条加规则。

1. **精度闸**：有歧义的值（`gpu` / `docker` / `database` / `online-service` / `account`）只认命令或前置声明，不认文件存在与正文提及；OS 只认「仅 / only」限定。
2. **召回覆盖**：语言值同时看扩展名、清单 / 锁文件、代码围栏与命令行——一条 skill 只要有其中任何一类事实就命中。
3. **金标集**：从同步白名单里分层抽 100–200 条真实 skill（固定随机种子），人工逐值标注存在 / 不存在与证据；用同一份 `SkillFacts` 跑规则，算每个值的 precision / recall。
4. **门槛**（首轮实测后可按数据调整，改门槛要写回本文）：有 ≥10 个正样本的值，**precision ≥ 95%**；语言 10 个值 **recall ≥ 90%**；工具与凭据 **recall ≥ 80%**；OS 值 precision ≥ 98%（只认显式限定）。
5. **迭代**：每次改规则必须重跑金标集，结果写进 [`test-plan.md`](test-plan.md) §结果；`rule_version` 递增。
6. **精度与召回的取舍由数据决定**（所有者 2026-11-14）：首个金标集之前按本文的严格口径跑，不在没有数据时放宽；跑完若召回不足，逐条加规则，不做同类推断。

### 边界与不变量

- **纯函数**：`RuleLabeler` 不依赖 Spring、不读网、不读库；同一 `(facts, rule_version)` 永远同一输出。
- **只写 `runtime` 维度**：不写 `pricing_type`、分类、`outcome`。
- **整组替换**：一次评估的结果就是这条 skill 的完整规则标签集；旧值不在新集合里就删。
- **生产表只有两列**：证据、规则版本、运行记录都不进在线库（[ADR 0208](../../decisions/0208-fixed-rules-are-a-label-producer.md)）。
- **只有官方命名空间可以收标签**：内接口拒绝其他命名空间，避免把公域标签写到私有 skill。
- **导入不因打标失败而失败**：打标与推送是导入成功之后的附加步骤。
- **刷新不覆盖非规则产出者**：本版没有其他产出者；Agent 期接入前必须先定优先级（开放问题 8）。

## 规则表

下面是**触发条件的唯一权威**。`M` 指文件清单（不区分大小写匹配扩展名，文件名按清单里的相对路径），`C` 指命令行，`F` 指 frontmatter，`R` 指前置声明，`E` 指环境变量名。每个值「任一」命中即成立；同一 skill 可以同时命中多个值。

**命令行的口径**（金标集 2026-11-14 实测后写死）：命令只来自带语言的 shell 围栏、`$` 开头的行、以及「已知可执行文件开头且带参数」的内联代码；一行按 `|` / `||` / `&&` / `;` 拆段，每段都算（`curl … | python3 -c` 命中 python）；行首的 `KEY=value` 赋值不算可执行文件；引用了外部链接（`](https://…)`）的行里的命令视为引用，不算步骤。

**结构化声明优先于正文**：frontmatter 里作者明确写下的 `metadata.requires.bins` / `credentials` / `skills`、`prerequisites.commands`、`platforms` 都直接参与判断，且证据指向那一行——它们是声明，不是提及。`bins` 与 `prerequisites.commands` 用同一张「可执行文件 → 值」映射（python/node/java/go/rust/dotnet/ruby/php/c-cpp/shell/docker/database/browser），外加 `lark-cli` / `curl` / `gh` / `az` / `aws` / `gcloud` / `kubectl` / `skmr` / `modal` / `runpod` / `replicate` / `vercel` / `netlify` → `online-service`。

**不用 shebang、也不读 `SKILL.md` 以外的文件内容**：导入时 OPS 手上有全部文件，刷新时 server 只给 `SKILL.md` 与文件清单；只靠清单能保证两条路径看到同一份事实。

### 语言运行时

| 值 | 命中条件（任一） | 明确不算 | 证据首选 |
|---|---|---|---|
| `python` | `M`：`*.py`、`requirements.txt`、`pyproject.toml`、`setup.py`、`setup.cfg`、`Pipfile`、`Pipfile.lock`、`uv.lock`、`poetry.lock`；`C` 首词 `python` / `python3` / `pip` / `pip3`，或 `uv` / `poetry` / `conda` 后接 `run` / `install` / `sync`；围栏 `python` | 只在描述里写 Python；只在「其他语言」列表里出现 | `C` |
| `node` | `M`：`package.json`、`package-lock.json`、`pnpm-lock.yaml`、`yarn.lock`、`bun.lockb`、`*.js` / `*.mjs` / `*.cjs` / `*.ts`；`C` 首词 `node` / `npm` / `npx` / `pnpm` / `yarn` / `bun` | 只在描述里写 Node / React；`*.jsx` / `*.tsx`（浏览器视图组件）；`starter-components/` 下的 `.js`；`js` / `ts` 围栏（ECharts 配置与浏览器片段同语言） | `C` |
| `java` | `M`：`pom.xml`、`build.gradle`、`build.gradle.kts`、`settings.gradle`、`settings.gradle.kts`、`*.java`；`C` 首词 `java`（`-jar`）、`mvn`、`mvnw`、`gradle`、`gradlew`；围栏 `java` | 只在描述里写 Spring / Java | `C` |
| `go` | `M`：`go.mod`、`go.sum`、`*.go`；`C` 首词 `go` 且后接 `run` / `build` / `test` / `mod` / `get` / `env`；围栏 `go` | 单独提到 Golang 而没有任何事实；`go install`（编译出的是原生二进制，装完不需要 Go，且常与 brew/脚本并列成安装方式） | `C` |
| `rust` | `M`：`Cargo.toml`、`Cargo.lock`、`*.rs`；`C` 首词 `cargo`；围栏 `rust` | 只提到 Rust | `C` |
| `dotnet` | `M`：`*.csproj`、`*.fsproj`、`*.vbproj`、`*.sln`、`*.cs`、`*.fs`；`C` 首词 `dotnet`；围栏 `csharp` / `fsharp` | 只提到 .NET / C# | `C` |
| `ruby` | `M`：`Gemfile`、`Gemfile.lock`、`*.gemspec`、`*.rb`；`C` 首词 `ruby` / `bundle` / `gem`；围栏 `ruby` | 只提到 Rails / Ruby | `C` |
| `php` | `M`：`composer.json`、`composer.lock`、`*.php`；`C` 首词 `php` / `composer`；围栏 `php` | 只提到 PHP | `C` |
| `c-cpp` | `M`：`CMakeLists.txt`、`Makefile`、`meson.build`、`*.c` / `*.cc` / `*.cpp` / `*.cxx` / `*.h` / `*.hpp`；`C` 首词 `gcc` / `g++` / `clang` / `clang++` / `make` / `cmake` / `ninja`；围栏 `c` / `cpp` | 只看得到 `make` 出现在文档里而不是命令 | `C` |
| `shell` | `M`：`*.sh` / `*.bash` / `*.zsh`；`C` 首词 `bash` / `zsh` 且后接脚本路径或 `-c` | 步骤里出现 `ls` / `cd` / `echo` 等普通命令；`allowed-tools: Bash`；任何 skill 都有的看文件命令 | `M` |

### 操作系统（只在限定的时候标）

| 值 | 命中条件 | 明确不算 | 证据 |
|---|---|---|---|
| `macos` | `F` 的 `platforms` 列表（少于三个桌面系统时，列出的就是限定）；`F` 或 `R` 的显式限定：`only on macOS`、`macOS only`、`仅 ?macOS`、`只支持 ?macOS`、`requires macOS` | 「支持 macOS / Linux」这类正文平台列表；`platforms` 同时列出 macOS/Linux/Windows（等于不限定）；正文提到 macOS | `F` |
| `windows` | 同上，针对 Windows（`only on Windows`、`仅 ?Windows`、`requires Windows`） | 出现 `*.ps1` / `*.bat` 文件但没有任何限定；正文平台列表；三者齐全的 `platforms` | `F` |
| `linux` | 同上，针对 Linux | 「跨平台」；提到 Linux 服务器作为部署示例；三者齐全的 `platforms` | `F` |

**没写限定就不标任何 OS**（产品负责人 2026-11-14 明确）。同一处限定两个系统（「仅 macOS 与 Linux」或 `platforms: [linux, macos]`）就标两个；三个桌面系统全列就是跨平台，一个都不标。

### 工具与资源

| 值 | 命中条件 | 明确不算 | 证据 |
|---|---|---|---|
| `docker` | `C` 首词 `docker` 或 `docker-compose`（`build` / `run` / `compose` / `pull` / `exec`）；`R` 显式写 `requires Docker / 需要 Docker` | 只有 `Dockerfile`；生成 compose / K8s 清单；把 Docker 当教学主题；正文提 Docker | `C` |
| `gpu` | `C` 含 `torchrun`、`nvidia-smi`、`--gpus`、`CUDA_VISIBLE_DEVICES`；`R` 显式写 `requires an? NVIDIA GPU / 需要 ?GPU / CUDA` | 云 GPU（Modal / RunPod / Colab / SageMaker）单独出现；只讲 GPU 优化但可 CPU 跑 | `C` |
| `database` | `C` 首词 `psql` / `mysql` / `mariadb` / `mongosh` / `redis-cli` / `sqlcmd`；`C` 含 `EXPLAIN ANALYZE`；`C` 首词 `flyway` / `liquibase` / `alembic` 且后接 `migrate` / `update` / `upgrade`；`R` 显式写 `requires a database / 需要连接数据库` | 教 SQL、设计 schema、查公开数据库 API（后者是 `online-service` + `api-key`）；`sqlite3` 本地文件库 | `C` |
| `browser` | `C` 首词 `playwright` / `puppeteer` / `selenium` / `chromedriver`；`C` 里出现 `browser.js` / `*browser*.py` 这类浏览器脚本；`F` 的 `allowed-tools` 含浏览器工具；`R` 显式写 `requires a browser / 需要浏览器` | 生成 HTML / 打开网页；正文提浏览器；HTML 里引用的 JS 库 | `C` |
| `online-service` | `F` 的 `allowed-tools` 含 `WebFetch` / `WebSearch`；`F` 的 `description` 写明联网 / 在线搜索；`F` 的 `bins` 或 `prerequisites` 含远程服务 CLI；`R` 显式写 `requires internet / 联网 / 在线服务 / 网络访问`；`C` 首词是 `modal` / `runpod` / `replicate` / `curl` / `wget` / `gh` / `az` / `aws` / `gcloud` / `kubectl` / `skmr` / `lark-cli`；`C` 含非 localhost 的 `http(s)://` URL | 文档里的 `curl` 示例（引用外部链接的行）；正文提 API；`skmr skill read` / `invoke`（只是加载自己的说明） | `F` |
| `system-permission` | `C` 首词 `sudo`；`R` 显式写 `requires administrator / root / 系统权限 / 无障碍权限` | `chmod`；普通文件写权限 | `C` |

### 凭据

| 值 | 命中条件 | 明确不算 | 证据 |
|---|---|---|---|
| `api-key` | `E` 命中：命令行、任意代码围栏或内联代码里的全大写变量名 `[A-Z][A-Z0-9_]*(API_KEY|APIKEY|TOKEN|SECRET|ACCESS_KEY)`；`F` 的 `metadata.requires.credentials` 含同类变量名；`R` 显式写 `requires an? API key / 需要 ?API ?key / 密钥`；`F` 的 `compatibility` 写 API key | 只在正文提到「API key」而没有变量或命令；小写 payload 字段 `refresh_token` / `doc_token` / `user_access_token`；`--doc <doc_token>` 这类参数占位 | `E` |
| `account` | `C` 是登录流程：任意 CLI 的 `X login` 或 `X auth login/status/whoami`（`gh` / `lark-cli` / `az` / `aws configure` / `gcloud` / `docker` / `npm` / `vercel` / `netlify` …）；`C` 带 `--as user`（以用户身份访问云端数据，必须先登录）；`R` 显式写 `requires an account / 需要账号 / 登录` | 把账号说成可选项；正文提到账号；`--as bot`（应用身份，不是读者的账号） | `C` |

### 默认值

| 值 | 命中条件 | 明确不算 |
|---|---|---|
| `prompt-only` | 以上非默认值**一个都没命中**，且没有脚本 / 清单 / 代码文件，`SKILL.md` 里也没有可执行命令行；也没有子目录说明文件（`references/*.md` 等）、没有 `metadata.requires.skills`、正文不通过 `skill_view` / `skmr skill read` 转交给另一个 skill | 只带 `Dockerfile` / 配置文件的 skill：不写任何标签，也不写 `prompt-only`；路由型 skill（自身是索引、能力在别处）不算纯提示词 |

`prompt-only` 写进最终表（筛选要用），但读模型不返回、界面不画（v66 PRD 已定）。

### 正反例（来自 v66 PRD，作为夹具基准）

| skill | 期望 |
|---|---|
| `orchestra-training-llms-megatron` | `docker` + `gpu`（quickstart 是 `docker run --gpus all`） |
| `jeffallan-devops-engineer` | 不标 `docker`（只产出 Dockerfile 与流水线示例） |
| `jeffallan-sql-pro` | `database`（Verify 步骤跑 `EXPLAIN ANALYZE`） |
| `database-lookup` | `online-service` + `api-key`，不标 `database`（查公开数据库 API） |
| `orchestra-modal-serverless-gpu` | 不标 `gpu`（云 GPU，归 `online-service` + `api-key`） |

## 数据模型

### OPS 库（迁移 V2）

```sql
-- 每次评估的命中集合；不覆盖历史。当前集合 = 最近一次 run 的行。
CREATE TABLE label_claim (
  id            TEXT PRIMARY KEY,
  run_id        TEXT NOT NULL REFERENCES job_run (id) ON DELETE CASCADE,
  origin        TEXT NOT NULL,             -- import | refresh
  skill_id      TEXT NOT NULL,             -- server 的 skill id
  namespace     TEXT NOT NULL,             -- 目前只有 skillmaster
  name          TEXT NOT NULL,
  version_id    TEXT,                      -- 评估的是哪一版（导入的草稿 / 刷新的当前版）
  definition_id TEXT NOT NULL,             -- runtime:python 这种稳定 id
  evidence      TEXT NOT NULL,             -- JSON：{kind,file,line,quote}
  rule_id       TEXT NOT NULL,             -- 哪条规则，如 python.manifest
  rule_version  TEXT NOT NULL,
  created_at    TIMESTAMPTZ NOT NULL,
  UNIQUE (run_id, skill_id, definition_id)
);

CREATE INDEX label_claim_skill ON label_claim (skill_id, created_at DESC);

-- 每条 skill 每次评估的结果；刷新靠它跳过没变的 skill。
CREATE TABLE label_entry (
  id            TEXT PRIMARY KEY,
  run_id        TEXT NOT NULL REFERENCES job_run (id) ON DELETE CASCADE,
  skill_id      TEXT NOT NULL,
  namespace     TEXT NOT NULL,
  name          TEXT NOT NULL,
  version_id    TEXT,
  rule_version  TEXT NOT NULL,
  result        TEXT NOT NULL,             -- labeled | unchanged | deferred | push_failed | skipped | failed
  labels        INTEGER NOT NULL DEFAULT 0,
  note          TEXT,
  created_at    TIMESTAMPTZ NOT NULL,
  UNIQUE (run_id, skill_id)
);

CREATE INDEX label_entry_run ON label_entry (run_id);

INSERT INTO job_lock (kind, holder, acquired_at)
VALUES ('label', 'seed', '2026-11-14T00:00:00Z');
```

- 软删：不设。`skill` 被删后，OPS 侧的行留作历史；刷新按 server 的列表走，不会复活。
- `label_entry` 的 `result` 是刷新与导入共用的词表；`reason` 用 `note` 记（如 `unknown_definition`、`server_404`）。

### 在线库（迁移 V55）

```sql
-- 最终表只剩「哪条 skill 有哪个标签」。一列一条语句：schema 生成器只认这种写法，
-- 读不懂的迁移会从 current/schema.md 里静默消失。
ALTER TABLE skill_label DROP CONSTRAINT skill_label_pkey;
ALTER TABLE skill_label DROP CONSTRAINT skill_label_skill_id_definition_id_key;
ALTER TABLE skill_label DROP COLUMN id;
ALTER TABLE skill_label DROP COLUMN run_id;
ALTER TABLE skill_label DROP COLUMN source;
ALTER TABLE skill_label DROP COLUMN confidence;
ALTER TABLE skill_label DROP COLUMN evidence;
ALTER TABLE skill_label DROP COLUMN model;
ALTER TABLE skill_label DROP COLUMN model_version;
ALTER TABLE skill_label DROP COLUMN vocabulary_version;
ALTER TABLE skill_label DROP COLUMN review_state;
ALTER TABLE skill_label DROP COLUMN evaluated_version_id;
ALTER TABLE skill_label DROP COLUMN created_at;
ALTER TABLE skill_label DROP COLUMN updated_at;
ALTER TABLE skill_label ADD PRIMARY KEY (skill_id, definition_id);

DROP TABLE label_audit_sample;
DROP TABLE label_extraction_run;

ALTER TABLE skill DROP COLUMN outcome_source;
ALTER TABLE skill DROP COLUMN outcome_confidence;
ALTER TABLE skill DROP COLUMN outcome_evidence;
ALTER TABLE skill DROP COLUMN outcome_model;
ALTER TABLE skill DROP COLUMN outcome_model_version;
ALTER TABLE skill DROP COLUMN outcome_evaluated_version_id;
ALTER TABLE skill DROP COLUMN outcome_updated_at;
```

- **回滚**：生产库当前 `skill_label` 为空（v66 P0 只上线了词表与读模型，没有写入者）。回滚脚本重建 V52 的表形状与列；没有数据要恢复。dry-run 脚本在事务里执行 V55、打印行数，然后 `ROLLBACK`。
- **读模型**：`LabelRepository.labelsOfSkills` 去掉 `review_state` / `source` / `evaluated_version_id` 三个条件，只保留 `definition.state IN ('enabled','stopped')` 与默认值过滤（`prompt-only` / `free` 不进读模型）；`outcomesOf` 只读 `outcome`，不再看来源列。
- **`ModuleMap`**：`label_extraction_run` / `label_audit_sample` 从 M12 的表清单移除。

## 接口

三个接口都在 `/inner/v1`，只在私有端口、带 `X-Inner-Token`（[`InnerPlaneConfig`](../../../skillmaster-server/src/main/java/com/skillmasterai/config/InnerPlaneConfig.java)）。错误形状沿用 `{"error":{"code":…,"message":…}}`。

### 列公域已上线 skill

```
GET /inner/v1/skills?cursor=<skill_id>&limit=200
```

```json
{
  "items": [
    {"skill_id": "...", "namespace": "skillmaster", "name": "pdf-tools",
     "current_version_id": "..."}
  ],
  "next_cursor": "..."   // 最后一页为 null
}
```

- 只列 `visibility='public'`、`current_version_id IS NOT NULL`、命名空间 `official=true` 的 skill，按 `skill_id` 升序；`limit` 上限 500。
- `current_version_id` 是刷新跳过没变内容的键：同一条 skill 的新版本会得到新 id，内容没变就不会有新版本。

### 读当前版本的文本与清单

```
GET /inner/v1/skills/{namespace}/{name}/content
```

```json
{
  "skill_id": "...",
  "version_id": "...",
  "files": [{"relpath": "SKILL.md", "size": 1234, "is_binary": false}],
  "skill_md": "---\nname: ...\n---\n..."
}
```

- 只返回根 `SKILL.md` 的文本与文件清单；不返回其他文件内容（[ADR 0196](../../decisions/0196-label-extraction-reads-public-text-only.md) 的最小输入）。
- `SKILL.md` 超过文本上限或不是文本时返回 409 `skill_md_unreadable`；调用方记 `failed`，不推标签。
- 未知 skill / 未上线 / 非官方命名空间：404 `unknown_skill`。

### 替换一条 skill 的规则标签

```
PUT /inner/v1/skills/{namespace}/{name}/labels
{"version_id": "...", "claims": [{"definition_id": "runtime:python"}, {"definition_id": "runtime:docker"}]}
```

```json
{"skill_id": "...", "replaced": 2, "labels": ["runtime:python", "runtime:docker"], "applied": true}
```

- **整组替换**：同一事务里删掉这条 skill 的全部 `skill_label` 行，再插入本请求的集合；重复请求是 no-op。
- **`applied=false` 是一种拒绝，不是错误**：`version_id` 不是当前上线版本时，server 不写最终表，`replaced=0`。它保护的是「线上版本不能被草稿的标签改掉」；OPS 记 `deferred`，该版本上线后的刷新会写。
- 只接受 `runtime` 维度的定义，且 `label_definition` 必须存在；词表外的 id 返回 400 `unknown_definition`，消息点名那个 id。
- 只接受官方命名空间的 skill（导入产生的草稿也算），其他命名空间返回 403 `not_official`。
- 不接收证据与规则版本：它们留在 OPS；服务间是同一信任域（与 v69 的 `/inner/v1/sync/submissions` 同一做法）。
- 未知 skill 返回 404 `unknown_skill`。

### OPS 的触发面

```
POST /inner/v1/jobs/label/runs
{"trigger": "manual"}
```

- 与 `/import/runs` 同形：取锁失败 409 `busy`，成功 202 `{run_id, status: "running"}`。
- `GET /inner/v1/jobs/runs/{id}` 返回运行行；`entries` 来自 `label_entry`，形状与导入条目一致（`skill_id` / `name` / `result` / `labels` / `note`）。

## 分期

| 期 | 交付 | 验收 |
|---|---|---|
| **P1 · 规则与夹具** | `SkillFacts`、`RuleLabeler`、规则表、证据校验、每一列的夹具；金标集工具与首轮测量 | A1、A2、A3、A6；金标集的 precision / recall 记录 |
| **P2 · 导入链路** | 导入钩子、OPS V2、server V55、内接口、导入端到端 | A4、A7、A8、A9、A10 |
| **P3 · 刷新与调优** | `LabelRefreshJob`、触发面、增量跳过、全量刷新与规则调优 | A5、A11；第一轮全量刷新的耗时与覆盖率 |

**每一期明确不做**：P1 不接 server、不写库；P2 不做全量刷新；P3 不做定时任务与自动发布后回调。

## 开放问题

**已定**（所有者 2026-11-14）：门槛按 95% / 90% / 80% / 98%；`shell` / `sqlite3` / OS 限定 / `compatibility` 按 §规则表；刷新手动触发 + 增量跳过，不加定时任务。

| # | 问题 | 当前倾向 | 怎么回答 |
|---|---|---|---|
| Q1 | 公域实际有多少条？ | 未核实；按分页遍历 | 第一轮刷新时数，写进 test-plan §结果 |
| Q2 | 外来 skill 里 `allowed-tools` 的覆盖率有多高？ | 未核实；只当加分信号，不指望它 | 金标集统计 |
| Q3 | 规则与未来 Agent 产出冲突时谁赢？ | 不在本版定 | Agent 期另开一条决定 |
| Q4 | 内容接口要不要返回 `SKILL.md` 之外的小文本文件？ | 先不返回；金标集显示召回不足再加 | 首轮 recall 数据 |
| Q5 | `rule_version` 的格式？ | 一个随规则改动手工递增的常量，如 `fixed-r2` | 实现时定，写进代码注释 |
