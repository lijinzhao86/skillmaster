# SOP · 给现有 skill 开一个新草稿

线上那一版不动；改的是草稿，上线后才会替换线上。

## 一、开草稿

```bash
skmr skill write <地址>@new SKILL.md --name "<这一版叫什么>" < ./SKILL.md
```

`@new` 会**新开一个草稿**，并打印**新版本码**和下一步。

只想改一处、不想整份重写，把 `write` 换成 `edit`：

```bash
skmr skill edit <地址>@new SKILL.md --old "<原文>" --new "<新文>" --name "<这一版叫什么>"
```

## 二、记住版本码

后面所有改动都用这一个版本码。**不要再调 `@new`**，那会另开一个草稿。

具体怎么写（能用「把 A 换成 B」说清就 `edit`，否则 `write` 整份写）见
[`03-edit-draft.md`](03-edit-draft.md)。

## 三、查相对引用

```bash
skmr skill check <地址>@<版本码>
```

## 四、交给用户上线

```bash
skmr skill open <地址> --tab versions
```

## 做完的标志

新版本码有了、读回来的内容和写的一致、你说了「这是草稿，上线要你自己去网页上点」。
