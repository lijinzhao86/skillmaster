# SOP · 传和取单个文件

图片、PDF、Word、Excel、PPT、音视频这类**二进制文件**不走 `write`。`write` 是给文本用的：它从
标准输入读，服务端会做换行 / BOM / NFC 归一。二进制用 `put` 上传、`get` 取回。

## 一、先分清用哪条

| 要传的东西 | 用哪条 |
|---|---|
| Markdown、代码、配置——**文本** | `skill write`（或已在草稿里就 `edit` 一处） |
| 图片、PDF、Office、音视频——**二进制** | `skill put` |
| 只想在终端看一眼某个文件 | `skill cat` |
| 要把一个文件落到本地 | `skill get` |
| 要整版打包带走 | `skill download`（上限约 17 MiB；更大的版本用 `skill get` 逐个取） |

## 二、传上去

```bash
skmr skill put <地址>@<版本码> <本地文件> <远端路径>
```

- **只写已经存在的草稿。** 草稿还没有、而第一份内容就是二进制时，本版是三步：

```bash
printf '占位\n' | skmr skill write <地址>@new _placeholder.txt --name <名称>
skmr skill put <地址>@<打印出的版本码> <本地文件> <远端路径>
skmr skill rm <地址>@<版本码> _placeholder.txt
```

  占位路径**不要用 `SKILL.md`、不要与目标文件同路径、不要用底稿里已有的路径**（后者会覆盖底稿里的那份文件）；
  忘了第三步，那份占位文本会留在版本里。

- 目标已经存在时默认**拒绝覆盖**；确认要换加 `--overwrite`。
- 远端路径以 `/` 结尾，或一次传多个文件时，按每个文件的文件名放进那个目录：

```bash
skmr skill put <地址>@<版本码> ./design.png references/design.png
skmr skill put <地址>@<版本码> ./a.png ./b.pdf references/ --overwrite
```

## 三、取回来

```bash
skmr skill get <地址> <远端路径> [-o <本地路径>]
```

- 省略 `-o` 时落到当前目录的同名文件；`-o -` 写标准输出。
- 本地已经有同名文件时默认拒绝；确认要覆盖加 `--overwrite`。
- 命令会先写临时文件、成功后再改名：目的地不会被半份文件替换；中断会留下一个 `.part-` 文件，命令会告诉你路径，需自行删除（CLI 不删文件，ADR 0101）。
- 想只看内容、不要落盘，用 `skill cat`。

## 做完的标志

文件在草稿里、`skmr skill ls` 能看到、需要看内容时 `get` 回来确实是你传的那份。
**上线仍然只能由人在网页上点**——`put` 和 `get` 都不上线。
