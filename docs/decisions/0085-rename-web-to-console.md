# ADR 0085 · `skillmaster-web` 改名为 `skillmaster-console`

- **状态**：已采纳
- **日期**：2026-10-23
- **依赖**：[ADR 0015](0015-web-frontend-stack.md)（浏览器子项目的建立与它刻意不引的东西）、[ADR 0075](0075-the-browser-client-is-react.md)（React）、[ADR 0076](0076-the-design-file-is-react-source.md)（设计稿的源码）

## 背景

`skillmaster-web/` 这个名字的含义是「浏览器这一端全在这里」。它建立时只有这一个浏览器产物，所以名字是准的。

v15 要新增公开站点：营销首页，之后还有文档、更新日志、协议。公开站点也是 Web。于是 `web` 不再能区分两个面——下一个人看到它，会以为公开首页也在里面。

候选有四个：保留 `web`；改成 `app`；改成 `platform`；改成 `console`。

## 决定

1. **把 `skillmaster-web/` 改名为 `skillmaster-console/`**。项目名、`package.json` 与 lockfile、CI 的 paths 与 working-directory、文档与代码注释里的路径跟着改；**行为零改动**。
2. **历史文档正文不改名**：ADR 与 iterations 里的 `skillmaster-web/` 是当时的事实；只修因改名而断掉的相对链接。
3. 本 ADR 取代 [ADR 0015](0015-web-frontend-stack.md) 里的**子项目名**；0015 的技术栈与「刻意不引」那七项纪律不变。

## 理由

- **`web` 与 `site` 同属交付形态**。拆出公开站点后，继续叫 `web` 就是让名字与所指分离。
- **`app` 会与将来的手机 App 冲突**。现在没有手机端，这个冲突看不见；有了之后每次说 `app` 都要加限定词。
- **`platform` 范围太大**。它天然指整个产品，而不是其中一个面。
- **`console` 与界面上的「控制台」是同一个词**。代码、文档、界面三处一个名字，最省解释。
- **行为零改动**，review 与 bisect 只看名字，不混功能。

被否掉的：**保留 `web`**（歧义留着）；**`app`**（与手机端冲突）；**`platform`**（范围过大）；**先不改、等公开站点落地再说**（那时改名会与 v15 的功能改动混在一起，更难分离）。

## 后果

- 一笔 `git mv` 与引用更新；CI 的 **job id 仍是 `check`**，required status check 的名字不变。
- 历史版本 TD / test-plan 里指向 `skillmaster-web/` 的相对链接改到新路径（否则 `check_docs.py` 红）；正文里的旧名保留。
- 公开站点的项目名、域名分域与手机端命名不在本 ADR——它们属于 v15 的文档与后续 ADR。
- [ADR 0015](0015-web-frontend-stack.md) 的文件头标注：子项目名由本 ADR 取代，正文里的旧名保留为当时事实。
