# ADR 0100 · invoke 的输出是平台组装的头部加正文，不是原文透传

- **状态**：部分被取代（决定 4 由 [0119](0119-permissions-carry-commands.md) 取代）
- **日期**：2026-10-27
- **依赖**：[`versions/v23-invoke-metadata/`](../versions/v23-invoke-metadata/README.md)、[0005](0005-content-addressing.md)（内容寻址与 digest）、[0047](0047-permissions-are-a-ladder.md)（五档梯子）、[0091](0091-release-type-decides-the-version-number.md)（版本号在发布时分配）、[0038](0038-default-credential-is-an-api-key.md) 与 [0039](0039-api-key-defaults-and-their-edges.md)（key 的 scope，以及「write 隐含 read」）
- **部分取代**：[0035](0035-the-pin-lives-on-the-client-machine.md) §后果 里「`invoke` 印『已按 X@Y 加载』」那一句——那行页脚被本条的头部取代；钉移动时的提示仍在，改走 stderr

## 背景

`skill invoke` 至今返回的是这一版 `SKILL.md` 的原始字节，末尾加一句页脚。agent 加载完要做三件事——能不能读别的文件、能不能改这一份、改完收到哪——它一件都答不出来：翻 `help` 只能读到命令形状，直接试会撞 403，而规则段是全局的，答不了「这一份」。

同时，正文开头的作者 frontmatter 里有一行 `version:`：那是作者随手写的标签（`lijinzhao/life` 写 `2.0.0`），而平台给这一版分配的号是 `1.0.0`。两个数字并排出现，没有一个字说明它们是什么。

有三个位置可以回答「你在这份上能做什么」：

| | 方案 | 代价 |
|---|---|---|
| 1 | 继续原文透传，把页脚写长 | 页脚是散文，模型要读完一句才知道有哪些动作；每加一件事就长一行，没有止境 |
| 2 | **头部由服务端组装，CLI 只渲染** | 输出不再是这一版的字节，digest 不能对着它比 |
| 3 | 在 `/body` 响应里拼上头部 | `cat` 与 digest 保真（ADR 0005）当场失效 |

## 决定

1. **`skill invoke` 输出 = 平台组装的头部 + 正文**：头部只有 `address` / `version` / `access` / `permissions`，作者声明了 `compatibility` 时多一行；从未分配过版本号的行（草稿、已丢弃）没有 `version` 行，`address` 带版本码；页脚删除。
2. **正文里作者的 frontmatter 块整块去掉**：作者自己的 `description`、`when_to_use`、`version`、`name` 都不出现——搜索卡片已经给过前两样，后两样与平台事实冲突。
3. **值在服务端，渲染在 CLI**：`access` / `permissions` 与 `can_write` 同源——档位来自解析这次读取的那个 `Access`（`access` 报的就是它），`permissions` 再与**凭据的 scope** 求交（`skills:read` 的 key 只看到 `read, list`），与 `can_write` 一直是同一种算法；CLI 不推导、不解析 YAML，只按固定顺序打印拿到的字段。
4. **`permissions` 列的是「档位授予的、无条件的动作」**：`read` / `list` / `write` / `discard` / `share`。`publish` / `rollback` / `visibility` / `delete` 是控制台动作，没有命令可敲；`download` / `grep` / `show` / `versions` 有命令但不改变能力边界，所以也不列。`discard` 从 `maintain` 起列——`write` 只能丢弃自己的草稿，而头部没有「谁的」这一维。
5. **`/body` 与 `skill cat` 一个字节都不改**：要这一版的原始字节（比对 digest、整份导出）就走 `cat`。

## 理由

- **头部是结构，页脚是散文。** 一个字段一行、名字固定，模型不必「读懂」就知道 `write` 意味着能提交版本；而被否的第 1 条无论写多清楚，它仍然是每加一件事就长一句的叙述。
- **显示的答案与执行的答案必须同源。** `can_write` 早就在服务端算（账号档位 ∩ 凭据 scope），因为两处各算一次一定漂；`access` / `permissions` 是同一件事的另外两种说法，只能从同一处来。
- **被否的第 3 条**：在 `/body` 上拼接会让 `cat` 不再是原始字节，而「托管要保真」与 digest 校验都依赖那条路一个字节不改；一个端点服务两种用途，最后两种都不对。
- **去掉作者 frontmatter 是同一枚硬币的另一面。** 既然头部由平台组装，正文就不该再带另一个 frontmatter——两份元数据并排，读者没有任何依据判断该信哪一份。
- **`compatibility` 保留**：它是运行环境要求（要 python3、要网络），是加载前就该知道的事实，而头部是唯一会到达 agent 的地方。

## 后果

- **invoke 的输出不再是这一版的字节**，不能拿 digest 对着它比。这是代价，写进 `skill invoke` 的 help：要原始字节用 `skill cat`。
- **旧规则段里「末行说明该版本与可执行的操作」失效**：`skillmaster/startup` 的规则段要同步改成指向头部（v23 PRD §做 6），老用户粘过的旧版本会一直说到下次重跑旅程。
- **`permissions` 的取值随梯子走**：新增档位或动作要同时改 `GrantRole.permissionsOf` 与它的测试，否则 agent 会拿到一个不存在的动作名。
- **`--offset` 的行号从正文第一行算起**，不再把被去掉的 frontmatter 算在内——与 `read` 对同一个文件的编号因此差一个块的偏移，两者的编号各自与自己的输出一致。
- **老客户端连新服务端**只是多几个 JSON 字段（消费面两个；作者面三个——信封上多一个 `compatibility`），忽略即可；**新客户端连老服务端**会少 `access` / `permissions` 两行，输出仍然可读。
- **`stdout` 从此是一份文档**：钉移动时的提示挪到 stderr（与 `startup` 一致），`invoke > body.md` 存下的就是头部加正文——唯一例外是取用上限的说明（正文超过 2000 行 / 128 KiB 时的「继续取」），它跟着内容走，留在 stdout。
