# 0001 · 批量上线的每一行真的在写的时候发出去了

> **日期**：2026-10-26
> **所属版本**：[`../README.md`](../README.md)
> **类型**：小版本迭代——**未改变对外可验收的能力边界**

## 改了什么

- `skillmaster-server/src/main/java/com/skillmasterai/api/WebPublishBatchController.java`
  —— `line()` 结尾把 `out.flush()` 换成 `response.flushBuffer()`；`HttpServletResponse` 从
  `publish()` 一路传进 `write()`。`line()` 与 `write()` 由 `private` 改为包内可见，供下面两条
  单测直接调用（类本身仍是包私有，对外 API 没变）。
- `skillmaster-server/src/test/java/com/skillmasterai/api/PublishBatchFlushTest.java`（新增，两例）
  —— ① `everyLineIsWrittenAndPushedOutOnItsOwn`：两行（`start`、`done`）字节逐字符相等（含行尾
  换行），**每行一次 `flushBuffer()`**；② `everyRowIsPushedAsTheLoopReachesIt`：两个 target 走真实
  循环，四行（`start`、两条 `row`、`done`）**四次推送**，且**推送发生在每一次 publish 之后**
  （第一次 publish 时已推 1 行、第二次时已推 2 行）。两例对着旧实现都是红的（推送 0 次）；把行攒到
  循环之后再逐条推，第二例也红（`[1, 1]`）。替身是手写的 `ScriptedBatch`（继承
  `PublishBatchUseCase` 覆盖 `publishOne`），与 `support/RecordingSmsSender` 同一做法，不引入 mock
  框架。
- `PublishBatchIT` 的类注释 —— 原文说"是否边做边到，是这个客户端看不到的传输事实"，
  现在这句话只对那条 IT 成立，指向新的单测。

## 为什么

**现象**（用户 2026-10-26 报告）：批量上线时第一条 skill 一直转圈，然后所有行同时变成成功。

**根因**，两层，缺一不可：

1. **Spring Framework 7 给 `StreamingResponseBody` 的是一个不 flush 的流。**
   `ServletServerHttpResponse.getBody()` 在 `spring.http.response.flush.enabled` 为假时返回
   `StreamUtils.nonFlushing(response.getOutputStream())`，而 `NonFlushingOutputStream.flush()`
   的字节码就是一行 `return`。这个开关走 `SpringProperties.getFlag(…)`，**默认 false**。
   于是控制器里每行之后的 `out.flush()` 一直是空转，整段 NDJSON 停在容器的输出缓冲里（Tomcat
   默认 8192 字节），直到 handler 结束才一次发出。**这一条以 body 装得进缓冲为前提**：超过 8 KB
   会按块自然刷出去；本批 1131 字节，远在缓冲以内。
2. **服务端每行确实要花时间**（本机实测每份草稿 150–600ms，见下表的时间戳），所以"一次性发出"
   正好落成"全程一条转圈、结束时一起变绿"。生产上同一现象由用户 2026-10-26 报告；那里每行要多久
   **没有采集过**，本文不含未经测量的生产数字。

**实测证据**（本机，2026-10-26）：

| 时点 | 修前 | 修后 |
|---|---|---|
| 请求发出 | 28.415668 | 23.100456 |
| 响应头到达 | **30.309402**（1.89s 后，与 body 同一刻） | 23.120129（20ms 后） |
| 每条 row 到达 | 无（只有一次 `Recv data, 1131 bytes`） | 每条一次 `Recv data`，间隔 150–600ms |

浏览器侧（headless Chrome 跑真页面）：修前 `0 / 10` 停住 2s 后直接 `10 / 10`；修后
`1 / 10`、`2 / 10`…`10 / 10` 每行一次重绘。

**为什么不是改配置**：把 `spring.http.response.flush.enabled` 打开也能修，但它是一个
`SpringProperties` 开关（系统属性或 classpath 上的 `spring.properties`），不是
`application.yml` 的键——放进部署脚本就是一台机器忘了设、流又静默退回缓冲的隐患。
`flushBuffer()` 是 servlet API 自己的推送，不依赖任何开关，影响的也只是一条路由。

**不改会怎样**：这一版的核心承诺（PRD 做 #9「执行与结算」、验收 #20「执行过程一条一条地看得见」）
在真实传输上不成立；页面测试是按行自己喂的，量不到它。

## 影响

- **影响哪些已交付行为**：批量上线的传输变成逐行发出。**正常行与请求级 4xx 的错误码不变**，
  事务边界、结算文案也不变。一处要说明白：`start` 一写出，响应就提交了，所以此后如果出现
  「没人命名的失败」，客户端看到的只能是 200 + 截断流（`done` 缺失）、由页面报「没有结果」——
  这正是控制器类注释本来写的语义，但它现在是**注定如此**，而不再取决于那一刻有没有提交。
  `X-Accel-Buffering: no` 仍然保留（nginx 那一层）。
- **需要同步改哪些文档**：`test-plan.md` —— T19 一行的口径收窄（它验的是页面，不是传输）、
  T18 的用例正文与结果行补注（它的期望里那句「不是攒到最后一起给」此前没有被任何用例验过）、
  §已知问题记上这条（已修复）、结果抬头补 2026-10-26 的复验；`technical-design.md` §接口 ——
  补一条实现约束（每行推的是响应，不是那个流）；`README.md` —— 状态与文档表收进本目录。
- **是否需要新开 ADR**：不需要。这是实现缺陷的修复，没有跨版本的取舍。
- **为什么没有加「计时」的集成用例**：要断言「首行早于批次结束到达」，就得让某一行确定性地慢下来
  （把 `PublishBatchUseCase` 换成带延时的替身是能做到的）。不做是因为**在测试库上每行只有毫秒级，
  断言必然依赖时序**——那是一个会闪的测试，而它会红的原因永远是调度，不是回归。所以回归停在这一层：
  **每行一次推送**（`PublishBatchFlushTest` 两例，对着旧实现红）。端到端的「真的逐行到达」由本节
  上表的 `curl --trace-time` 与 headless 复验证明，一次性记录，不作回归。
