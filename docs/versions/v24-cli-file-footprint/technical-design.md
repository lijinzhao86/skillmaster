# v24-cli-file-footprint · 技术设计

> **最后更新**：2026-10-27
> **所属版本**：`docs/versions/v24-cli-file-footprint/`（需求见 [`prd.md`](prd.md)；跨版本取舍见 [ADR 0101](../../decisions/0101-the-cli-never-deletes-files.md)）

## 架构

只动 CLI。服务端、控制台、安装脚本都不在这条链上。

| 组件 | 现在的职责 | 本版改什么 |
|---|---|---|
| `skillmaster-cli/internal/config` | 状态目录、凭据与 pin 的路径 | 新增 `PinDir(server)`；`PinPath(server)` 降级为**旧格式的只读路径** |
| `skillmaster-cli/internal/pins` | 一个 server 一个 JSON 表，整表读—改—写 | 一 skill 一文件；原地覆写；读新文件失败才回退旧表 |
| `skillmaster-cli/internal/credentials` | 钥匙串优先、0600 文件兜底；临时文件 + rename | `Delete` 改名 `Clear` → **截断**；保存写固定的 `.new` 再 rename；空文件读作「没有凭据」 |
| `skillmaster-cli/cmd/skillmaster/download.go` | 落盘 zip → 解包到 `<target>.partial` → rename | 档案在内存里解包 → 写本次独有的 staging → rename；失败保留并点名 |
| `skillmaster-cli/internal/nodelete`（新） | — | `go/ast` 扫描：非测试源码里不许出现删除原语 |

**三条数据流**

1. **`invoke` 记钉**：`invoke` 解析出版本 → `pins.Store.Remember(address, pin)` → 路径 `<config.Dir()>/pins/<server 哈希>/<地址哈希>.json` → 读旧记录（没有就看旧表）→ 与本次相同就不写 → 不同就原地覆写。
2. **`read` 取钉**：`pins.Store.Lookup(address)` → 新文件 → 没有就旧表 → 都没有就按该命令自己的默认（`resolve`，`files.go:44`）。
3. **`download` 落盘**：响应读进内存（上限 `maxArchiveBytes`，`download.go:30`）→ `zip.NewReader` → 逐项校验 → 写 staging → `rename` 到目标。

**不变的东西**（本版一个字节都不动）：命令面、退出码（[ADR 0087](../../decisions/0087-cli-exit-codes-are-a-public-contract.md)）、`invoke` 的输出形状（[ADR 0100](../../decisions/0100-invoke-output-is-platform-assembled.md)）、凭据来源的优先级（[ADR 0038](../../decisions/0038-default-credential-is-an-api-key.md)）、`logout` 不调服务端（ADR 0038 §决定 7）。

## 数据模型

### 1. pin：一 skill 一文件

**路径**：

```
<config.Dir()>/pins/<hex(sha256(server))[:12]>/<hex(sha256(地址))[:16]>.json
```

- 目录那一段与今天的 `perServerPath`（`internal/config/config.go:137`）同一个算法，只是从「文件名的后缀」变成「目录名」。
- 文件那一段用地址的哈希而不是地址本身：地址里有 `/`，可能有 Unicode，而 macOS 默认大小写不敏感——原样当文件名迟早撞车。
- 地址是**去掉 `@版本` 的裸地址**（`bareAddress`，`cmd/skillmaster/main.go:2753`），与旧表的键一致。

**记录**（`pins.Pin` 内嵌，JSON 是平的）：

```json
{"address":"lijinzhao/coding","kind":"name","name":"1.2.0","digest":"9f86d0…","code":""}
```

`kind` / `name` / `digest` / `code` 的语义与今天完全一致（`internal/pins/store.go:38-43`）——本版只加 `address`，并在读的时候校验它。

### 2. 旧 pin 表（只读）

`<config.Dir()>/pins-<server 哈希>`，内容仍是 `{"地址": Pin}`。**只读**：新文件不存在时查它，新写入永不碰它，也永不删除。新旧路径不冲突——一个是文件 `pins-<哈希>`，一个是目录 `pins/<哈希>/`。

### 3. 凭据文件

