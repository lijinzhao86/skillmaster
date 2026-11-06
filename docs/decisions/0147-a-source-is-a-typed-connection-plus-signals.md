# ADR 0147 · 来源是「有类型的连接 + 适配器解释的通用信号」

- **状态**：已采纳
- **日期**：2026-11-05
- **所属版本**：[`v49-public-signals`](../versions/v49-public-signals/README.md)
- **依赖**：[ADR 0146](0146-public-is-platform-endorsement.md)（公域只收官方背书的内容，所以每条都能说清出处）、[ADR 0010](0010-storage-in-postgres.md)（PostgreSQL 是存储）
- **修订**：指标不再上信息页，只在 `skill show` 打印；信息页只写平台与仓库（2026-11-06，见 v49 TD §来源解析与刷新 / §CLI）

## 背景

公域收口之后，skill 的来源会越来越杂：官方自建、GitHub 仓库、SkillHub、以后可能还有别的平台。每个平台能给的指标也不一样——GitHub 给 stars / forks / 最近提交，SkillHub 可能给安装量，官方来源什么指标都没有。

直接的做法是「每来一个平台加一列」：`github_stars`、`skillhub_installs`……这条路的问题不在第一版，而在第二版：表、API、控制台、CLI 都要跟着加字段，而且页面必须知道每一列属于哪个平台。另一种做法是存一个自由 JSON 对象，页面按平台名分支读字段——问题只是从数据库搬到了页面。

## 决定

1. **`skill_source` 只有通用列**：`role`（origin / mirror）、`kind`、`locator`、`path`、`label`、`url`。一个 skill 最多一条 origin，mirror 可多条。
2. **每个平台实现一个 `SourceAdapter`**：`parse(locator, path)` 只做校验与规范化、不联网；`fetch(binding, revisionHint)` 联网取快照，失败作为值返回（`status=error`），不抛到页面。注册表 `SourceRegistry` 是 `kind → adapter`，重复 kind 启动失败，未知 kind 在发布时拒绝。
3. **指标是一组约定数组**，不是每个平台一列：
   ```json
   [{"key":"stars","label":"Stars","kind":"count","value":"1234",
     "display":"1.2k","url":"https://github.com/…/stargazers"}]
   ```
   `kind` 只有 `count` / `date` / `text`；页面按 `label` 与 `display` 渲染，永远不知道 GitHub 这个名字。
4. **快照绑版本**（`skill_source_snapshot`）：1.2.0 来自 commit A、1.3.0 来自 commit B，各自一条；刷新只改 `signals / status / fetched_at`，不改 `revision`。
5. **来源写在 SKILL.md 的可选元数据里**（`metadata.source`），随版本走。官方命名空间没声明时自动写 `kind=official`；声明了外部来源的官方 skill，按声明的 kind 记录——这正是「官方背书的外部来源」。

## 理由

- **加一种来源改动的文件数，是这套模型唯一的验收。** 按这条做，接 SkillHub 只需要新增一个适配器与它的测试；表结构、API 字段、控制台组件、CLI 格式化都不改。按列做，则每接一个平台都要改四处，第四处迟早漏。
- **自由 JSON 只是把分支推迟。** 页面仍要问「这是 GitHub 还是 SkillHub」，才知道 `stars` 怎么读；约定数组把这个问题消掉了：每条信号自己带 `label / display / url`。
- **快照绑版本，不绑 skill。** 页面说「这个版本对应哪个 commit」，就必须能回答两个版本来自两个 commit；绑在 skill 上，历史版本只能显示最后一次抓到的值。
- **来源是内容元数据，不是控制台字段。** 它随版本走，才能和许可证、commit 一起解释「这一版到底是什么」；由运营在控制台单独维护的来源会在版本之间漂移，而版本是内容身份。

被否掉的：**每平台一列**（加平台要改 schema/API/页面/CLI）；**自由 JSON**（页面按平台分支）；**来源只存在发布记录里**（历史版本没有来源，且发布记录是审计流水，不是读模型）；**页面实时请求 GitHub**（把第三方可用性绑进读路径）。

## 后果

- 未知 `kind` 的发布被拒绝（`invalid_source`），不会落半条来源。
- 适配器失败时页面仍画来源身份，指标位写「指标暂不可用」；快照保留上一次成功值。
- 本版只接 GitHub 与官方两个适配器；mirror 只建了数据形状，没有写入路径。
- 信号数组上限 8 条、`label` 最多 12 字，由适配器遵守；v49 只展示，不参与排序。
