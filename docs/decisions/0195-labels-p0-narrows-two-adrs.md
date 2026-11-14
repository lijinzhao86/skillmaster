# 0195 · v66 标签 P0 对 0193 / 0194 的四点收窄

- **状态**：已采纳
- **日期**：2026-11-14
- **所属版本**：[`v66-skill-labels`](../versions/v66-skill-labels/README.md)
- **取代**：[0193](0193-public-labels-are-extensible-claims.md) §决定 3 的 `api_key` 写法与 §决定 7 的冲突留痕时点；[0194](0194-public-labels-are-agent-produced-and-sampling-audited.md) §决定 3「中置信生效」的展示口径、§后果 的触发口径、`taxonomy_version` 字段名与 `actor` 时点
- **依赖**：[0193](0193-public-labels-are-extensible-claims.md)、[0194](0194-public-labels-are-agent-produced-and-sampling-audited.md)

## 背景

0193 / 0194 在 2026-11-13 定下方向时，四处写法留了含糊：中置信「生效」没说展示不展示；抽取说「同步建草稿或发布时触发」，与「只治公域、已上线」的范围不一致；`taxonomy_version` 与实现里选的 `vocabulary_version` 不同名；覆盖冲突说「记 `actor` 与时间」，但 P0 没有任何写它的路径。

实现时按 v66 PRD / TD 做了选择。ADR 正文一旦写下不改，所以把选择记在这里，并让 0193 / 0194 的文件头指过来——不靠头注解释正文。

## 决定

1. **中置信的「生效」是落库，不是展示。** 中置信写为 `pending` 并进异常队列；读模型只展示 `auto` 与 `confirmed`（与 0193 §决定 6 一致）。取代 0194 §决定 3 的「生效但标待确认」中可被读成「页面可见」的那一半。
2. **抽取只对「公域 + 已上线」入队。** 发布或同步完成后触发；同步建出的草稿不抽。取代 0194 §后果 的「同步建草稿或发布时触发」。
3. **字段名以实现为准。** 词表版本列是 `vocabulary_version`（取代 0194 §后果 的 `taxonomy_version`）；wire 值与 `--label` 用连字符 `api-key`（取代 0193 §决定 3 的 `api_key`；上游 `requires_api_key` 仍叫原名字）。
4. **覆盖冲突的留痕推迟到 P2。** P0 只保证覆盖行是 `source=operator`、`confidence=1.0`、`confirmed`；谁改的、旧值、新值没有单独留痕，只有行级 `updated_at`（收窄 0193 §决定 7 的「留痕」与 0194 §后果 的「记 `actor` 与时间」）。存储形态见 v66 TD 开放问题 13。

## 理由

- **中置信必须说死。** 0193 已经定了「页面只展示高置信或运营确认」，0194 的「生效」若是展示，就等于在 `pending` 上再开一个可见性判断；把它解释为落库，两条 ADR 才不打架。
- **触发面按产品范围收窄。** 这一期只治公域、只治已上线；给草稿打标既没有用户可读的页面，也会在草稿被丢弃时留下无人复核的标签。
- **字段名早定早改便宜。** P0 没有部署，改名字只是改代码与词表 seed；上线后再改要迁数据。
- **冲突留痕先不假装。** 加一个没人写的 `actor` 列，会让读的人以为留痕已经存在；真正的设计取决于 P2 运营覆盖用什么形态（文件还是控制台），到那时一起定。

被否掉的：**直接改 0193 / 0194 正文**（违反「ADR 正文不改」）；**P0 先加 `actor` 列**（空列比没有列更容易误导）；**保留 `taxonomy_version` 以对齐旧文**（实现与文档必须同名，否则生成视图与读者都会错）。

## 后果

- 0193 / 0194 正文保持原样；读它们时以本 ADR 与 v66 TD 为准。两个文件头的状态行都标了「部分被 [0195](0195-labels-p0-narrows-two-adrs.md) 取代」。
- `deploy/label-overrides.yml` 与抽样 CI 仍按 0194 的「不提供运营专用 CLI」执行；本 ADR 不改那一条。
- P2 落地覆盖冲突留痕时，补 `actor` / 时间 / 旧值 的 DDL 与 ADR 头注；届时若形态超出「加列」，另开 ADR。
- 词表接口、`skills labels` 与 `--label` 一律用 `api-key`；`api_key` 只在上游字段 `requires_api_key` 的解释里出现。
