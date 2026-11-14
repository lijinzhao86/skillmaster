# 看清一个 skill 是什么

> 决定用不用它之前，先读它的信息：作者说它什么时候用、它要什么环境、许可允许你做什么、还有哪些版本。这些都能在命令行读到，不用打开网页。

## 一条命令看全

```bash
skmr skill show <地址>
```

输出描述、作者的「适用场景」、我的「适用场景」、环境、版本、来源、许可证与使用；空字段不打印。

- **完成信号**：打印出描述与版本。

## 许可允许你做什么

```bash
skmr skill license <地址>
```

给许可证、状态、版权、可做、要履行，以及全文的地址。**只读要点，不打印全文本身。**

## 标签与分类

```bash
skmr skill tags <地址>
```

三组：公开分类、公开标签、我的标签。

## 版本

```bash
skmr skill versions <地址>
```

能调用哪些版本。作者视角的版本状态用 `skmr version list <地址>`；看一版的事实用 `skmr version show <地址>@<版本>`；比较两版用 `skmr version diff <地址>@<新版本> --from <旧版本> --patch`。见 [草稿和线上版本的区别](../concepts/versions-and-drafts.md)。

## 写我自己的「适用场景」

```bash
skmr skill my-when-to-use set   <地址> --text "我要处理 PDF 时用它"
skmr skill my-when-to-use clear <地址>
```

只影响我自己的检索，不改作者的版本内容。

## 网页上看这个 skill

```bash
skmr skill open <地址> --tab info
```

## 判断要不要用时要注意的

- **`description` 与「适用场景」是作者写的，不是保证**；能不能满足你，看正文。
- **许可要读**：能不能改、能不能商用，写在 `license` 里。

## 读信息出错怎么办

| 现象 | 下一步 |
|---|---|
| 这个 skill 不在你的库里 | `skmr skills search` 找名字，或 `skmr skill join <地址> --yes` |
| 地址找不到 | 核对命名空间与名字 |
