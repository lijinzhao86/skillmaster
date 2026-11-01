# SOP · 在已有的草稿上继续改

接着上次那个草稿改，**不要**再开一个新的。

## 一、先找到版本码

```bash
skmr version list <地址> --status draft
```

第一行就是 `<地址>@<版本码>`。

## 二、write 还是 edit

分界不在「改动多少」，在**你手上有什么**：

| | `skmr skill write` | `skmr skill edit` |
|---|---|---|
| 你要给出 | **整份文件的最终内容** | **要被替换的原文** + 替换成的新文 |
| 适合 | 新建文件；整份重写；大段重排 | 把某段原文换成新文，其余不动 |
| 文件已存在 | 要加 `--overwrite`，否则拒绝（退出 1） | 直接改，不用 `--overwrite` |

能用一句「把 A 换成 B」说清，就用 `edit`；说不清、或者要动的地方太多，就用 `write` 整份写。

## 三、改

先看原文。`--old` 要逐字匹配，用 `cat`（`read` 的输出带行号，拿来当 `--old` 匹配不上）：

```bash
skmr skill cat <地址>@<版本码> <路径>
```

用 `edit` 换一处：

```bash
skmr skill edit <地址>@<版本码> <路径> --old "<原文>" --new "<新文>"
```

用 `write` 整份写（新加文件不用加 `--overwrite`）：

```bash
skmr skill write <地址>@<版本码> <路径> --overwrite < ./文件
```

## 四、查相对引用、交给用户上线

```bash
skmr skill check <地址>@<版本码>
skmr skill open <地址> --tab versions
```

## 做完的标志

改完草稿还在（没开出新草稿）、`check` 通过、你说了「这是草稿，上线要你自己去网页上点」。
