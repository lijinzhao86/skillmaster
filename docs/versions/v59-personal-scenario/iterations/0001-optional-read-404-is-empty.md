# 0001 · 可选读 404 按空态处理，不冒充「整个 skill 找不到」

> **日期**：2026-11-11
> **所属版本**：[`../README.md`](../README.md)
> **类型**：小版本迭代——**未改变对外可验收的能力边界**

## 改了什么

- 控制台 `skillmaster-console/src/hooks/useSkillMyWhenToUse.ts`：`GET …/my-when-to-use` 返回 404 时，把句子读成空（`''`）并照常渲染空态，不再写页面横幅；其他失败仍按原样显示。
- 测试：新增 `skillmaster-console/tests/my-when-to-use-skew.test.tsx`，锁住 404 → 空态、500 → 横幅两种行为；`tests/support.ts` 的 `stubMyWhenToUse` 现在可以传一个完整的 `Response` 来描述拒绝。
- 测试夹具 `SKILL` / `LIVE_VERSION` / `DRAFT_VERSION` 从 `tests/skills.test.tsx` 挪到 `tests/skill-page-harness.tsx`，供这条用例复用。

## 为什么

2026-11-11 上线后发现：服务端被单独回滚到 v58、控制台仍是 v59 时，信息页顶部弹出「这个地址在你的 skill 里找不到」，而同一页明明画着这个 skill。根因是新控制台调用了 v58 没有的 `/my-when-to-use` 路由，Spring 的统一 404 映射返回 `skill_not_found`（`ApiExceptionHandler`：所有未映射路由与不可见 skill 共用这个码）。

这句文案在语义上是错的：页面已经解析出 skill，这个读只是可选的一层。把它改成空态，既符合「读不到就是还没写」的事实，也让「服务端单独回滚」这一发布文档允许的运维动作（[`.dev-sop/release/README.md`](../../../../.dev-sop/release/README.md)：各端独立 dispatch、可切回上一 release）不再制造假故障。没有选「删掉这个读」：它在正常版本下有内容；也没有改服务端的 404 映射：那是所有读共享的契约。

## 影响

- **影响哪些已交付行为**：只影响控制台信息页对这一个可选读失败的显示；正常读、写入、清除的行为不变。写入失败仍走页面横幅（旧服务端下 PUT 同样 404），本次不改。404 之后同一页面会话不再重读（与「成功只读一次」一致），刷新或重新进入页面会再试。
- **需要同步改哪些文档**：README 状态行与「本版文档」表已登记本条；`prd.md` §显示补了失败规则；`test-plan.md` T15 的证据补了新测试。均已完成。
- **是否需要新开 ADR**：不需要，因为这是失败态的显示选择，没有改变对外契约或跨版本取舍。
