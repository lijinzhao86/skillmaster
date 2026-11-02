# v41-console-error-details · 测试方案

## 验收映射

| 验收 | 用例 |
|---|---|
| A1 共享给不存在的用户名，显示具体句子 | T1 |
| A2 共享给自己，显示具体句子 | T2 |
| A3 失败后一次成功的共享，横幅消失 | T3、T8 |
| A4 读取失败后一次成功的读取，旧横幅消失 | T4 |
| A5 所有 `codeMessage`、编辑器 `showFailure`、`FailureReporter` 的调用都带 `details` | T5 |
| A6 既有测试全绿 | T6 |
| A7 界面结构与样式不变 | T7 |
| A8 标签或加入/移除失败后一次成功，旧横幅消失 | T10 |
| A9 一次无关的读取成功不擦掉写失败的横幅 | T11、T13 |
| A10 写请求期间改了用户名，不显示旧用户名的详情 | T12 |

T9 是 A5 的回归用例（`details` 过滤后仍保留非电话字段），不单独对应一条验收。

## 用例

### T1 · 共享给一个不存在的用户名

- **前置**：`SkillCollaborators` 挂在「设置 › 协作者」，名单与待处理申请各返回一次 200；`POST /web/skills/demo-user/pdf-tools/grants` 返回 400 `invalid_request`，`details: [{ field: 'handle', issue: 'no_such_user' }]`。
- **步骤**：在「共享给」输入 `nobody`，点「共享」，等请求结束。
- **期望**：`.banner` 的文本是「没有这个用户名，检查一下拼写。」；不是「提交的内容有问题，请检查后重试。」。

### T2 · 共享给自己

- **前置**：同 T1，`details: [{ field: 'handle', issue: 'already_yours' }]`。
- **步骤**：在「共享给」输入 `demo-user`（所有者），点「共享」，等请求结束。
- **期望**：`.banner` 的文本是「不用共享给自己——这个 skill 本来就在你名下。」。

### T3 · 失败之后一次成功，横幅消失

- **前置**：第一次 `POST /grants` 返回 400（同 T1）；第二次返回 201；成功后的名单读取返回 200。未过滤时 `refreshPopulation()` 直接返回，不发第二次读取。
- **步骤**：第一次共享失败后确认 `.banner` 是「没有这个用户名，检查一下拼写。」；改输入再点一次「共享」，等请求与重读结束。
- **期望**：第二次成功后 `.banner` 不存在。

### T4 · 读取失败之后一次成功的读取，旧横幅消失

- **前置**：协作者名单的第一次读取返回 500；之后在搜索框里输入一个词，200 ms 防抖后触发一次**只读**重读，返回 200。
- **步骤**：打开页面，确认名单读取失败留下 `.banner`；在「协作者名单」的搜索框输入 `yu`，等防抖与请求结束。
- **期望**：重读成功后 `.banner` 不存在。**这条不经过写操作**，所以它只能由「读取成功时清空」满足。

### T5 · `details` 必填与 `failureMessage`

- **前置**：无。
- **步骤**：`npm run type-check`；运行 `tests/errors.test.ts`。
- **期望**：类型检查通过；`failureMessage` 把 `details` 传给 `codeMessage`，`invalid_request` + `no_such_user` 命中「没有这个用户名，检查一下拼写。」；把 `details` 从 `codeMessage`、编辑器 `showFailure`、`FailureReporter` 的调用里删掉都会产生编译错误。**类型只能保证「有参数」，不能保证「它一定是 `result.details`」**；显式传 `[]` 只剩批量发布那一行与客户端合成的拒绝，已在 TD 记录。

### T8 · 下一次动作开始时清空旧横幅

- **前置**：第一次共享返回 400（同 T1）；第二次 `POST` 被测试挂起，尚未返回。
- **步骤**：第一次失败后确认 `.banner` 存在；改输入再点「共享」，在请求仍挂起时检查页面。
- **期望**：按钮处于提交中（`disabled`），`.banner` 已经不存在——旧失败在动作开始时就被拿掉，不等成功。

### T9 · 号码已改时保留非号码的详情（回归）

- **前置**：一条带图形验证码的发送；发送中把手机号改成另一个；服务端返回 400，`details` 同时含 `phone/already_registered` 与 `captcha_answer/invalid`。
- **步骤**：发起发送，改号码，放行响应。
- **期望**：`problems.phone` 不存在（旧号码的句子不能出现）；`problems.captcha` 仍是「图形验证码不正确或已过期，已为你换了一张。」；没有横幅。

### T10 · 加入/移除失败后一次成功，旧横幅消失

- **前置**：页面是可见但未加入的 skill（显示「加入」）；第一次 `POST /library` 返回 400 `skill_not_joinable`；第二次返回 200，随后 `GET /tags` 返回已加入的状态。
- **步骤**：点「加入」失败，确认页面横幅有原因；再点一次「加入」，等请求与重读结束。
- **期望**：第二次成功后 `.page > .banner` 不存在，按钮变成「移除」。

