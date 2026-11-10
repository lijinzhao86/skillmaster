# 搜索评测基线（2026-11-11）

> **用途**：后续搜索排序、个人层和阈值优化的对照基线。它记录的是**当前产品行为**，不是某一版的需求。
>
> **口径**：本机真实服务 + 真实 DashScope `text-embedding-v4`（1024 维），独立测试库，`scope=library&semantic=true&limit=10`。基线来自 v59 提交 `8f27de9`（PR #169，2026-11-11）；合并后应以合并 commit 重跑一次再冻结。

## 一眼结论

后续优化拿**「写说明后」一列**当基线；「写说明前」是这次个人层的消融对照，不是产品当前状态。

| 组 | 指标 | 写说明前 | 写说明后（后续基线） |
|---|---|---|---|
| v19 库内回归（99 条） | Recall@10 | 0.899 | **0.939** |
| 个人精确（12 条） | Top-1 | 75% | **100%** |
| 个人精确（12 条） | Recall@5 | 83% | **100%** |
| 个人精确（12 条） | MRR | 0.792 | **1.000** |
| 个人改写（12 条） | Top-1 | 25% | **67%** |
| 个人改写（12 条） | Recall@5 | 67% | **92%** |
| 个人改写（12 条） | MRR | 0.392 | **0.753** |

公域卡片没有出现 `my_when_to_use`。个人精确组 Top-1 100%（目标 ≥90%），改写组 Recall@5 67% → 92%（+25 个百分点，目标 +20）。

## 数据集

同一批 **33 个 fixture skill**，来自 v19：

- [`labeled.tsv`](../../skillmaster-server/src/test/resources/retrieval/labeled.tsv)：99 条，66 条语义式 + 33 条名字式
- [`unmatched.tsv`](../../skillmaster-server/src/test/resources/retrieval/unmatched.tsv)：38 条负样本
- [`skills.json`](../../skillmaster-server/src/test/resources/retrieval/skills.json)：33 个 skill 的 name / description

v59 新增：

- [`v59-personal.tsv`](../../skillmaster-server/src/test/resources/retrieval/v59-personal.tsv)：24 条，`personal-exact` 12 条 + `personal-rewrite` 12 条；每条带期望 skill 与要写的 `my when-to-use`

## 怎么复跑

先起一个独立库与真实服务（服务端要允许本地测试验证码；不要用生产库）：

```bash
DASHSCOPE_API_KEY=... python3 skillmaster-server/scripts/retrieval-eval-v59.py \
  --base http://127.0.0.1:8082 --db skillmaster_v59_v19
```

脚本会自己注册账号、写入 33 个 skill 与作者向量、跑写前基线、写 12 句个人说明、再跑一次。向量按真实 provider 计算；它不是 CI 测试，跑一次会花钱，这一点与 v19 的 [`retrieval-eval.py`](../../skillmaster-server/scripts/retrieval-eval.py) 相同。

## 指标定义

- **Recall@10**：期望 skill 出现在前 10 的查询数 ÷ 该组查询数。v19 回归集沿用 v19 自己的定义。
- **Top-1**：期望 skill 排第 1 的查询数 ÷ 该组查询数。
- **Recall@5**：期望 skill 进前 5 的查询数 ÷ 该组查询数（零结果算失败）。
- **MRR**：平均 `1 / 期望 skill 名次`；未命中记 0。
- 三组的查询数固定：v19 99，个人 exact 12，个人 rewrite 12。

## 口径与限制

- **v19 的 0.939 与库内基线的 0.899 不是同一指标**。前者是 v19 纯向量脚本的 Recall@10；后者是同一标注集走当前库内检索（含 S0 名字/标题分级和 10 条上限）的写前基线。写说明后库内回到 0.939。比较时不要混用。
- 数据只有 33 个 skill、123 条查询，不外推到生产全量。
- 两条个人改写没有到第 1、其中一条仍 miss：`我那个在线小工具线上出故障了`、`帮我把这些数据整理成可筛选的表格`。如实保留，没有为了让数字好看改 note。
- 基线的「写后」依赖 12 句个人说明；换一个人写不同的句子，数字会变。它是**这套数据 + 这组说明**的基线，不是「个人层理论上限」。

## 后续怎么用

1. 改排序、阈值、S2 margin、S1/S2 分级或库内召回逻辑后，先跑同一脚本，比较「写后」一列。
2. 公域检索改动另跑 v19 的 [`retrieval-eval.py`](../../skillmaster-server/scripts/retrieval-eval.py)，不要拿本页的库内数字代替。
3. 数据集或说明文本变了，基线必须重跑并更新本页；不要只改代码留着旧数字。
