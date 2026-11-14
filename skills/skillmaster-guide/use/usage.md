# 看使用记录

> `skmr usage` 回答两类问题：我自己用过什么；我的 skill 被谁用过、有没有被搜到。一条交互一行：搜索词、结果数、随后调用的是哪条、排第几。

## 我自己用过什么

```bash
skmr usage
skmr usage --days 7
skmr usage --stats
skmr usage --skill pdf
```

- 默认近 30 天；`--days` 取 1–90，或 `--since` 加 `--until` 自定义区间。
- **完成信号**：明细行，加一行写明区间的尾行。

## 我的 skill 的使用者表（所有者）

```bash
skmr usage <地址>
skmr usage <地址> --users
skmr usage <地址> --mine
```

- `<地址>` 是**所有者视角**：聚合加使用者表。只有私有 skill 有使用者表；公开 skill 只给聚合。
- `--users` 只要使用者表（所有者 + 当前协作者，含 0 次调用）；`--mine` 是本人在它上面的使用，按版本。
- **完成信号**：`--users` 列出所有者与当前协作者；已撤销授权的人不在表里，但计入聚合。

## 使用记录的翻页与导出

```bash
skmr usage --page 2 --limit 100
skmr usage --json
```

## 网页上看使用记录

https://console.skillmasterai.com/usage

## 读使用记录时要注意的

- **「最终调用了它」才代表真的用上了**（`--json` 里是 `converted`）：搜到之后没调用，只算一次搜索。
- **区间写在首行与尾行**，贴给别人时一起带上，否则数字没有意义。

## 使用记录出错怎么办

| 现象 | 下一步 |
|---|---|
| 没有这个 skill | 核对地址；聚合与使用者表只给所有者 |
| 页码、条数或区间不合法 | 核对 `--page`、`--limit` 与日期 |
