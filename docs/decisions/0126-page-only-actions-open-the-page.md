# 0126 · 网页专属的动作，在 CLI 里打开那一页

- **状态**：已采纳
- **日期**：2026-11-01
- **取代**：[0061](0061-what-a-credential-may-do-to-versions.md) §决定 4（「页面专属的动作在 CLI 里不是命令，只是一句拒绝」）。0061 的 §决定 1–3、5 与正文其余部分不改。
- **部分被取代**：[0127](0127-never-published-skills-may-be-deleted-by-a-credential.md) —— 删除这一类不再是「只打开页面」：从未发布过任何版本的 skill 允许凭据删除；§决定 4「API 平面没有这九个动作的路由」对删除不再成立。同一份 0127 修订了 §决定 1：页面命令**不再零请求、不读凭据**，而是先读一次作者页（读不到时再读一次消费者面）做预检。**正文按原样保留**。
- **依赖**：[0061](0061-what-a-credential-may-do-to-versions.md)（边界：改变调用者读到什么只在浏览器面）、[0046](0046-deleting-a-skill-happens-in-the-browser.md)、[0072](0072-the-handoff-points-at-the-page-that-owns-the-action.md)（交接指向拥有该动作的页面）、[0074](0074-a-handoff-may-carry-a-preselection.md)（交接可带预选）、[0087](0087-cli-exit-codes-are-a-public-contract.md)（退出码是对外契约）、[0081](0081-every-console-operation-has-a-cli-command.md)（每处控制台操作有 CLI 对应）

## 背景

[0061](0061-what-a-credential-may-do-to-versions.md) §决定 4 定的是：`version publish|rollback|revoke|restore|offline|online|delete` 在 CLI 里**不是命令，只是一句拒绝**，并打印一条可以转发的交接命令。它的理由是「边界要可发现」：agent 试 `version publish` 时得到的是一句「这由人在网页上确认，点这里」，而不是「未知的子命令」。

这个形状在实践中暴露了两个问题。

**一是拒绝被读成失败。** 拒绝走的是退出码 1（普通失败），v22 的缺陷清单 D8 已经记过：agent 会把它当成「操作失败」而中止交接。命令本身写对了、路也是通的，输出的却是一条错误——这正是 [0087](0087-cli-exit-codes-are-a-public-contract.md) 说的「命令写对了、事情没办成」这一类，但它和真正的失败共用同一个信号，读的人分不出来。

**二是能力面不完整。** 控制台后来长出了四个 skill 级动作（删除 skill、下线、恢复服务、改可见性），CLI 里只有前三个还挂在 `version` 组下当拒绝（名字也错位：`version delete` 删的是整个 skill），可见性**连名字都没有**。一个人想让 skill 下线，在命令行里搜不到任何入口，只能自己拼 `/skills/<ns>/<name>?tab=settings`。

## 决定

1. **九个网页专属动作在 CLI 里都是命令，行为是打开那一页**：`version publish|rollback|revoke|restore`，`skill delete|offline|online|visibility`，`skills publish`（批量上线，打开「待上线」页）。命令不发请求、不读写凭据、不执行动作。
2. **退出 0，首句说「还没执行」**：人类可读输出的第一行固定写明这个动作还没有发生、只能在网页上由人确认；第二行是地址。`--json` 给 `{"action", "performed": false, "url", "opened"}`。
3. **skill 级动作回到 `skill` 组**：`version delete|offline|online` 移除。因为它们未正式发布，产品负责人 2026-11-01 确认不留别名、不写迁移期。
4. **边界不变**：API 平面仍然没有这九个动作的路由，任何凭据都做不到（[0061](0061-what-a-credential-may-do-to-versions.md) §决定 1）。这一条改的是 CLI 的导航形状，不是能力。

## 理由

**为什么不沿用「拒绝」：** 拒绝句解决的是「边界可发现」，但它把「去这里做」和「失败了」压成同一个信号。`open` 系列早就证明另一条路更好用：打开一页、退出 0、把地址留在输出里。用同一条路，agent 不需要解析 NAT 句子，人也不需要复制一条命令再粘贴。

**为什么退出码是 0 而不是「成功但特殊」：** 命令要求它做的事——把那一页交到人手里——确实做成了。把「没执行」写进首句和 `performed:false`，是让读的人知道进度，而不是让退出码去承担两个含义。[0087](0087-cli-exit-codes-are-a-public-contract.md) 的四个码没有「成功但什么都没做」，加第五个会把每个调用方的判断都改一遍。

**为什么 skill 级三个动作可以改名：** 它们没有正式发布过，改名不会打断任何人；留着别名反而会让「skill 级动作」永远有两个入口，而两个入口迟早会漂。判断依据是发布状态，不是「改名很危险」这条笼统顾虑。

**为什么可见性不带目标值：** 网页设置页没有「按参数预选可见性」这个能力。收下 `public` 却不生效，等于用命令的语法承诺一个没有发生的事；`--bump` 能带，是因为页面真的会预选。

**被否掉的：** 让 CLI 拿浏览器会话去执行（[0061](0061-what-a-credential-may-do-to-versions.md) 已经否过，会话是「一个人当场确认」的那层保护）；加一个 `--yes` 让人「确认」（没有可确认的后果，只是噪音）；保留拒绝但把退出码改成 0（错误文案配成功码，读的人更糊涂）。

## 后果

- **CLI**：`version` 组的页面动作从「拒绝 + 交接句」改为「打开网页 + 退出 0」；`skill` 组新增四个命令、`skills` 组新增 `publish`（批量上线）；帮助、README 的退出码段落、补全与测试跟着改。
- **文档**：新增本版三份文档；`skillmaster-cli/README.md` 里「只拒绝的命令退出 1」那一段改为只举 `skills my-tags delete`。
- **以后新增控制台动作时**：只要它是「改变调用者读到什么」的那一类，CLI 的对应就是「打开那一页」的命令，而不是拒绝；[0081](0081-every-console-operation-has-a-cli-command.md) 的映射表因此总能填出一行。
- **没有变的**：API 平面依旧没有这些路由；同意屏与凭据 scope 不因此扩张；拒绝句里「交接命令」的机制保留给 `skills my-tags delete` 这类仍是拒绝的动作。
