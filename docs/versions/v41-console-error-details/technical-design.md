# v41-console-error-details · 技术设计

## 架构

控制台的错误措辞只有一个入口：`skillmaster-console/src/api/errors.ts` 的 `codeMessage`。本版交付后它接收 `code`、`fallback` 与必填的 `details`，按错误码查不同的表：`invalid_path` 查 `issueSentence`，`invalid_upload` 查 `sizeSentence`，`invalid_request` 查 `FIELD_SPECIFIC` / `ISSUE_MESSAGES`。

问题不在文案表，而在调用点：`details` **原先**在 `errors.ts` 里有默认值 `[]`，漏传是合法的。服务端已经发送 `details[]`（`api/types.ts:38` 的 `ErrorDetail`），客户端却在多数调用点把它丢掉，于是 `invalid_request` 只剩 `CODE_MESSAGES.invalid_request` 的兜底句「提交的内容有问题，请检查后重试。」。

本版把「漏传」变成类型错误，并给「整个失败结果」一个入口：

1. `codeMessage(code, fallback, details)`——`details` 改为必填，不再有默认值。
2. `failureMessage(result)`——接收 `{ code, message, details }`，转调 `codeMessage`。
3. 所有 `codeMessage(result.code, result.message)` 改成 `failureMessage(result)`；拿不到 `details` 的调用显式传 `[]`。

横幅生命周期是组件 / 钩子自己的状态，没有全局容器。**共享同一横幅的组件**（`SkillCollaborators`、`useSkillTags`）里：用户发起的动作开始时清空，读取成功只清自己上一次读失败留下的横幅；**各自独占横幅的页面**在成功读取时清空，并把写失败安排在重读之后设置。成功路径不保留旧横幅。

## 数据模型

无。没有表、迁移或持久化状态变化。

## 接口

### 控制台内部接口

| 符号 | 位置 | 变化 |
|---|---|---|
| `codeMessage(code, fallback, details)` | `api/errors.ts` | `details` 从可选变必填 |
| `failureMessage(result)` | `api/errors.ts` | 新增 |

```ts
export function failureMessage(result: {
  code: string
  message: string
  details: ErrorDetail[]
}): string {
  return codeMessage(result.code, result.message, result.details)
}
```

结构类型而不是 `ApiResult`：调用点手里的失败结果有 `ApiResult` 的失败分支，也有 `importFailure` 这种自带 `code / message / details` 的中间形状；它们要的是同一句话。

### 调用点清单

**已经传 `details` 的**（只需改成 `failureMessage`，行为不变）：

| 文件 | 位置 |
|---|---|
| `pages/PublishPage.tsx` | 298、395 |
| `pages/MyTagsPage.tsx` | 291、337、394、441 |
| `pages/DiscoverPage.tsx` | 297、315 |

`hooks/useSkillTags.ts` 原本就传 `details`（现为 61、129）；本版只加清空，见 §横幅生命周期。`pages/version-editor/useEditorSave.ts` 的 `showFailure` 也传 `details`（261），本版只把它的参数改为必填（见下），不改成 `failureMessage`。

**必须补 `details` 的**：

| 文件 | 位置 | 备注 |
|---|---|---|
| `AppShell.tsx` | 162 | 结束会话失败 |
| `store.ts` | 85 | 会话读取失败 |
| `components/SkillCollaborators.tsx` | 192、440、452、464、476、487 | 名单读取、共享、改档位、撤销、批准、拒绝 |
| `components/key/KeyCreateForm.tsx` | 218 | 新建密钥的横幅兜底 |
| `hooks/useSkillFiles.ts` | 158 | 文件读取 |
| `components/version/VersionDetail.tsx` | 172、203 | 改名、说明 |
| `hooks/useSkillDetail.ts` | 59 | skill 详情读取 |
| `hooks/useCaptcha.ts` | 42 | 图形验证码 |
| `hooks/useCodeRequest.ts` | 201 | 短信验证码；号码在请求期间被改过时，先过滤掉 `field: phone` 的详情再取句子 |
| `hooks/useVersionDiff.ts` | 152 | 单文件 diff |
| `hooks/useSkillAsk.ts` | 55、65 | 申请 / 撤回访问 |
| `hooks/useSkillSettings.ts` | 58、83、109、137 | 公开浏览、可见性、下线、删除 |
| `hooks/useVersionActions.ts` | 38 | 上线 / 下架 / 恢复 / 丢弃 |
| `api/importFailures.ts` | 149、209 | 导入 / 新建的兜底 |
| `pages/LoginPage.tsx` | 59 | 登录 |
| `pages/CliSignInPage.tsx` | 133 | CLI 授权 |
| `pages/AccountSessionsPage.tsx` | 264、282 | 结束单台设备 / 结束其它设备 |
| `pages/PublishPage.tsx` | 358 | 批量发布的一行；这一行只有 `code / message`，显式传 `[]` |
| `pages/RegisterPage.tsx` | 259 | 注册 |
| `pages/SkillsPage.tsx` | 343 | 恢复已删除的 skill |
| `pages/AccountKeysPage.tsx` | 187、324、348 | 列表读取、撤销、续期 |
| `pages/ResetPage.tsx` | 89 | 找回密码 |
| `pages/version-editor/useEditorLabels.ts` | 90、110 | 版本名称 / 说明的保存状态 |

