# 0140 · CLI 也能批准与拒绝权限申请

- **状态**：已采纳
- **日期**：2026-11-04
- **所属版本**：[`v45-notifications`](../versions/v45-notifications/README.md)
- **依赖**：[0052](0052-a-request-is-not-a-grant.md)（申请不是授权）、[0073](0073-asking-for-access-splits-by-plane.md)（申请按平面分）、[0081](0081-every-console-operation-has-a-cli-command.md)（每处控制台操作有 CLI 命令）、[0056](0056-dangerous-actions-are-confirmed-in-a-modal.md)（危险动作要确认）、[0139](0139-a-notification-points-at-a-business-object.md)（通知是业务对象的指针）
- **取代**：[0073](0073-asking-for-access-splits-by-plane.md) §决定 2——「批准 / 拒绝仍然只有网页，API 平面没有这三条路由」。**0073 的正文保持原样**，改动记在这里；§决定 1 的「待批列表在网页」不变。

## 背景

[ADR 0073](0073-asking-for-access-splits-by-plane.md) 把五条动作按平面切开：申请 / 撤回 / 读自己的在 API 平面，批准 / 拒绝与待批列表只在网页。它的理由是「批准改变的是申请人下次读到的档位——凭据自己能批，等于『申请 → 批准』一个人跑完，[ADR 0052](0052-a-request-is-not-a-grant.md) 要的那道人为确认就没了」。

v45 让 A 在 CLI 里收到「lin 申请 dev/pdf-tools 的写入档位」（[ADR 0139](0139-a-notification-points-at-a-business-object.md)）。如果下一步只能是一条 `skmr skill open …` 把人送到浏览器，通知的链路就在这里断一次：**通知面在终端，动作面在网页**。2026-11-04 与所有者对齐时，这条被点名要求补齐。

摆上桌的选项：

| | 做法 | 为什么被否 |
|---|---|---|
| a | 保持 0073：批准 / 拒绝只在网页，CLI 只给打开网页的命令 | 链路断一步；A 在终端看到申请，却必须切到浏览器才能答 |
| b | 只有拒绝进 CLI，批准留网页 | 拒绝不授予任何东西，确实更轻；但同一个决定被拆到两个平面，A 要记「哪一种去网页」，而 0073 的理由针对的是「批准」，拆一半并不解决它 |
| c | **批准与拒绝都上 API 平面；CLI 要求 `--yes`；网页保留自己的确认界面** | 采纳 |

## 决定

1. **API 平面加两条路由**：`POST /api/v1/notifications/{id}/approve`（body 可带 `role`，默认申请的那一档）与 `POST /api/v1/notifications/{id}/deny`。它们按通知 id 找到那条申请；调用者必须仍是那个 skill 的 `admin` 及以上。
2. **CLI**：`skmr notifications approve <id> [--role <档>] --yes` 与 `skmr notifications deny <id> --yes`。**`--yes` 必填**；不带时只打印后果与带 `--yes` 的完整命令，不执行——与 `skmr skill delete` 的确认同一个形状（[ADR 0056](0056-dangerous-actions-are-confirmed-in-a-modal.md) 的 CLI 对应物）。
3. **网页保留原有的决定界面**：协作者页的「批准 / 拒绝」照旧（可改档位、看申请人当前档位）。两边调用同一个 `DecideSkillAccessRequestUseCase`，不是两套规则。
4. **待批列表仍只在网页**（0073 §决定 1 不变）。CLI 不需要列「所有人的申请」：它只处理**自己收到的那条通知**；通知的存在就是 A 被点名的那份队列。
5. **scope 不变**：`POST` 要 `skills:write`；只读密钥不能批准 / 拒绝，与不能标已读、不能接受邀请同一条规则。
6. **审计沿用现有的 approve / deny 记录**：申请人、申请的那一档、最终授予的那一档；CLI 的调用者是凭据所属的人。

## 理由

**为什么 0073 的「一个人跑完」在这里不成立。** 申请人不能批准自己的申请：批准要求 `admin`，而一个人若已经是 `admin`，就申请不了更高一档（`SkillAccessService.request` 会拒绝）。所以「申请 → 批准」由同一个人跑完，需要他既是申请人又是 `admin`，而那种请求根本不存在。A 的密钥批准 B 的申请，是 A 在答，不是 B 自批。

**为什么 `--yes` 够。** 这道确认与删除 skill、改可见性是同一种：高风险、改变谁能读写，CLI 用显式开关，网页用弹窗。`--yes` 不是给 agent 的安全边界（agent 可以带），它是给**人**的：让人在按下之前先读到「会授予谁什么」。真正的边界是审计与 A 对自己密钥的保管；这一点与「admin 凭据本来就能发邀请」是同一个事实。

**为什么不在通知行里也放批准 / 拒绝（控制台）。** 网页的决定界面比一行通知多两件事：看申请人**现在的档位**、在批准前**改档位**。把决定压进一行会丢掉这两件；CLI 至少用 `--role` 保留了改档位这条路，而控制台的通知行交给「去处理」是更完整的入口。两边的入口不同，不是规则不同。

## 后果

- **0073 §决定 2 被取代**；§决定 1（待批列表在网页）和 §决定 3（只读密钥）不变。
- **API 平面多两条路由**；M12 需要一个按 `skill_id` 而不是地址做决定的 seam（通知只带 id，不带地址）。网页原有的地址路由继续用同一条用例。
- **A 的 CLI 通知下一行**从 `skmr skill open …` 变成 `skmr notifications approve <id> --role <档> --yes` / `deny <id> --yes`。
- **一个已知风险**：agent 可以带 `--yes` 自动批准。它拿不到新能力（同一把密钥本来就能发邀请；v45 之前能直接授权），但「自动批准别人的申请」比「自动邀请」更值得看审计。这条风险写进 PRD 的取舍，不假装它不存在。
- **拒绝仍然不授予任何东西**；批准按最终档位写授权，与网页同一条路径。
