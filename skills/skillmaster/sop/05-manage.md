# SOP · 管理 skill

共享、看版本、丢弃草稿。上线与删除下面单独说。

## 共享

看自己共享给了谁：

```bash
skmr skill shares <地址>
```

共享给别人：

```bash
skmr skill share <地址> --to <用户名> [--role invoke|browse|write|maintain|admin]
```

- 五档从低到高：`invoke` 调用 → `browse` 浏览 → `write` 写入（**也能看草稿**）→ `maintain` 维护 → `admin` 管理（连协作者一起管）。每一档都包含它下面的档。
- 默认给 `invoke`；用户说「让他管理」，给 `admin`；说「让他能改」，给 `write`。
- 不知道地址，先用 `skmr skills list <关键词>` 找。
- 撤销共享用 `skmr skill unshare <地址> --user <用户名>`。

## 看版本

```bash
skmr version list <地址>
```

丢弃一版草稿：

```bash
skmr version discard <地址>@<版本码>
```

## 只在网页上做的

上线、回滚、下架、重新上架、下线、恢复服务、改可见性、删除，都只能在网页上点——**为什么、删除的例外是什么，见 [`00-model.md`](00-model.md)**。这里只说 CLI 的行为：

- 正常交接：同名命令不执行动作，而是打开那一页并说明「还没执行」，**退出 0**。照它打印的地址把用户带过去就行。
- 档位或状态不对：**不开页面、退出 1**，照它的原因转述——那不是命令坏了（ADR 0126）。

要带用户过去：

```bash
skmr skill open <地址> --tab versions
```

打不开浏览器就加 `--print`，把地址给他。

## 做完的标志

能说出「哪个 skill、共享给谁、什么档位」，或者把用户送到了对应的网页并说清要他自己点。