路径不变（`config.CredentialPath`，`config.go:117`）。语义上有两处新增：

| 状态 | 字节 | 读作 |
|---|---|---|
| 从没登录过 | 文件不存在 | 没有凭据 |
| 已登录 | JSON 文档 | 凭据 |
| `logout` 之后 | **0 字节** | **没有凭据**（`Load` 必须显式处理空内容，见 §接口） |

保存走 `<path>.new` 再 `rename`：`.new` 是固定名字，成功时被 rename 消费掉；失败时留在原处的那个文件由错误句点名。

### 4. 下载的 staging

`os.MkdirTemp(filepath.Dir(target), "."+filepath.Base(target)+".staging-")`。名字带目标名与随机后缀，一眼能看出是谁留下的；**失败即保留**，成功时整目录 rename 到目标。

### 5. 读的顺序与损坏处理

| 情形 | `Lookup`（读） | `Remember`（写） |
|---|---|---|
| 新文件在、解析成功、`address` 一致 | 返回它（有旧表也不看） | 相同则不写；不同则原地覆写 |
| 新文件在、解析失败 | `CorruptError`（附路径） | **当作没有旧记录，直接覆写**（自愈），返回 `had=false` |
| 新文件在、`address` 是别人的 | `MismatchError`（附路径）——**不返回那一版** | `MismatchError`——不覆盖别人的记录 |
| 新文件不在、旧表有这一项 | 返回旧表里的 | 返回旧表里的当 `previous`，然后写新文件 |
| 两处都没有 | 没有钉（`found=false`） | 写新文件，`had=false` |

`CorruptError` 今天在 `internal/pins/store.go:151-160`，按文件路径构造；本版保留它，只把文案里的「文件」改成具体路径。`MismatchError` 是本版新增的第二个错误类型，形状与它一样：路径 + 为什么不是这个地址的记录。

## 接口

### 命令面

命令行一个参数都不变。对调用方**可见**的行为变化只有下面几行：

| 命令 | 变化 | 调用方看得见吗 |
|---|---|---|
| `skill invoke` | 同一版本第二次调用不写 pin | 否 |
| `login` / `startup` | 凭据或钉写入失败时，错误句里多一个 `.new` 的路径（只在失败时） | 是 |
| `logout` | 凭据文件留着，0 字节；`auth status` 照旧说未登录 | 是（`ls` 能看到） |
| `skill download` 成功 | 与今天完全一致；目标已存在且为空时，多留一个 `.replaced-*` 空目录并印一行 | 是（仅那一态） |
| `skill download` 失败 | staging 留在原处，错误句点名并说明「确认后请自行删除」 | 是 |
| `read` / `files` / `grep` / `wc` | pin 从新文件读，读不到回退旧表 | 否 |

### 内部签名

```go
// internal/config
func PinDir(server string) (string, error)   // 新：<Dir>/pins/<hash>/
func PinPath(server string) (string, error)  // 保留：旧表，只读

// internal/pins
func Open(dir, legacyPath string) *Store
func (s *Store) Lookup(address string) (Pin, bool, error)
func (s *Store) Remember(address string, pin Pin) (Pin, bool, error)

// internal/credentials
type Store interface {
    Load() (Credentials, bool, error)
    Save(Credentials) error
    Clear() error          // 原 Delete：让凭据不再存在（文件＝截断；钥匙串＝删除条目）
    Where() string
}

// internal/nodelete
func scanForDeletes(fset *token.FileSet, name string, file *ast.File) []string
```

`pins.Open` 的两个参数都是绝对路径：`dir` 是要写的目录，`legacyPath` 是只读的旧表（传空串表示没有）。

### `Load` 的空内容

`fileStore.Load`（`internal/credentials/file.go:20`）在 `len(blob) == 0` 时返回 `(Credentials{}, false, nil)`——**在做 `ParseBlob` 之前**。这一条是 `logout` 改成截断的前提：不这么改，截断后的文件会被读成一把空 token，而 `found=true` 会让 `newerCredential`（`responsive.go:166`）去比较两份凭据。

### 不变式检查

`internal/nodelete` 只有一个文件 `nodelete_test.go`：

