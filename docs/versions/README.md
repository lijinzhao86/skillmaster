<!-- 生成物，勿手改。源：docs/versions/*/README.md；重新生成：python3 scripts/docs/generate.py -->

# 版本记录

v1–v39 是**历史记录**：正文冻结，不再修改。v40 起只写「相对 [`current/`](../current/README.md) 的 delta」，不重述系统现状。

| 版本 | 状态 |
|---|---|
| [`v1-hosting`](v1-hosting/README.md) | 进行中 |
| [`v2-credentials`](v2-credentials/README.md) | 进行中（**三个组件都已实现**：服务端 P0、控制台 P1、CLI P2）… |
| [`v3-sessions`](v3-sessions/README.md) | **已完成**（实现已合入 `main`，2026-10-16；证据见 `test-plan.md` §结果） |
| [`v4-cli-login`](v4-cli-login/README.md) | 已实现并合入 `main`（PR #10，合并提交 `68fb7a3`，2026-10-16）… |
| [`v5-skill-management`](v5-skill-management/README.md) | 已实现并合入 `main`（PR #12，服务端 P0 / 网页 P1 / CLI P2） |
| [`v6-version-management`](v6-version-management/README.md) | 已实现并合入 `main`（PR #13，合并提交 `b5dfca8`，2026-10-20）… |
| [`v7-cli-file-io`](v7-cli-file-io/README.md) | 已实现并合入 `main`（PR #14，合并提交 `856277e`） |
| [`v8-web-editor`](v8-web-editor/README.md) | 已实现（服务端与网页均已落地，本地测试与设计检查通过；视觉验收待人工） |
| [`v9-skill-transfer`](v9-skill-transfer/README.md) | 进行中（PRD、设计稿、技术设计与测试方案已定稿；**代码已实现**）… |
| [`v10-cli-lifecycle`](v10-cli-lifecycle/README.md) | **已实现，验收部分执行**（服务端三条作者面路由 + 三条消费面路由、CLI 五个动词与 `@new`）… |
| [`v11-publish-handoff`](v11-publish-handoff/README.md) | 已实现（三份文档成稿；CLI 与网页改动落地，`go test ./...`、`npm run test`）… |
| [`v12-react-frontend`](v12-react-frontend/README.md) | **已完成**（2026-10-22 合并到 `main`，合并提交 `45378f2`，PR #22） |
| [`v13-cli-grammar`](v13-cli-grammar/README.md) | 进行中（三份文档已写；实现已在 `cli/skills-grammar` 分支完成）… |
| [`v14-skill-tags`](v14-skill-tags/README.md) | 进行中（`prd.md` 已定稿：2026-10-23 扩了 CLI 的标签写、库加入 / 移除）… |
| [`v15-marketing-home`](v15-marketing-home/README.md) | 进行中——**代码已实现**（2026-10-24，见 test-plan §结果），**未交付**：视觉验收… |
| [`v16-cli-help`](v16-cli-help/README.md) | 进行中——PRD 已锁定（多轮三路循环审计清零，`7938773`） |
| [`v17-cli-startup`](v17-cli-startup/README.md) | 进行中，实现阶段 |
| [`v18-batch-publish`](v18-batch-publish/README.md) | 进行中，**实现已完成、循环审计已走完第三轮**（2026-10-25） |
| [`v19-skills-retrieval`](v19-skills-retrieval/README.md) | 已上线（2026-10-25） |
| [`v20-guidance-on-platform`](v20-guidance-on-platform/README.md) | 进行中 |
| [`v21-startup-state-line`](v21-startup-state-line/README.md) | 已完成（已合入 `main`，随 CLI `1.0.1` 发布；单测全绿，**真机 T11 仍未执行**） |
| [`v22-cli-help-and-prompt`](v22-cli-help-and-prompt/README.md) | 进行中（PRD 已锁定 2026-10-27；P0 / P1 / P2 代码已落地）… |
| [`v23-invoke-metadata`](v23-invoke-metadata/README.md) | 进行中（**服务端已上线**，release `7109acd`… |
| [`v24-cli-file-footprint`](v24-cli-file-footprint/README.md) | 进行中（**已实现并合入 main**，merge `1485383`，2026-10-27）… |
| [`v25-cli-self-update-and-prompt`](v25-cli-self-update-and-prompt/README.md) | **已实现并合入 main**（merge `a6ebc2e`，2026-10-27） |
| [`v26-startup-guidance-and-agent-paths`](v26-startup-guidance-and-agent-paths/README.md) | **实现中（beta）** |
| [`v27-rule-block-and-prompt-retirement`](v27-rule-block-and-prompt-retirement/README.md) | 已完成（代码与文档同分支 `feat/rule-block-searches-by-default`，未提交） |
| [`v28-console-brand-links-home`](v28-console-brand-links-home/README.md) | 已完成（代码与文档同分支 `feat/console-brand-links-home`，文档与守卫用例未提交） |
| [`v29-cli-rename-to-skmr`](v29-cli-rename-to-skmr/README.md) | 进行中（**已实现，未提交**；等循环审计与合并） |
| [`v30-search-exclude`](v30-search-exclude/README.md) | 已完成（2026-10-30 合并；服务端已上线，CLI `v1.2.0` 同日发布）… |
| [`v31-list-addresses-only`](v31-list-addresses-only/README.md) | 已完成（2026-10-30 合并 `bbe7aa4`；CLI `v1.3.0` 同日发布）… |
| [`v32-online-editing`](v32-online-editing/README.md) | 进行中（第一期 `edit` 已实现并通过三轮三路审计；二期的 `write` 部分已实现——行号防线）… |
| [`v33-content-canonicalization`](v33-content-canonicalization/README.md) | 已实现，审计与测试已收口 |
| [`v34-file-toolbox`](v34-file-toolbox/README.md) | 进行中（已实现；本机测试见 `test-plan.md` §结果，服务端 grep 两个用例）… |
| [`v35-skill-list`](v35-skill-list/README.md) | 已实现——设计稿、控制台、服务端三处都已改 |
| [`v36-architecture-refactor`](v36-architecture-refactor/README.md) | 已实现（P0–P4 已落地；三端测试全绿；**待人看图**） |
| [`v37-cli-page-actions`](v37-cli-page-actions/README.md) | 已实现 |
| [`v38-install-delivers-skill`](v38-install-delivers-skill/README.md) | 进行中（**仓库侧已实现**；平台上的 `skillmaster/skillmaster-startup` 尚未创建）… |
| [`v39-media-files`](v39-media-files/README.md) | 进行中（P0–P2 已实现，测试未执行；P3 的整包流式化、CLI `download` 大包与回收清扫器未做） |
| [`v40-code-writing`](v40-code-writing/README.md) | 进行中（P0–P3 已实现，测试已跑；待评审。本版只改文档与注释，不改任何代码） |
| [`v41-console-error-details`](v41-console-error-details/README.md) | 进行中（P0–P3 已实现；控制台 638 条测试全绿；两轮审计收口；PR 待合并） |
| [`v42-skill-source-sync`](v42-skill-source-sync/README.md) | 进行中（P0 对齐、P1 派发结构、P1.5 触发契约已落地；`sop/` 于 2026-11-03 整目录删除）… |
| [`v43-download-domain`](v43-download-domain/README.md) | 进行中（设计、技术设计、实现与本地验证已完成；待视觉验收与 PR） |
| [`v44-cli-read-write-contract`](v44-cli-read-write-contract/README.md) | 已实现（服务端 + CLI + 生成物；单测与集成测试通过，端到端 T1/T2/T6/T7 待本地环境跑） |
| [`v45-notifications`](v45-notifications/README.md) | 进行中（P0 服务端 / P1 控制台 / P2 CLI 已实现，待视觉与端到端验收） |
| [`v46-account-identity`](v46-account-identity/README.md) | 进行中（已实现；自动化用例通过。T6 的浏览器半与授权页的视觉验收未执行，留产品负责人；待 PR 合并） |
| [`v47-welcome-and-consent`](v47-welcome-and-consent/README.md) | 进行中（已实现并已开 PR #130，测试见 `test-plan.md` §结果；待端到端验收） |
| [`v48-official-only-public`](v48-official-only-public/README.md) | 进行中（**已实现**；服务端 585 个用例、控制台 677 个用例全绿）… |
| [`v49-public-signals`](v49-public-signals/README.md) | 公域信号、使用度量（M15）与控制台 P3 已实现（2026-11-06） |
| [`v50-open-source-sync`](v50-open-source-sync/README.md) | 进行中（**已实现**，`./mvnw verify` 已通过，数量与证据见 `test-plan.md` §结果）… |
| [`v51-skill-runtime`](v51-skill-runtime/README.md) | 已实现并合并（PR #149） |
| [`v52-service-credentials`](v52-service-credentials/README.md) | 进行中 |
| [`v53-api-key-expiry`](v53-api-key-expiry/README.md) | 实现已完成 |
| [`v54-skill-copy`](v54-skill-copy/README.md) | 进行中（**已实现，循环审计两轮通过，人工视觉验收通过**；待合入） |
| [`v55-help-layer`](v55-help-layer/README.md) | 已实现并合并（PR #159） |
| [`v56-legal-and-account-deletion`](v56-legal-and-account-deletion/README.md) | 已完成（2026-11-10 合并 PR #161 并发布服务端、控制台、官网）… |
| [`v57-draft-loop`](v57-draft-loop/README.md) | 进行中（**已实现、已自测**；T1–T11 通过、T12 未执行，见 `test-plan.md` §结果；待合并） |
| [`v58-usage-history`](v58-usage-history/README.md) | 进行中，实现完成，待第 6 关循环审计（2026-11-10） |
| [`v59-personal-scenario`](v59-personal-scenario/README.md) | 进行中（P0/P1/P2 已实现并自测；P3 事件管道未在本 PR 实现，v58 已合并；待合并） |
