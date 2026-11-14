# 提交一份反馈

> 命令持续失败、文档与 `--help` 都解决不了时，用 `skmr feedback send --file <文件>` 把现场交给平台。用户贴了截图或视频，先用 `skmr feedback attach` 取到路径，再用 `--attach` 一起发。

## 三步

1. **拿模板。**

   ```bash
   skmr feedback template
   ```

   模板有六节：我想做什么 / 发生了什么 / 怎么复现 / 环境 / 已经试过什么 / 不要写。

2. **写文档，先给用户看。**

   把完整内容给用户看，得到同意再提交。**不要写** API 密钥、密码、私有代码、他人的个人信息、对话全文。密钥前缀（`sk-`、`AKIA`、`skm_`）会在发送前自动替换为「[已隐藏]」；手机号、身份证号、银行卡号会提示确认。

3. **预览，然后提交。**

   ```bash
   skmr feedback send --file issue.md
   ```

   不带 `--consent` 只打印完整内容、同意说明与将发送的字段，**不发送**。确认后：

   ```bash
   skmr feedback send --file issue.md --consent
   ```

   成功会给一个回执号（`FB-YYYYMMDD-XXXXXX`），并给用户一条站内通知。

## 图片或视频

用户把图片或视频贴进对话时，不要让它只留在对话里：**不要让用户手敲 CLI**，取图和提交都由你执行。

1. **消息里已有路径**（Claude Code 会写成 `[Image: source: …]`；拖进来的文件也会带出路径）：
   ```bash
   skmr feedback attach --copy <路径>
   ```
2. **没有路径**：先找当前会话的粘贴缓存，再读 macOS 剪贴板。
   ```bash
   skmr feedback attach --last-paste
   ```
3. **还取不到**：请用户把文件拖进对话（终端会带出路径），或另存为文件后给你路径。
4. **带附件提交**：
   ```bash
   skmr feedback send --file issue.md --attach <附件路径>
   ```
   预览会把附件清单列出来；把它和文档一起给用户看，再加 `--consent`。允许的格式与大小上限见 `skmr feedback send --help`。

`attach` 会把文件复制到本机的临时目录（仅本人可读）；发送成功后清空内容，文件本身留着。具体位置见 `skmr feedback attach --help`。图片或视频不会被自动扫描；画面里的密钥、个人信息与他人隐私要用户自己确认，图片或视频也可能带位置等元数据。图片可以用 `Read` 打开核对，视频不行——按文件名和预览让用户确认。

## 诊断字段

默认不附带任何诊断。需要时加 `--attach-diagnostics`，只带白名单字段：CLI 版本、操作系统、架构、时间；再用 `--command` 与 `--error-code` 补充命令名与错误码。

## 撤回

```bash
skmr feedback delete <回执> --yes
```

删除会把反馈与附件一起删掉，不能恢复。细节见 `skmr feedback delete --help`。账号注销后无法再运行这条命令，请发邮件到 privacy@skillmasterai.com 申请删除。不带 `--yes` 只打印将要删除的回执，不发请求。

## 报错怎么处理

每条提示对应的下一步见 `skmr feedback send --help`。Guide 只补一个容易漏的判断：

- **「内容里可能有敏感信息」**：如果这只是要报告的问题本身，加 `--allow-sensitive` 再发；否则删掉再发。
