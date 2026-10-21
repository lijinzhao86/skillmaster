# v11-publish-handoff · 测试方案

> **最后更新**：2026-10-21
> **所属版本**：[`README.md`](README.md)　需求权威：[`prd.md`](prd.md)　设计权威：[`technical-design.md`](technical-design.md)

## 验收映射

| 验收（prd.md） | 用例 |
|---|---|
| #1 `--sub actions` / `--sub diff` 的地址与落点 | C1、W10、E1 |
| #2 `--sub record` 不写参数 | C2 |
| #3 三档 `--bump` 各自预选 | C3、W2 |
| #4 不带 `bump` 时行为与今天一致 | W3 |
| #5 `publish --bump` 的拒绝句逐字 | C4 |
| #6 四个动词的第三行都带 `--sub actions` | C5 |
| #7 `rollback --bump` 是用法错误 | C6 |
| #8 不认识的参数 / 第二个地址 / 非法取值 | C7 |
| #9 三个惰性态 | W4、W5、W6 |
| #10 地址里的非法 `bump` 当作没有 | W1 |
| #11 `bump` 只在版本栏内传递 | W7、W8、W9 |
| #12 不给新参数时地址逐字不变 | C8 |
| #13 `--json` 形状不变 | C9 |
| #14 帮助与 README 同步 | C10、人工核对 |

**两条不单列的**：`--sub --json` 的 JSON 模式探测由 C7 的用法错误用例覆盖；`offline` / `online` / `delete` 的落点不变由
v10 的 C12 覆盖（本版未改那三个动词的成品句子）。

## 用例

### CLI（`skillmaster-cli/cmd/skillmaster/version_test.go`）

**C1 · `--sub` 写进地址**
- 前置：`SKILLMASTER_WEB_URL=http://localhost:5173`
- 步骤：分别跑 `version open dev/pdf-tools@d1 --sub actions --print`、`--sub diff --print`
- 期望：地址含 `&sub=actions` / `&sub=diff`；`--print` 不调用浏览器

**C2 · `--sub record` 不写**
- 步骤：`version open dev/pdf-tools@d1 --sub record --print`
- 期望：地址与不带 `--sub` 逐字相同，不含 `sub=`

**C3 · `--bump` 三档**
- 步骤：`--bump patch|minor|major --print`
- 期望：地址分别含 `&bump=patch` / `minor` / `major`；与 `--sub` 组合时两者都在

**C4 · `publish --bump` 的拒绝句**
- 步骤：`version publish dev/pdf-tools@d1 --bump minor`
- 期望：退出码非零；第三行逐字 `到那一版自己的页面上去做：skillmaster version open dev/pdf-tools@d1 --sub actions --bump minor`；零请求

**C5 · 四个动词不带 `--bump`**
- 步骤：`publish|rollback|revoke|restore dev/pdf-tools@d1`
- 期望：第三行都以 `--sub actions` 结尾；都不含 `--bump`

**C6 · 非 publish 上带 `--bump`**
- 步骤：`version rollback dev/pdf-tools@d1 --bump minor --json`
- 期望：本地用法错误；JSON 的 `error.code=usage`；零请求

**C7 · 参数与取值的用法错误**
- 步骤：页面专属动词带 `--what`、带两个地址、`--bump` 给两次；`version open … --sub general`、`--bump foo`、`--sub` / `--bump` 各给两次；`--sub --json`、`--bump --json`（值被误写成开关）
- 期望：都是本地用法错误，句中指名那个参数或取值；`--json` 在值的位置时按文本作答；零请求

**C8 · 不给新参数时地址不变**
- 步骤：`version open dev/pdf-tools@1.2.3 --print`、`@draft:01JKMNPQRSTVWXYZ0123456789 --print`
- 期望：与既有断言逐字相同（`…?tab=versions&version=…`）

**C9 · `--json` 形状**
- 步骤：`version open … --sub actions --bump minor --json`
- 期望：`{"url": "…&sub=actions&bump=minor", "opened": true}`，没有多余字段

**C10 · 帮助文本**
- 步骤：`version help`（`skill help` 不含 `version open`，不参与这条）
- 期望：`open` 一行列出 `--sub record|diff|actions` 与 `--bump patch|minor|major`，并注明 `record` 是缺省；页面专属动词仍不在命令列

### 网页（`skillmaster-web/tests/versions.test.ts`）

**W1 · 路由解析**
- 步骤：`parseVersionRoute('?tab=versions&version=d1&bump=minor')`、`…&bump=foo`、不带 `bump`
- 期望：第一种得到 `bump: 'minor'`；第二、三种**没有** `bump` 这个键

**W2 · 面板三档预选**
- 前置：线上 1.2.3；挂载 `VersionActionPanel` 传 `initialBump`
- 步骤：分别以 `patch` / `minor` / `major` 挂载
- 期望：对应单选项 `checked`；输入框 `1.2.4` / `1.3.0` / `2.0.0`；按钮 `上线 @…` 跟着变

