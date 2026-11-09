# 第一次：从建到上线

> 五步：登录 → 建 → 写 → 自查 → 上线。**每一步都有一个完成信号**——看不到信号就停下，否则错误会一路带到最后。

## 第 1 步：确认登录

```bash
skmr auth status
```

- **完成信号**：打印出你的账号名，而不是「本机没有凭据」。
- 没有？见 [登录](../account/sign-in.md)。

## 第 2 步：建出 skill

```bash
skmr skill create <名字> --description "<做什么 + 什么时候用>"
```

- **完成信号**：打印出地址和一个 `@版本码`，形如 `已新建 lijinzhao/pdf-tools@0NW9P6PP`。**记下版本码**，后面每一步都用它。

## 第 3 步：写 SKILL.md

`create` 给的是一个占位文件。把它覆盖掉——注意 frontmatter 里的 `name` **必须和地址里的名字一字不差**，内容从本地文件读进来：

```bash
skmr skill write <地址>@<版本码> SKILL.md --overwrite < ./SKILL.md
```

- **完成信号**：`skmr skill ls <地址>@<版本码>` 里能看到 `SKILL.md`。
- 命令的写法与坑见 [从零建一个 skill](../author/create.md)。

## 第 4 步：自查断链

```bash
skmr skill check <地址>@<版本码>
```

- **完成信号**：退出码 0，标准输出没有内容。
- 有断链就先修——上线之后这一版就冻结了。

## 第 5 步：上线

```bash
skmr version publish <地址>@<版本码>
```

`skmr version publish` 会打开那一版的确认页，**在网页上点**。

- **完成信号**：`skmr version list <地址> --status live` 里能看到这一版。
- 细节见 [把一版草稿上线](../ship/release.md)。

## 第一次上线时要注意的

- **第 4 步不要跳。** 断链上线后改不掉，只能再发一版。
- **上线之后要改，是开新草稿**，不是重走这五步，见 [改一个已有的 skill](../author/edit.md)。