1. `TestProductCodeNeverDeletesFiles`：从 `runtime.Caller` 找到模块根（找不到 `go.mod` 直接失败，防止扫了个空目录还通过），遍历非测试 `.go` 文件（跳过 `testdata/`、`vendor/`、以 `.` 开头的目录），用 `go/parser` 解析，收集**导入名到包路径**的映射（处理别名），再找 `X.Sel(...)` 中 `X` 指向 `os` / `syscall` / `golang.org/x/sys/unix` / `golang.org/x/sys/windows`、`Sel` ∈ {`Remove`, `RemoveAll`, `Unlink`, `Unlinkat`, `Rmdir`, `Rmdirat`, `DeleteFile`, `RemoveDirectory`} 的调用。命中即 `t.Errorf`，报出 `文件:行` 并指向 ADR 0101。
2. `TestTheScannerSeesADelete`：对一段合成的源码跑同一个收集函数，断言它**能**抓到 `os.Remove`、别名导入与 Windows 的 `syscall.DeleteFile`——检查器自己也要被测。

## 分期

| 期 | 交付 | 验收 |
|---|---|---|
| **P0 凭据** | `Store.Clear`、`.new` + rename、空文件语义；`logout` 走截断；`responsiveKeychain` 的两处调用跟上 | `internal/credentials` 全部测试通过；`grep` 证明这两个包里再没有删除原语 |
| **P1 pin** | `config.PinDir`；`pins` 重写（一 skill 一文件、原地覆写、旧表回退、地址校验、相同不写）；`main.go` 的 `pinsFor` / `pinsUnreadable` 跟上 | `internal/pins` 与新加的用例通过；`invoke` 两次只写一次（验收 5） |
| **P2 download** | 内存解包 + 独有 staging + rename；失败保留并点名 | `cmd/skillmaster/download_test.go` 全绿，外加「失败保留」用例（验收 8、9） |
| **P3 收口** | `internal/nodelete` 检查；`README.md`；全量测试；`strace` 与两个沙箱的实测 | 验收 1–3 全过 |

## 开放问题

| # | 问法 | 当前倾向 |
|---|---|---|
| Q1 | 同一 skill 被两个 agent 同时 `invoke` 时，原地覆写可能交错出一份解析不了的文件——要不要加文件锁？ | **不加**。这一点是 ADR 0101「后果」记下的退让：出现概率低（同一 skill、同一瞬间、版本还不同），失败是响亮的，下一次 `invoke` 覆写就自愈。加锁要引入平台分支（flock / LockFileEx），收益配不上复杂度 |
| Q2 | 目标已存在且为空时，怎么把 staging 放进去？ | **Go 的 `os.Rename` 在任何平台都拒绝覆盖已存在的目录**（它先 `Lstat` 目标，是目录就直接返回 `EEXIST`，根本不调用 `rename(2)`），所以这一态每次都走「改名让开」：旧空目录改成 `.<目标名>.replaced-<pid>-<纳秒>` 留着，CLI 印一行点名它。代价是一个空目录残留；换来的是一次原子放置——目标要么原样、要么完整 |
| Q3 | pin 记录要不要带一个格式版本号字段？ | **不带**。缺字段读成零值，与今天旧表的兼容方式一致；真要改形状时再引入，届时旧记录按「没有钉」以外的规则处理 |
| Q4 | `logout` 之后 0 字节的凭据文件会不会让 `newerCredential` 误判？ | **不会**，前提是 §接口 那条 `Load` 改动先落地：空文件返回 `found=false`，比较分支根本不进 |
| Q5 | `strace` 那条验收在哪里跑？ | 本版**不做成 Go 测试**：它要一个真实进程与一个能应答的假服务端，和 `go test ./...` 的进程内跑法不是一回事。写成 `test-plan.md` 里的一条可复现命令，在 Linux（含容器）上跑；结果如实记进 test-plan §结果 |
| Q6 | `pinsUnreadable`（`main.go:2776`）现在劝人「删掉这个文件」——还成立吗？ | 成立，但**位置变了**：每个 skill 一个文件，所以文案要点名是哪一个文件；删除动作仍然由人做（ADR 0101 的「人来做删除」） |
