# 0072 · 页面专属动作的交接落点指向动作所在的那一页

- **状态**：已采纳
- **日期**：2026-10-21
- **所属版本**：[`v10-cli-lifecycle`](../versions/v10-cli-lifecycle/README.md)
- **修订**：[0061](0061-what-a-credential-may-do-to-versions.md) §决定 4 的**落点那一句**——它让七个动词都给 `version open`。0061 正文按原样保留。

## 背景

[0061](0061-what-a-credential-may-do-to-versions.md) §决定 4 让 `version publish|rollback|revoke|restore|offline|online|delete` 停在错误里，并给出 `version open` 的完整命令。那时它回答的是「凭据能不能做」，「去哪一页」只是顺手写下的一句话。

v10 要把「交回给人」这一步做实：agent 只凭 CLI 走完生命周期，高风险动作由人确认。逐条对页面之后：

- **版本级的四个**（`publish` / `rollback` / `revoke` / `restore`）在那一版自己的页面上（[0054](0054-lifecycle-actions-happen-on-a-version-page.md)）——`version open` 的落点是对的。
- **skill 级的三个**（`offline` / `online` / `delete`）在「设置 › 常规」的危险区（`skillmaster-web/src/pages/SkillPage.vue:1521-1567`），而 `version open` 打开的是版本页——**那一页没有这三个按钮**。照 0061 的原文给地址，人打开后找不到动作，还要自己再找一次。

摆上桌的选项：

| | 做法 | 为什么被否 |
|---|---|---|
| a | 让 `version open` 也接受不带版本的 skill 地址 | 版本地址的语法里 `@版本` 是必需的（[0063](0063-a-draft-is-addressed-by-a-short-code.md) 的地址规则）；让它可省略会把「哪一版」变成可选项，而版本页上的动作都依赖它 |
| b | 拒绝句只打印网页首页 | 首页不是任何一个动作的落点；人还要再走两步才到那一页 |
| c | **新增 `skill open`，拒绝句按动作所在页给** | 采纳 |

## 决定

1. **CLI 新增 `skill open <ns>/<name> [--tab …] [--sub …]`**，与 `version open` 同形：打印地址并尝试打开浏览器，`--print` 只打印。
2. **拒绝句按动作所在的那一页给可执行命令**：版本级的四个 → `skillmaster version open <ns>/<name>@<版本>`；skill 级的三个 → `skillmaster skill open <ns>/<name> --tab settings`。
3. **拒绝句的其余部分不变**——为什么不能做、依据哪几条 ADR，七个动词共用。
4. **将来新增页面专属动作时，落点跟着动作走**，不再一律给版本页。

## 理由

交接的目的不是「告诉人不能做」，而是**让那一步真的能继续**。0061 写下那句话时，「去做」指向哪里还没有逐条对过页面；本版逐条对了，发现三个动作的页面对不上。把落点写成一条跟着动作走的规则，比在七个动词上各写一句更难漂——新增动词的人只需要回答「这个动作在哪个页面」。

**为什么不是「CLI 直接做」**：[0061](0061-what-a-credential-may-do-to-versions.md) §决定 1 的判据没有变，本 ADR 只修订它 §决定 4 的落点，不重新讨论哪些动作属于页面。

## 后果

- 0061 §决定 4 的落点一句被本 ADR 修订；那条决定的实质（页面专属动作不在 CLI）不变。
- CLI 多一个只读命令 `skill open`；它不改变任何状态，也不新增凭据能力。**三个 skill 级动作只有所有者能做**（设置页那一块是 `rung === 'owner'`），所以交接的对象是所有者。
- 拒绝句的第三句有两套文本。测试必须**逐字**钉住，否则落点会悄悄退回版本页——那正是本 ADR 要修的那一类错误。
