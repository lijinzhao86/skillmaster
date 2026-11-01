# M07 · 版本与内容

> **状态**：已实现 · **拥有表**：`skill_version`、`version_file` · **对外的发布类型**：`Manifest`、`ManifestEntry`、`SkillSnapshot`、`SkillVersionService`、`VersionPin`、`VersionProjectionSink`、`VersionState`、`VersionSummary`

## 职责

版本的身份（短码 / 版本名 / 摘要）、不可变的已发布内容、草稿的可变内容、提交 / 上线 / 下架 / 恢复的状态机、版本钉的解析、作者面与消费面的快照，以及引用计数 GC。

## 边界

- **不做**：读 `skill` / `skill_grant` 判权限。调用方先向 M12 要一个 `Access`，M07 只评估它携带的档位。
- **不做**：写 `skill` 行。上线时 M07 算出投影，M12 负责写指针与列表投影。
- **字节经 M06**：M07 只持有摘要与大小，不碰 `blob_content`。
- **投影写入经端口**：上线 / 提交 / 丢弃时的投影写入走 `VersionProjectionSink`（M07 声明），实现是 `config/SkillVersionProjectionAdapter`（调用 M12）；`SkillVersionService` 不再直接依赖 `SkillMetadataService`。M07 仍 import M12 的 `Access`、`GrantRole`（共享内核）与 `SkillMetadata`、`DraftProjection`、`VersionProjection`、`NotPermittedException`（M12 的 `publishedTypes`）；类型层面的耦合已在 `ModuleMap.PUBLISHED_TYPES` 里显式化。

## 不变量

1. 已发布版本的内容冻结：只有 `draft` 行可写。
2. 上线 / 下架 / 恢复是单向状态机；当前版本不能下架。
3. 引用计数 GC 必须与版本变更同事务（M06 只执行删除）。
4. `@Transactional` 不在本模块；跨模块事务由用例层划定。

## 为什么这样切

版本是「内容 + 状态」，skill 是「地址 + 权限 + 列表」。v5 起把 `skill` / `skill_grant` 划给 M12，让「谁能看见」与「这个版本内容是什么」各自只有一个权威；代价是上线变成一次跨模块的投影写入，用例层必须把两处放进同一个事务。
