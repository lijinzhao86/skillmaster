# ADR 0148 · 许可证属于版本，全文不进 `version_file`

- **状态**：已采纳（「五种状态」部分由 [ADR 0154](0154-v49-does-not-adjudicate-license-conflicts.md) 取代）
- **日期**：2026-11-05
- **所属版本**：[`v49-public-signals`](../versions/v49-public-signals/README.md)
- **依赖**：[ADR 0069](0069-metadata-is-a-sidecar-not-content.md)（`.skillmaster/` 是平台保留路径）、[ADR 0005](0005-content-addressing.md)（内容按摘要寻址）

## 背景

公开意味着别人能把内容带走，所以公域信息必须回答「这个版本的许可证是什么」。但上游仓库的许可证摆放并不统一：有的在仓库根放一份 `LICENSE` 管全部；有的把 skill 放在 `skills/<name>/` 下，许可在 skill 目录里；也有的只在 `SKILL.md` 写一句 `license: MIT`，没有任何全文。同一个仓库的两个 skill 甚至可以有不同许可。

同时，版本的内容摘要是内容的身份：同一份内容重新下载必须得到同一个摘要。如果把平台补上的 `LICENSE` 当成版本内容写进 `version_file`，摘要就变了，上传回去与上游对不上，`skill ls` 也会多出一份作者没写过的文件。

## 决定

1. **许可证按版本存一行**：`skill_version_license(version_id …)`。一个版本一条，因为下一个版本可能来自另一个 commit、另一个许可。
2. **解析顺序是「最近的许可文件优先」**：skill 目录 → 逐级向上 → 仓库根；记录命中的 `source_path`。`SKILL.md` 的 `license` 只是声明，不是全文。
3. **五种状态，服务端给结论**：`ok`（有全文）、`declared-only`（只有声明）、`missing`（都没有）、`conflict`（仓库值 ≠ 声明值）、`other`（不在固定表里）。`conflict` 把两个值并列写进 `detail`，不下结论；`missing` 不默认 MIT。
4. **全文存在 `license_text`，不挂 `version_file`。** 它不参与内容摘要，不出现在文件清单，不改导入后的 digest；blob GC 只跟随 `version_file`，所以也不会误删。
5. **下载/安装时生成 `.skillmaster/` 侧车**：`license.json`（结构化）、`LICENSE`（上游正文没有许可文件时才写）、`attribution.md`（来源、路径、commit、版权、修改说明）。侧车是平台生成的元数据，不是版本内容；上传整个 `.skillmaster/` 被剥掉。

## 理由

- **版本是内容身份，许可跟着版本。** 同一个 skill 的 1.0 与 2.0 可能换了上游 commit，许可也可能变；存在 skill 上会让两个版本共用一条过时的结论。
- **全文不能进版本内容。** 这不是洁癖：摘要一变，`skill get` 下载再 `submit` 就与上游不是同一份内容；而且普通作者没有义务上传一份平台生成的许可。
- **「只写 MIT」不够。** MIT 的义务是保留版权与许可声明；只给一个 SPDX 值，用户不知道要保留什么。全文（或明确「仅声明，未附全文」）才是可执行的说明。
- **冲突不下结论。** 仓库与 `SKILL.md` 不一致时，平台不知道哪个是真的；并列两值、指向来源全文，比替用户选一个安全。
- **不默认 MIT。** 没有许可就是没有授权；把「未声明」读成「随便用」是这一层最危险的误读，而它不会报错。

被否掉的：**全文塞进 `version_file`**（污染摘要、`skill ls` 与上传后的 digest）；**只存 SPDX**（不满足声明保留义务）；**存在 skill 上而不是版本上**（换 commit 后漂移）；**默认 MIT**（把未授权读成授权）；**冲突时替用户选仓库值**（没有依据）。

## 后果

- `skill_version_license` 多一份全文副本；许可文本很小，代价可接受。
- 公域导出与 `skill get` 的 zip 里多三个 `.skillmaster/` 条目；内容文件保持不变。
- 本版不导出 `conflict` / `missing`（那是下一版开源同步的发布闸），只展示状态。
- `license.json` 的 `license_file` 指向上游命中的文件；没有命中时不写，`LICENSE` 侧车也不会重复写原文。