**两个先过滤再取句子的调用点。** 短信发送（`useCodeRequest`）在飞行期间号码可以被改（输入框是活的），而服务端拒绝里的 `phone` 详情属于**发出去的那个号码**；如果直接把它交给 `codeMessage`，横幅会针对一个屏幕上已经不在的号码说话。它先滤掉 `field: phone`，再取句子并映射字段；`captcha` 等其他字段的详情保留（T9 钉住这一点）。`SkillCollaborators` 的共享输入同理：请求飞行期间用户名可改，`field: handle` 的详情先按「提交时的名字是否仍是当前名字」过滤（A10）。

**编辑器内部的 `showFailure` 与 `FailureReporter` 也把 `details` 设为必填。** `useEditorSave.ts` 的 `showFailure` 原先有 `details = []` 的默认值，三条读取失败路径（`refreshDraftInPlace`、`refreshStaleBuffers`、`ensureText`）因此仍会丢详情；`FailureReporter` 的类型也补上 `details`。客户端合成的拒绝（读不到版本、上传前拒绝）显式传 `[]`。

### 横幅生命周期

| 文件 | 状态 | 本版动作 |
|---|---|---|
| `components/SkillCollaborators.tsx` | `banner` | `share` / `changeRole` / `withdraw` / `approve` / `deny` 开始时清空；`loadGrants` 成功时只清**自己上一次读失败**留下的横幅（`bannerFromRead` + `bannerReadGeneration`，旧读的答案不能清新读的失败）；写失败落屏时把标记清掉 |
| `hooks/useSkillTags.ts` | 页面横幅（经 `onError`） | `write` 开始时清空；`read` 只清自己上一次的读失败（`readFailure` + generation）；写失败落屏时把标记清掉 |
| `hooks/useSkillDetail.ts` | `banner` | `load` 成功时清空 |
| `pages/SkillsPage.tsx` | `banner` | `restore` 开始时清空（成功路径不再写横幅） |
| `components/version/VersionDetail.tsx` | `localBanner` | `rename` / `saveNote` 开始时清空 |
| `hooks/useSkillAsk.ts` | `banner` | `submitAsk` / `withdrawAsk` 开始时清空 |
| `hooks/useSkillSettings.ts` | `banner` | `changeVisibility` 开始时清空 |

**已经清在正确位置的，不改**：`KeyCreateForm`（提交与离开表单时 `onBanner(null)`）、`useSkillFiles`（`loadFile` 开始时）、`useVersionActions`（动作开始时）、`AccountKeysPage` / `useSkillDetail`（成功读取时清空，页面独占横幅）、`AccountSessionsPage`（成功读取时清空；`confirmRevoke` / `confirmRevokeOthers` 在重读之后才设置写失败，重读不会擦掉它）、`store`（成功时 `problem: null`）、`AppShell`（成功即跳转）、`CliSignInPage` / `LoginPage` / `RegisterPage` / `ResetPage`（动作开始时）、`useCodeRequest`（动作开始时）、`useCaptcha`（刷新成功后清、失败时覆盖；它是字段消息，不是横幅）、`useVersionDiff`（重试前把该文件清空）、`useEditorLabels`（成功时覆盖为「已保存」）、`MyTagPicker`（动作开始时）。

## 分期

| 期 | 交付 | 验收 |
|---|---|---|
| P0 | `errors.ts`：`details` 必填、`failureMessage` 新增；`errors.test.ts` 补用例 | 单元测试；`npm run type-check` 会暴露所有漏传 |
| P1 | 按 §接口 的清单迁移调用点 | `npm run type-check` 通过；既有测试全绿 |
| P2 | 按 §横幅生命周期 补齐清空 | A3、A4 的组件测试 |
| P3 | 按 test-plan 的 T1–T13 补齐组件测试；跑全量控制台测试；回写 docs 的结果一节 | A1–A10 全过 |

## 开放问题

| # | 问题 | 当前倾向 |
|---|---|---|
| Q1 | 「共享给」的用户名错误放在横幅，还是输入框下？ | **放横幅。** 设计稿的「共享给」只有输入、档位、按钮与一句提示，没有字段错误态；新增位置要先改设计稿。设计补了这一态之后再搬。 |
| Q2 | `failureMessage` 收 `ApiResult` 还是结构类型？ | **结构类型。** `importFailure` 与批量发布的一行各有自己的形状，收窄到 `{code, message, details}` 才能共用。 |
| Q3 | 未知 issue 码怎么兜底？ | **分层维持现状。** 横幅层（`codeMessage('invalid_request', …)` 遇到不认识的 issue）落到 `CODE_MESSAGES[code]`（「提交的内容有问题，请检查后重试。」）；字段层（`fieldErrors`）与导入兜底（`importFailures`）回退服务端原句。本版不扩大也不缩小。 |
| Q4 | 横幅在动作开始时还是成功时清空？ | **两者都要，但读取只清自己的失败。** 开始清空避免重试期间还挂着上一次的失败；读取成功只清自己上一次读失败留下的横幅，不清写失败（否则搜索框的一次成功读取会擦掉刚失败的共享）。 |
