# 0003 · 第一次接触：先说一句、再说要装什么，对用户称 Agent

- **日期**：2026-10-28
- **关联**：[ADR 0107](../../../decisions/0107-startup-speaks-to-non-technical-users.md)、[ADR 0108](../../../decisions/0108-importing-local-skills-is-optional.md)、[`design/voice-spec.md`](../../../../design/voice-spec.md) §八

## 改了什么

- **`design/voice-spec.md` 新增 §八「首次接触：安装对话」**：两句开场（问候 + 它是干什么的 / 现在要做什么 + 当下做什么）、三条规则（先说再查、不解释原理、欢迎词不分岔）、开场问候允许一个感叹号（§五 的唯一例外）。
- **`skillmaster-site/public/install/skillmaster.md`**：`## 打招呼` 一节按上面两句定稿；「别让人干等」写成一条规则（每次检查、下载、写入之前先说在做，做完说结果）。
- **本版 PRD 名词表**：「agent / 客户端 / 模型」对用户的说，从「你用的 AI」改成「Agent」；直接叫名字仍然可以。
- **CLI 的清单项名跟着改**：`startup_plan.go` 里「让 AI 先来 SkillMaster 找技能」「让 AI 用 SkillMaster 自己的文件夹」改成「让 Agent …」，测试同步；本版 PRD / TD / test-plan / README 与安装页里的同名项一并改。**`docs/decisions/` 下的 ADR 0106 / 0107 / 0108 不改**——ADR 正文是当时的历史记录。
- **`docs/research/onboarding-interaction.md` 新增 §八**：Apple HIG、Homebrew / uv 的检测做法、GOV.UK 短句口径、NN/g 错误消息研究，以及本项目两轮 subagent 实测。

## 为什么

2026-10-28 按安装说明页真机重走旅程时发现三件事：

1. **第一句只说了产品是什么，没说「我现在要做什么」**。原来的「我先看看你电脑上现在是什么情况」让用户不知道接下来会被装东西；产品负责人要求改成「现在帮你在这个 Agent 上装 SkillMaster」。
2. **先查再说话，用户要对着空白等**。原稿把欢迎词放在 `skillmaster --version` 之后，且按「首次 / 再来」分两版；两轮 subagent 实测里变体都选对了，但第一句总在命令跑完之后。改成「先说一句 → 再查」，并加了「别让人干等」。
3. **称谓与用词各有冲突**：「您」vs voice-spec 的「一律称你」；「skill」vs 名词表的「技能」；「沉淀个人知识库」是一个产品兑现不了的承诺（我们存的是技能，不是资料）。产品负责人定：称「你」、「技能」，承诺用「托管到云端」原句，Agent 这个词反过来改名词表。

## 影响

- **控制台界面不受影响**：§八 只管安装对话与 `startup` 的第一句，控制台仍按 voice-spec 其他各节。
- **平台技能 `skillmaster/startup@1.3.2` 里仍是「你用的 AI」**：`voice.md` 与几个步骤文件都要按新词改，随下一次技能发布一并上线；在那之前，`startup` 对话里的称呼与安装页不一致。
  **TODO（2026-10-28 产品负责人定：待会儿一起改）**：改 `voice.md` 第 18 行与步骤文件里的称呼，发 `1.3.3`。
- **`deploy/README.md` 的 TLS 与备案一节与安装页的 OSS 直连地址已撤回**（PR #84 → #85），与本次无关。
