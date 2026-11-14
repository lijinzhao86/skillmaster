# 找与列：搜索、浏览我的库

> `skmr skills search "<一句话说清要做的事>"` 按意思找；要按属性筛，加 `--label <维度>:<值>`，词表用 `skmr skills labels`。**默认只搜自己的库**——搜不到某个公开 skill，通常是因为它还没进你的库，不是因为它不存在。

## 查询怎么写

一句「这一步要做的事」，别堆关键词。一个意图搜一次；两个意图搜两次。

## 结果只是候选

列表里的每一条都只有一句简介（有属性时再多一行属性），**列出来不等于用得上**。只留正在教「这一步要做的事」的那几条；一条都不留，就换个说法再搜一次。

有属性时，搜索结果会多一行「属性」：最多三个短名（如 `Python`、`浏览器`），多出来的显示 `+N`。完整语义看 `skmr skills labels`。平台标注当前还在上线中，多数 skill 暂时没有这一行。

## 按属性筛选

```bash
skmr skills search "处理 PDF" --scope public --label runtime:python
```

- `--label <维度>:<值>` 只用于 `skills search`，可重复，多个取「且」；`skills list` 不接受。
- 键与短名以 `skmr skills labels` 为准；写错值时服务端会指出是哪个词。只有已有标注的 skill 会被筛出来。


## 加载这个 skill

```bash
skmr skill invoke <命名空间>/<名字>
```

- **完成信号**：输出开头带着这个 skill 的地址和版本——它已经生效了，并且这次用到的就是那一版。

之后照它说的做，包括它给出的命令。

## 列出我的库

```bash
skmr skills list
skmr skills list --source shared
skmr skills list --show draft
skmr skills list --category 文档工具 --public-tag pdf
```

按来源（`created` / `shared` / `joined`）、状态（`live` / `draft` / `offline` / `pending` / `deleted` / `all`）、分类、标签、名字筛。完整参数见 `skmr skills list --help`。

- **完成信号**：一行一条，末尾是总数。

## 网页上发现公开 skill

https://console.skillmasterai.com/discover

列的是**公开、有上线版本、你还没加入**的 skill，最近更新的在前。看到想用的，收进库：

```bash
skmr skill join <地址> --yes
```

## 搜不到或搜错了怎么办

| 情况 | 怎么做 |
|---|---|
| 搜不到 | 先看是不是没进库：加 `--scope public` 搜公开库；搜到了就 `skmr skill join <地址> --yes` 收进来 |
| 搜到很多不相关的 | 用 `--category` / `--public-tag` 收窄，或者把查询写得更具体 |
| 一个都没搜到 | 换说法再搜一次；还是没有，说明这件事不需要 skill，按你自己的能力做 |
| 已知地址，只想读里面某个文件 | 不用搜，直接 `skmr skill read`；读 `SKILL.md` 要先 `invoke` 过或写版本，见 [读一个 skill 里的文件](read-files.md) |

## 搜索时要注意的

- **搜索是按意思的**，写成一整句自然语言比堆关键词准；要按字面找，加 `--semantic=false`。
- **公开库不是默认范围。** 忘了这一点，会用「搜不到」误判「不存在」。
- **加载后会一直用那一版，直到重新 `invoke`。** 裸地址的读取不会自动跟线上更新。