**W3 · 不带 bump**
- 步骤：不传 `initialBump`
- 期望：选中「只是修正」，按钮 `上线 @1.2.4`（既有用例）

**W4 · 第一次上线**
- 前置：`live === null`，`initialBump='minor'`
- 期望：不画单选项；输入框仍是 `1.0.0`

**W5 · 早期自带版本号的草稿**
- 前置：`draft` 且 `version.name !== null`，`initialBump='minor'`
- 期望：不画输入框与单选项；显示「上线时沿用」那行说明

**W6 · 已上线版本**
- 步骤：`VersionDetail` 打开 `version=1.2.3&sub=actions&bump=minor`
- 期望：没有发布区块；页面照常打开

**W7 · 切栏保留、离开清除**
- 步骤：`VersionDetail` 的 `subHref('record'|'diff'|'actions')`、`listHref`；`SkillPage` 的四个顶栏、可见性章、`backToListHref`
- 期望：`subHref` 保留 `bump`；`listHref`、四个顶栏、设置子页、返回清单都不含
- 说明：`dirHref` 与 `writeListAddress` 维护同一份清单，但今天的界面走不到带着 `bump` 的文件栏或清单改写（顶栏已经把它清掉），所以只随代码复核，不单列用例

**W8 · 上线成功后的地址**
- 前置：地址是 `?tab=versions&version=d1&sub=actions&bump=minor`
- 步骤：`useVersionActions` 发布成功（服务端回 `release_name`）
- 期望：`location.assign('?tab=versions&version=1.3.0&sub=actions')`——只删 `bump`，其余查询串原样保留

**W9 · 写命令回到清单**
- 步骤：丢弃草稿 / 发布无 `release_name` 后走 `backToList`
- 期望：地址里没有 `bump`

**W10 · `--sub diff` 的落点**
- 前置：一份有基线可比的草稿（线上 1.2.3），与一份没有任何基线的草稿各一例
- 步骤：`VersionDetail` 用 `route: parseVersionRoute('?tab=versions&version=d1&sub=diff')` 打开两例
- 期望：有基线的那一例当前栏是「改动」；没有基线的那一例落回「概览」

### 真机（本地服务）

**E1 · 交接落点**
- 前置：`skillmaster-test/coding` 有一份草稿，账号为所有者（`diff` 那一例要有可比的基线）
- 步骤：① 跑 `version publish …@<草稿> --bump minor`，复制拒绝句第三行的命令执行；② 再跑 `version open …@<草稿> --sub diff --print` 并打开
- 期望：① 打开的是「操作」栏；「新增能力」已选中；按钮是 `上线 @<下一 minor>`；② 落在「改动」；没有基线时落回「概览」

## 环境与数据

- **CLI 单测**：`cd skillmaster-cli && go test ./...`；不需要服务端（用例用 `httptest` 桩与 `--print`）。
- **网页单测 / 类型**：`cd skillmaster-web && npm run test && npm run type-check`。
- **真机**：本地服务 8080 + Vite 5173（`/api/v1`、`/web` 由 Vite 代理），开发库；用 `skillmaster-test/coding` 建一份草稿，
  跑完丢弃它，不留残留。CLI 指向 `SKILLMASTER_SERVER=http://localhost:5173`、`SKILLMASTER_WEB_URL=http://localhost:5173`。
- **视觉验收由人做**：E1 的「选中了哪一档」由人看页面确认；测试全绿不等于视觉验收。

## 结果

**自动化部分已执行（2026-10-21）**：

| 范围 | 命令 | 结果 |
|---|---|---|
| CLI | `cd skillmaster-cli && go test ./... -count=1` | 通过（8 个包） |
| CLI 静态检查 | `go vet ./...` | 退出码 0 |
| 网页 | `cd skillmaster-web && npm run test` | 通过（24 个文件 / 477 个用例） |
| 类型 | `npm run type-check`（`vue-tsc --noEmit`） | 通过 |
| 文档 | `python3 .claude/skills/docs-architecture/scripts/check_docs.py` | 退出码 0（1907 条相对链接全部有效） |

**逐条**：C1–C10 的自动化断言全部通过；W1–W10 全部通过。

**真机（本地服务，2026-10-21）**：

- 在 `skillmaster-test/coding` 上用 `skill write …@new` 建了一份冒烟草稿 `FQNZDNHY`（只多一个 `references/v11-smoke.md`）。
- `version publish …@FQNZDNHY --bump minor` 的第三行与预期逐字一致：
  `到那一版自己的页面上去做：skillmaster version open skillmaster-test/coding@FQNZDNHY --sub actions --bump minor`；退出码 1，零请求。
- 复制该命令执行，地址是 `…?tab=versions&version=FQNZDNHY&sub=actions&bump=minor`，Vite 返回 200，已在浏览器打开。
- `--sub diff` / `--sub record` / `--bump major --json` 的地址与 §用例 一致（`record` 不写 `sub`）。
- **待由人确认**：打开的那一页是不是「操作」栏、「新增能力」是否已选中、「上线 @…」的号码是不是 minor 建议。
  测试全绿不等于视觉验收。