### T11 · 无关的读取成功不擦掉写失败的横幅

- **前置**：协作者页；一次共享返回 400 `no_such_user`，留下横幅；随后在搜索框输入一个词，200 ms 防抖后名单读取返回 200。
- **步骤**：共享失败后确认横幅；在搜索框输入 `yu`，等防抖与请求结束。
- **期望**：横幅仍是「没有这个用户名，检查一下拼写。」——读取成功不是对共享失败的答复。

### T13 · 写请求飞行期间落下的读失败，不能让之后的读成功擦掉写失败

- **前置**：协作者页；名单读取被测试挂起；共享 `POST` 也被挂起。
- **步骤**：点「共享」后，先放行名单读取（500），再放行共享（400 `no_such_user`）；然后在搜索框输入一个词，触发一次成功的只读重读。
- **期望**：横幅仍是「没有这个用户名，检查一下拼写。」——写失败落屏时把「来自读取」的标记清掉，后来的读成功不能把它当成自己的旧失败清走。

### T12 · 改了用户名后，不显示旧用户名的详情

- **前置**：协作者页；共享的 `POST` 被测试挂起，返回 400 `no_such_user`。
- **步骤**：在「共享给」输入 `nobody` 并点「共享」；请求仍挂起时把输入改成 `someone-else`；放行响应。
- **期望**：横幅是通用的「提交的内容有问题，请检查后重试。」，不是「没有这个用户名，检查一下拼写。」。

### T6 · 既有测试全绿

- **前置**：无。
- **步骤**：`cd skillmaster-console && npm test`。
- **期望**：全部通过；没有因为签名变化而需要跳过或用 `any` 绕过。

### T7 · 界面结构与样式不变

- **前置**：改动完成。
- **步骤**：看 `git diff -- skillmaster-console/src/styles.css` 与 `SkillCollaborators.tsx` 的 JSX 部分。
- **期望**：`styles.css` 无改动；JSX 结构无改动；只有 `codeMessage` 调用、横幅清空与测试变化。

## 环境与数据

- 只需要 Node（`skillmaster-console/package.json` 要求 `>=24`）与已安装的 `node_modules`。
- `cd skillmaster-console && npm test`、`npm run type-check`。
- 测试用 `tests/support.ts` 的 `stubFetch` / `json` / `errorBody`；不连服务端、不开浏览器。
- 按 [`dev-sop`](../../../.claude/skills/dev-sop/SKILL.md) §四：写代码期间**不跑** `qa/`；全量回归由用户指令触发。

## 结果

| 用例 | 结果 | 证据 |
|---|---|---|
| T1 | 通过 | `npm test -- tests/skill-collaborators.test.tsx`：新增用例「says which username is missing when a share is refused」 |
| T2 | 通过 | 同文件：「says that sharing with yourself is unnecessary」 |
| T3 | 通过 | 同文件：「takes a failed share off the banner when a later share succeeds」 |
| T4 | 通过 | 同文件：「clears a failed read when the search re-reads the list」——只读重读，不经过共享 |
| T5 | 通过 | `npm run type-check` 无错误；`tests/errors.test.ts` 新增 `failureMessage` 用例 |
| T6 | 通过 | `npm test`：36 个文件 / 638 条全绿 |
| T7 | 通过 | `git diff -- skillmaster-console/src/styles.css` 为空；`SkillCollaborators` 的 JSX 结构未改 |
| T8 | 通过 | 同文件：「clears the old banner as soon as the next share starts」——第二次 POST 挂起时横幅已空 |
| T9 | 通过 | `tests/code-request.test.tsx`：「keeps a captcha refusal when the phone changed while the send was in flight」 |
| T10 | 通过 | `tests/skill-collaborators.test.tsx`：「takes a failed join off the page banner when a later join succeeds」 |
| T11 | 通过 | 同文件：「keeps a failed share when an unrelated read succeeds」 |
| T12 | 通过 | 同文件：「does not put a handle detail under a name the person has since changed」 |
| T13 | 通过 | 同文件：「keeps a failed share when a read failed while it was in flight」。**验过它会失败**：去掉写失败分支的 `bannerFromRead.current = false` 后，该用例红；恢复后绿 |

**T6 首轮跑出一个回归，已修**：`register-flow.test.tsx` 的「files a refused send against the number that was sent」在迁移后失败——`failureMessage` 把「手机号已注册」的详情带进了旧号码的横幅，而那条答案属于一个屏幕上已经不存在的号码。`useCodeRequest` 现在先按「号码是否已改」过滤掉 `field: phone` 的详情，再取句子；T9 钉住「其他字段的详情仍然保留」。重跑该文件 50 条通过，随后全量 638 条通过。

## 已知问题

无。本版范围外的历史缺陷见 [`known-issues.md`](known-issues.md)（密钥页剪贴板用例偶发失败，与本版无关）。
