// Package feedback holds the two things the feedback command knows locally: the document
// template and the content scan. Neither talks to the server.
package feedback

// Template is what `skmr feedback template` prints. It is also what the platform's own skill
// tells an agent to fill in, so the two must stay one text — a template in the skill that drifted
// from this one would produce documents the CLI does not ask for.
const Template = `反馈文档模板

把下面六节写清楚，再运行：
  skmr feedback send --file <文件> --consent

## 我想做什么
一句任务目标，不写实现细节。

## 发生了什么
原始错误、命令、退出码；按发生顺序。

## 怎么复现
最小步骤；能稳定复现就写，不能就说不能。

## 环境
CLI 版本、操作系统、客户端（Claude Code / Codex / 其它）、是否在沙箱里。

## 已经试过什么
查过的文档、改过的参数、结果。

## 不要写
API 密钥、密码、私有代码、个人信息、对话全文。`
