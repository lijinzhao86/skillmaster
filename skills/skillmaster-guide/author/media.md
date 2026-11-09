# 传图片、PDF、Office 和音视频

> 二进制文件用 `put`，它按原字节保存。用 `write` 传给已有草稿，文件会被当作文本处理并悄悄改写；传给 `@new` 则会直接报错。

## 先判断用 write 还是 put

| 文件是 | 用什么 |
|---|---|
| 纯文本（`.md` `.txt` `.json` `.yaml` …） | `write` |
| 图片、PDF、Office、音视频、压缩包 | `put` |

## 传一组文件到某个目录

```bash
skmr skill put <地址>@<版本码> ./logo.png ./cover.png assets/
```

- 远端路径以 `/` 结尾时，文件按自己的**文件名**放进那个目录。
- **只传一个文件、又要落到根目录**：把远端路径写成文件名本身（`... ./report.pdf report.pdf`），不要写 `.`。
- 目标已存在默认拒绝，覆盖加 `--overwrite`。

- **完成信号**：每个文件的成功信息。

## 新版本的第一份内容就是二进制时

`put` **不新建草稿**，所以先造一份：

```bash
printf '占位\n' | skmr skill write <地址>@new _placeholder.txt --name "这一版的名字"
skmr skill put <地址>@<版本码> ./report.pdf report.pdf
skmr skill rm <地址>@<版本码> _placeholder.txt
```

占位文件不要用 `SKILL.md`、不要与目标文件同路径、也不要用草稿里已有的路径——那会覆盖掉原来那一份。

## 传二进制时要注意的

- **别用 `write` 传图片**：管道按文本处理，存进去的东西坏了，而且不报错。
- **`put` 一定要带 `@<版本码>`**：裸地址只有在 `invoke` 钉过这份草稿之后才可以。
- **大文本不规整**：超过 16 MiB 就不再统一换行与编码，细节见 [上限](../limits/quotas.md)。

## 传文件出错怎么办

见 [上传不上去](../trouble/upload.md)。
