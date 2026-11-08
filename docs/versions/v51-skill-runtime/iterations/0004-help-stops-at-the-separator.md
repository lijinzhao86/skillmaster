# 0004 · 帮助在 `--` 处停下

## 改了什么

- **`internal/help.Requested`**：扫 `--help` / `-h` 时遇到 `--` 就停——分隔符之后的参数属于要被执行的命令，不再被当作本 CLI 的帮助请求。
- **`autoUpdateAllowed`**（`update.go`）：同一处分界——`run … -- x.py --help` 现在是一次真正的执行，不再被当成「用户要看帮助」而跳过更新检查。
- **顶层命令表**：`run` 的摘要从「取回并执行命令」改为「执行 skill 命令」，一行里说清它是干什么的；`--` 这个 flag 的说明与 `run` 的 Output 对齐（脚本路径会被解析，不是逐字转发）。
- **`skmr help workflow`**：加一条「运行」流程——skill 正文让你跑某个脚本 → `run <地址> -- python3 x.py [参数]`，在 `scripts/` 下执行，脚本参数（第一个词是解释器，不动）解析到 `scripts/` 下，首次加 `--yes`。
- **测试**：`help` 包新增 `TestRequestedStopsAtTheSeparator`（分隔符两侧各两条）；`TestHelpIsRecognisedAtEveryPosition` 改为只在分隔符之前插 `--help`——原来的写法把 `--help` 插到了 `run` 示例的 `--` 后面，而那正是这次改掉的行为。

## 为什么

生产验收时撞到：`skmr run skillmaster/matlab --yes -- python3 validate_project_manifest.py --help` 打印的是 `skmr run` 自己的帮助，脚本一行没跑；`-h` 一样。而 `skmr run --help` 的 `--` 说明写着「它后面的都是要执行的命令」——这就是被违反的契约。

根因是帮助扫描比 `run` 早：它在整行参数里找 `-h`/`--help`，不认识 `--` 这条分界线。v16 定「值不跳过、任意位置都算」时还没有 `--` 这种参数（[v16 TD](../../v16-cli-help/technical-design.md) 规则 2 的代价段当时就写明：要恢复「`--` 之后是数据」的写法，必须先引入 `--` 终止符）。`run` 引入了这个终止符，帮助扫描按那句遗留说明让开。

## 影响

- `--` 之后的 `-h` / `--help` 现在交给子进程；`--` 之前不变（`skmr run --help`、`skmr run <地址> --help` 仍是本 CLI 的帮助）。全仓只有 `run` 把 `--` 当分隔符；在 `skill cat` 这类不解析旗标的命令里它本来就是数据，之后的 `--help` 同理。
- **v16 的两条断言被收窄**：PRD #1 / #12 的「`--help` 出现在任意位置都算」与 TD 规则 2，现在在 `--` 之后不再成立。v16 冻结、不改正文；变化的依据与边界由这条记录承载。
- 帮助文案三处：`run` 的摘要、`--` 的说明，和 workflow 页新增的「运行」流程。`docs/current/cli.md` 由生成器重出。
- 不改执行行为、权限或任何数据。
