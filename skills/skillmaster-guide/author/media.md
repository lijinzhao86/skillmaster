# 传图片、PDF、Office 和音视频

> 二进制也用 `skmr skill write`，区别只在内容从哪来：加 `--from <本地文件>`，它按原字节上传；从标准输入读的当文本处理。

## 先判断内容从哪来

| 文件是 | 怎么写 |
|---|---|
| 纯文本（`.md` `.txt` `.json` `.yaml` …） | `skmr skill write <地址>@<版本码> <路径> < ./本地文件` |
| 图片、PDF、Office、音视频、压缩包 | `skmr skill write <地址>@<版本码> <路径> --from ./本地文件` |

## 传一组文件到某个目录

```bash
skmr skill write <地址>@<版本码> assets/ --from ./logo.png --from ./cover.png
```

- 远端路径以 `/` 结尾时，文件按自己的**文件名**放进那个目录。
- **只传一个文件、又要落到根目录**：把远端路径写成文件名本身。
- 目标已存在默认拒绝；覆盖要先 `skmr skill read <地址>@<版本码> <路径>` 完整读一遍，再加 `--overwrite`。

- **完成信号**：`skmr skill stat <地址>@<版本码> <路径>` 显示四列，类型是 `bin`、大小对得上。

## 新版本的第一份内容就是二进制时

先开一份草稿，再写：

```bash
skmr draft create <地址> --name "这一版的名字"
skmr skill write <地址>@<版本码> report.pdf --from ./report.pdf
```

## 传二进制时要注意的

- **覆盖要先读**：`--overwrite` 只在「本机完整读过这个文件、读后内容没变」时放行。
- **别把 `skmr skill read` 的输出直接写回去**：它带行号，会被拒绝。
- **多个文件不是一个整体**：中途失败会列出已写入 / 失败 / 未开始，修好后重跑同一条命令。
- **空内容要明说**：标准输入为空时加 `--allow-empty`。
- **大文本不规整**：超过 16 MiB 不再统一换行与编码，见 [上限](../limits/quotas.md)。

## 传文件出错怎么办

见 [上传不上去](../trouble/upload.md)。
