# 0179 · 文本上限统一用 Unicode 码点，写入与发布同契约

- **状态**：已采纳
- **日期**：2026-11-11
- **所属版本**：[`v59-personal-scenario`](../versions/v59-personal-scenario/README.md)
- **取代**：v1 技术设计里「`description` 1–1024 字符」这条只写在文档、没有实现的上限（[`v1 TD`](../versions/v1-hosting/technical-design.md)）

## 背景

作者元数据只有一个入口：`SKILL.md` 的 frontmatter。服务端在写入时解析它（`SkillDocument.parse`），发布时把那一版的解析结果投影到公开卡片（`SkillRepository.recordPublish`）。这条链上没有第二个编辑 `description` / `title` / `when_to_use` 的接口，也不应该有——文件是唯一可携带、可版本化的事实源。

今天的问题不是「没有入口」，而是**入口没有完整的契约**：

- `description` 规范写的是 1–1024 字符（[`SKILL.md` 规范](https://agentskills.io/specification)，仓库 [`v1 TD`](../versions/v1-hosting/technical-design.md) 同值），实现**只校验非空**，没有上限；
- 作者的 `when_to_use` **完全没有上限**；
- v59 新增的「我的适用场景」定的是 200，但服务端接口尚未实现。

这留下三个具体风险：

1. **检索会静默丢内容。** 向量文本是 `name + title + description + when_to_use` 拼成一个字符串发给 DashScope `text-embedding-v4`；这个模型单条上限 8,192 tokens，超出部分**静默截断**，不报错。描述没有上限，后半段可能根本没进向量。
2. **同一份 `SKILL.md` 在不同端不一致。** 超过规范上限的包在别的客户端可能装不下或被截断，而我们的平台接受它。
3. **"字符"没有单位。** 字节、UTF-16 单元、显示宽度、词数、token 各算各的；中文与英文在每种口径下的得失不同。

考虑过的口径与接口：

1. **UTF-8 字节**：1024 字节 = 1024 个英文字母，或 **341 个汉字**——把不公平翻给中文，而且和规范写的「1024 字符」对不上。
2. **UTF-16 `length()`**：中文与英文各 1，但 emoji 被算成 2。
3. **显示宽度**（汉字 = 2）：对齐视觉长度，不是信息量；要引入 wcwidth/ICU 与 emoji 宽度规则，跨端容易算得不一样。
4. **词数 / token**：中文分词没有统一口径；token 与具体模型绑定，换模型数字就变，不能做产品契约。
5. **另开一个 metadata 接口**：会变成 `SKILL.md` 与数据库两个事实源，下载 / 上传 round-trip 会断，版本模型也会被绕开。
6. **拒绝未知 frontmatter 字段**：`metadata` 本来就是开放的，拒掉等于拒绝生态扩展。

## 决定

1. **单位统一为 Unicode 码点**：一个汉字、一个英文字母、一个标点都算 1；Java 用 `codePointCount`，Go 用 `utf8.RuneCountInString`，控制台按码点截断而不是 HTML 的 `maxlength`（它数 UTF-16 单元）。
2. **三个上限**：
   - `description`：**1–1024 码点**，必填；
   - 作者的 `when_to_use`：**0–200 码点**，可选；
   - 「我的适用场景」：**1–200 码点**；空值拒绝，清除是独立的 `DELETE`。
3. **`SKILL.md` frontmatter 是作者元数据的唯一事实源**；`SkillDocument.parse` 是唯一契约函数，覆盖上传 / `skill submit`、新建 skill、草稿里写根 `SKILL.md`、同步 / 导入。**发布时再校验一次**，旧规则下写的草稿不能绕过新规则。
4. **拒绝时给结构化回执**：字段路径、问题码（`required_field_missing` / `field_not_text` / `too_long` / `name_too_long`）、当前值与上限、下一步；一次报完所有问题，不只报第一个。
5. **未知字段保留**（生态扩展不拒）；**近似拼错告警**（`whenToUse` / `when-to-use` 等），不静默当成没有。
6. **同步 / 导入不合规：拒绝并跳过**，不静默截断作者原文；拒绝原因进日志。把被拒条目汇总发运营邮箱不在本版（记在 [`TODO.md`](../current/TODO.md)）。

## 理由

- 1024 不是我们拍的：它是 `SKILL.md` 规范给 `description` 的兼容上限，也是 v1 技术设计写下的值；超过它，别的客户端可能装不下或截断。
- 200 与现有的标签说明同档（`SkillTagService.MAX_DESCRIPTION_CODE_POINTS = 200`），一个触发句不该比一条标签说明更长。
- 码点不承诺「中英信息量相等」，只承诺**两种语言都能在同一个上限内把该说的说完**：1024 个英文词约 150–170 个；常见 skill 描述 20–60 个词，有 2.5–8 倍余量。200 个英文词约 30–40 个，够写「什么时候用 / 什么时候不用」。
- 唯一事实源 + 写入与发布同契约，比另开 metadata 接口更能防漂移；未知字段保留是为了不把生态扩展当成违规。
- 发布复校验是必要的：规则升级后，旧草稿不能因为「写的时候还没有这条规则」而绕过。

## 后果

- 现有行不重写；只在新写入与发布时生效。上线前用只读 SQL 核现有最长值；已有超限的，先决定 grandfather 还是让作者改。
- 旧草稿可能在新规则下无法发布——这是有意的；回执要指向具体字段，并给「回草稿改 `SKILL.md`」的下一步。
- 演示件的 341 字 `when_to_use` 替身（`LONG_WHEN_STRESS`）超过 200，要缩到 200 以内；「作者长句在宽屏折行」那一态随上限消失（窄屏仍可能折）。
- 向量输入稳定在 8,192 tokens 以下（1024 + 200 远低于上限），不再有静默截断。
- 字段上限的生成视图（`docs/current/limits.md`）由代码常量派生，不在本 ADR 手写；实现时加 `SkillFieldLimits` 源并扩 `generate.py`。
- 拒绝回执的运营邮件汇总是后续待办，不阻塞本版。
