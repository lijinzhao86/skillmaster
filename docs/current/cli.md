<!-- 生成物，勿手改。源：skillmaster-cli/cmd/skillmaster/declarations*.go；重新生成：python3 scripts/docs/generate.py -->

# CLI 语法（生成）

命令路径、一句话与用法来自 `help.Spec`——与 `skmr help` 是同一份源，所以两者不可能不一致。退出码是公开契约（[ADR 0087](../decisions/0087-cli-exit-codes-are-a-public-contract.md)），参数细节用 `skmr <命令> --help`。

| 命令 | 一句话 | 用法 |
|---|---|---|
| `skmr` | SkillMaster 命令行 | — |
| `skmr auth` | 这份凭据的状态 | — |
| `skmr auth status` | 这份凭据是什么 | `skmr auth status [<地址>]` |
| `skmr doctor` | 自检；--fix 改配置 | `skmr doctor [--fix [--undo] [--dry-run]]` |
| `skmr draft` | 草稿的动词 | — |
| `skmr draft create` | 新建一份草稿 | `skmr draft create <命名空间>/<名字> [--from <版本\|版本码>] --name <名称> [--note <说明>]` |
| `skmr login` | 在浏览器里登录这台机器 | `skmr login [--keyring]` |
| `skmr logout` | 退出本机的凭据 | `skmr logout` |
| `skmr notifications` | 通知：待处理与全部 | — |
| `skmr notifications accept` | 接受共享邀请 | `skmr notifications accept <id> [--json]` |
| `skmr notifications approve` | 批准权限申请 | `skmr notifications approve <id> [--role <档位>] --yes [--json]` |
| `skmr notifications decline` | 拒绝共享邀请 | `skmr notifications decline <id> [--json]` |
| `skmr notifications deny` | 拒绝权限申请 | `skmr notifications deny <id> --yes [--json]` |
| `skmr notifications list` | 看通知与下一步命令 | `skmr notifications list [--status attention\|pending\|all] [--unread] [--page <页码>] [--json]` |
| `skmr notifications read` | 标为已读 | `skmr notifications read <id> \| --all [--json]` |
| `skmr notifications show` | 看一条通知的全文 | `skmr notifications show <id> [--json]` |
| `skmr prompt` | 写进 Agent 文件的那段说明 | — |
| `skmr prompt print` | 打印那段说明的正文 | `skmr prompt print` |
| `skmr run` | 执行 skill 命令 | `skmr run <地址>[@版本] [--cwd <目录>] [--cache-in <目录>] [--no-cache] [--yes] [--json] -- <命令...>` |
| `skmr secret` | 服务凭据：存值与绑定 | — |
| `skmr secret bind` | 再绑一个 skill | `skmr secret bind <id> --skill <地址> --env <NAME> [--json]` |
| `skmr secret incoming` | 看别人共享给我的 | `skmr secret incoming --skill <地址> [--json]` |
| `skmr secret list` | 看我的凭据与用量 | `skmr secret list [--skill <地址>] [--json]` |
| `skmr secret quota` | 改每天取用上限 | `skmr secret quota <id> --per-day <N> [--json]` |
| `skmr secret rm` | 删除一条凭据 | `skmr secret rm <id> --yes [--json]` |
| `skmr secret rotate` | 换值，绑定不变 | `skmr secret rotate <id> [--json]` |
| `skmr secret set` | 存值并绑定 | `skmr secret set [--name <名字>] --skill <地址> --env <NAME> [--json]` |
| `skmr secret share` | 打开网页共享 | `skmr secret share <id> --skill <地址> [--user <用户名>] [--print] [--json]` |
| `skmr secret shares` | 看谁在用（只读） | `skmr secret shares <id> [--skill <地址>] [--json]` |
| `skmr secret unbind` | 解除一处绑定 | `skmr secret unbind <id> --skill <地址> --env <NAME> [--json]` |
| `skmr secret unshare` | 撤销共享 | `skmr secret unshare <id> --skill <地址> --user <用户名> [--json]` |
| `skmr skill` | 单个 skill 的动词 | — |
| `skmr skill cat` | 一次读多个文件 | `skmr skill cat <命名空间>/<名字>[@版本] <相对路径>...` |
| `skmr skill category` | 公开分类 | `skmr skill category <子命令> [参数]` |
| `skmr skill category clear` | 清除公开分类 | `skmr skill category clear <命名空间>/<名字>` |
| `skmr skill category set` | 设置公开分类 | `skmr skill category set <命名空间>/<名字> <分类>` |
| `skmr skill check` | 查这一版的相对引用 | `skmr skill check <命名空间>/<名字>[@版本]` |
| `skmr skill copy` | 把别人的一版复制成我的 | `skmr skill copy <命名空间>/<名字>[@版本] --name <新名字> [--json]` |
| `skmr skill cp` | 复制草稿里的文件 | `skmr skill cp <命名空间>/<名字>@<版本码> <from> <to>` |
| `skmr skill create` | 新建一个 skill | `skmr skill create <名字> --description <描述> [--name <版本名称>] [--json]` |
| `skmr skill delete` | 删除 skill（网页） | `skmr skill delete <命名空间>/<名字> [--json]` |
| `skmr skill edit` | 替换一处或多处文本 | `skmr skill edit <地址>@<版本码> <相对路径> (--old <文本> \| --old-file <文件\|->) [--new <文本> \| --new-file <文件\|->] [--replace-all] [--regex] \| --stdin-json` |
| `skmr skill find` | 按名字找文件 | `skmr skill find <命名空间>/<名字>[@版本] <模式> [-i] [--path <路径>]` |
| `skmr skill get` | 取文件或整版到本地 | `skmr skill get <地址>@<版本> (<相对路径> \| --all) [-o <本地路径>] [--into <目录>] [--overwrite]` |
| `skmr skill grep` | 在某一版里搜文本 | `skmr skill grep <命名空间>/<名字>[@版本] [<pattern>] [-F] [-i] [-n] [-l] [-C <n> \| -A <n> \| -B <n>] [-e <pattern>...] [--exclude <glob>...] [--max-count <n>] [--path <路径>]` |
| `skmr skill head` | 读开头几行 | `skmr skill head <命名空间>/<名字>[@版本] <相对路径> [-n <行数>]` |
| `skmr skill invoke` | 加载正文并钉住这一版 | `skmr skill invoke <命名空间>/<名字>[@版本] [--offset <行号>]` |
| `skmr skill join` | 收进我的库 | `skmr skill join <命名空间>/<名字> --yes` |
| `skmr skill leave` | 把加入的移出库 | `skmr skill leave <命名空间>/<名字> --yes` |
| `skmr skill license` | 读许可证的要点 | `skmr skill license <命名空间>/<名字> [--no-login]` |
| `skmr skill ls` | 看一层目录 | `skmr skill ls <命名空间>/<名字>[@版本] [<目录>] [-l] [-R]` |
| `skmr skill mv` | 移动草稿里的文件 | `skmr skill mv <地址>@<版本码> <from> <to>` |
| `skmr skill my-when-to-use` | 我的适用场景 | `skmr skill my-when-to-use <子命令> [参数]` |
| `skmr skill my-when-to-use clear` | 清我的适用场景 | `skmr skill my-when-to-use clear <命名空间>/<名字>` |
| `skmr skill my-when-to-use set` | 写我的适用场景 | `skmr skill my-when-to-use set <命名空间>/<名字> --text <文字>` |
| `skmr skill offline` | 下线（打开网页确认） | `skmr skill offline <命名空间>/<名字> [--print] [--json]` |
| `skmr skill online` | 恢复服务（打开网页确认） | `skmr skill online <命名空间>/<名字> [--print] [--json]` |
| `skmr skill open` | 打开网页上的某一页 | `skmr skill open <命名空间>/<名字> [--tab info\|files\|versions\|settings] [--sub general\|collaborators\|details] [--print] [--json]` |
| `skmr skill read` | 读一个文件 | `skmr skill read <命名空间>/<名字>[@版本] <相对路径> [--offset <行号>] [--limit <行数>] [--raw]` |
| `skmr skill request` | 申请更高一档 | `skmr skill request <命名空间>/<名字> --role <档位> \| --status \| --cancel [--json]` |
| `skmr skill restore` | 恢复已删除的 skill | `skmr skill restore <命名空间>/<名字>` |
| `skmr skill rm` | 删掉草稿里的文件 | `skmr skill rm <地址>@<版本码> <相对路径>...` |
| `skmr skill role` | 改已生效协作者的档位 | `skmr skill role <命名空间>/<名字> --user <用户名> --role <档位> [--json]` |
| `skmr skill share` | 邀请某个人共享 | `skmr skill share <命名空间>/<名字> --to <用户名> [--role invoke\|browse\|write\|maintain\|admin]` |
| `skmr skill shares` | 看共享给了谁 | `skmr skill shares <命名空间>/<名字> [--query <文字>] [--page <页码>] [--json]` |
| `skmr skill show` | 读完整信息 | `skmr skill show <命名空间>/<名字> [--no-login]` |
| `skmr skill stat` | 看路径的大小与类型 | `skmr skill stat <命名空间>/<名字>[@版本] <路径>... [--digest]` |
| `skmr skill submit` | 提交一版成草稿 | `skmr skill submit [名字 \| 目录] [--name <版本名称>] [--note <说明>] [--release major\|minor\|patch] [--to <命名空间>/<名字>]` |
| `skmr skill tag` | 公开标签与我的标签 | `skmr skill tag <子命令> [参数]` |
| `skmr skill tag add` | 贴一个标签 | `skmr skill tag add <命名空间>/<名字> <名字> [--public]` |
| `skmr skill tag remove` | 摘一个标签 | `skmr skill tag remove <命名空间>/<名字> <名字> [--public]` |
| `skmr skill tags` | 读三组词 | `skmr skill tags <命名空间>/<名字> [--no-login]` |
| `skmr skill tail` | 读结尾几行 | `skmr skill tail <命名空间>/<名字>[@版本] <相对路径> [-n <行数>]` |
| `skmr skill unshare` | 收回某人的共享 | `skmr skill unshare <命名空间>/<名字> --user <用户名> [--json]` |
| `skmr skill versions` | 能调用哪些版本 | `skmr skill versions <命名空间>/<名字>` |
| `skmr skill visibility` | 改可见性（打开网页确认） | `skmr skill visibility <命名空间>/<名字> [--print] [--json]` |
| `skmr skill wc` | 数行、词、字节 | `skmr skill wc <命名空间>/<名字>[@版本] [相对路径...] [-l] [-w] [-c]` |
| `skmr skill write` | 整份写一个文件 | `skmr skill write <地址>@<版本码> <相对路径\|目录/> [--from <本地文件>...] [--overwrite] [--allow-empty]` |
| `skmr skills` | 整个库的动词 | — |
| `skmr skills categories` | 公开分类词表 | `skmr skills categories [--no-login]` |
| `skmr skills list` | 列出我的库 | `skmr skills list [关键词] [--all] [--namespace <命名空间>]... [--category <分类>]... [--my-tag <标签>]... [--public-tag <标签>]... [--source created\|shared\|joined]... [--sort recent\|name] [--show live\|draft\|offline\|deleted\|all] [--verbose] [--no-login]` |
| `skmr skills my-tags` | 我的标签 | `skmr skills my-tags [关键词] [--no-login]` |
| `skmr skills my-tags add` | 新建一个标签 | `skmr skills my-tags add <名字> [--describe <说明>]` |
| `skmr skills my-tags delete` | 删除定义（只在控制台） | `skmr skills my-tags delete <名字>` |
| `skmr skills my-tags describe` | 改标签说明 | `skmr skills my-tags describe <名字> --text <说明>` |
| `skmr skills my-tags rename` | 给标签改名 | `skmr skills my-tags rename <旧名> --name <新名>` |
| `skmr skills pending` | 看哪些草稿在等上线 | `skmr skills pending [--json]` |
| `skmr skills public-tags` | 公开标签词表 | `skmr skills public-tags [关键词] [--all] [--no-login]` |
| `skmr skills publish` | 批量上线（打开待上线页） | `skmr skills publish [--print] [--json]` |
| `skmr skills search` | 按意思找 skill | `skmr skills search <问题> [--scope library\|public\|all] [--limit <条数>] [--exclude <地址>]... [--category <分类>]... [--public-tag <标签>]... [--my-tag <标签>]... [--semantic=false] [--verbose] [--no-login]` |
| `skmr startup` | 开始入门旅程 | `skmr startup` |
| `skmr update` | 更新这个 CLI | `skmr update [--check]` |
| `skmr usage` | 使用记录 | `skmr usage [<地址>] [--stats] [--expand] [--users] [--mine] [--days <1–90> \| --since <YYYY-MM-DD> --until <YYYY-MM-DD>] [--skill <关键词或地址>] [--page <页码>] [--limit <条数>] [--json]` |
| `skmr version` | 作者面的版本管理 | — |
| `skmr version diff` | 与上一版比较 | `skmr version diff <命名空间>/<名字>@<版本> [--from <版本>] [--patch] [--file <路径>] [--max-lines <n>] [--json]` |
| `skmr version discard` | 丢弃一版草稿 | `skmr version discard <命名空间>/<名字>@<版本码> [--json]` |
| `skmr version list` | 作者视角的版本列表 | `skmr version list <命名空间>/<名字> [--status <状态>]... [--query <文字>] [--limit <n>] [--all] [--json]` |
| `skmr version note` | 改草稿说明 | `skmr version note <命名空间>/<名字>@<版本码> (--note <文字> \| --note-file <文件> \| --clear) [--json]` |
| `skmr version open` | 打开这一版的网页 | `skmr version open <命名空间>/<名字>@<版本> [--sub record\|diff\|actions] [--bump patch\|minor\|major] [--print] [--json]` |
| `skmr version publish` | 上线一版（打开网页确认） | `skmr version publish <命名空间>/<名字>@<版本> [--bump patch\|minor\|major] [--print] [--json]` |
| `skmr version release-type` | 改草稿的版本类型 | `skmr version release-type <命名空间>/<名字>@<版本码> <major\|minor\|patch\|none> [--json]` |
| `skmr version rename` | 改草稿名称 | `skmr version rename <命名空间>/<名字>@<版本码> --name <名称> [--json]` |
| `skmr version restore` | 重新上架（打开网页确认） | `skmr version restore <命名空间>/<名字>@<版本> [--print] [--json]` |
| `skmr version revoke` | 下架（打开网页确认） | `skmr version revoke <命名空间>/<名字>@<版本> [--print] [--json]` |
| `skmr version rollback` | 回滚（打开网页确认） | `skmr version rollback <命名空间>/<名字>@<版本> [--print] [--json]` |
| `skmr version show` | 一版的事实 | `skmr version show <命名空间>/<名字>@<版本> [--json]` |
