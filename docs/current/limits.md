<!-- 生成物，勿手改。源：skillmaster-server/src/main/java/com/skillmasterai/modules/ingest/IngestLimits.java；skillmaster-server/src/main/java/com/skillmasterai/modules/blob/BlobSettings.java；skillmaster-server/src/main/java/com/skillmasterai/common/SkillFieldLimits.java；skillmaster-server/src/main/java/com/skillmasterai/modules/feedback/FeedbackSafety.java；skillmaster-server/src/main/java/com/skillmasterai/modules/feedback/FeedbackAttachmentSafety.java；skillmaster-server/src/main/java/com/skillmasterai/usecase/SubmitFeedbackUseCase.java；重新生成：python3 scripts/docs/generate.py -->

# 上限（生成）

**服务端**以这些常量为准。CLI 与网页各自保留了一份同值或接近的常量（不由这里读取，控制台还有一句面向用户的文案）；改这里的值时，要一起检查客户端，否则它会先于服务端拦下或放过。其余常量与下面的分流阈值只有服务端有。

| 常量 | 原始表达式 | 字节 | 说人话 |
|---|---|---|---|
| `IngestLimits.STANDARD_MAX_FILES` | `512` | 512 | 512 |
| `IngestLimits.STANDARD_MAX_FILE_BYTES` | `100L * 1024 * 1024` | 104857600 | 100 MiB |
| `IngestLimits.STANDARD_MAX_BYTES` | `512L * 1024 * 1024` | 536870912 | 512 MiB |
| `IngestLimits.STANDARD_TEXT_MAX_BYTES` | `16L * 1024 * 1024` | 16777216 | 16 MiB |
| `IngestLimits.PACKAGE_IN_MEMORY_MAX_BYTES` | `128L * 1024 * 1024` | 134217728 | 128 MiB |

## 对象存储的分流阈值

`BlobSettings.defaults()` 决定「大于多少字节进对象存储」与「单个 blob 的上限」。部署可以用配置覆盖它，默认值如下：

| 项 | 默认 |
|---|---|
| 进对象存储的阈值 | 1048576 字节（1 MiB） |
| 单个 blob 上限 | 104857600 字节（100 MiB） |

## 技能文本字段的上限

单位是 **Unicode 码点**：一个汉字、一个英文字母、一个标点都算 1，emoji 也按 1 个码点计（ADR 0181）。作者字段由 `SkillTextFields.inspect` 校验：写入时经 `SkillDocument.parse`，发布时经 `SkillMetadataService` 各调用一次；「我的适用场景」由它自己的写接口校验。

| 常量 | 原始表达式 | 码点 |
|---|---|---|
| `SkillFieldLimits.DESCRIPTION_MIN_CODE_POINTS` | `1` | 1 |
| `SkillFieldLimits.DESCRIPTION_MAX_CODE_POINTS` | `1024` | 1024 |
| `SkillFieldLimits.AUTHOR_WHEN_TO_USE_MAX_CODE_POINTS` | `200` | 200 |
| `SkillFieldLimits.MY_WHEN_TO_USE_MAX_CODE_POINTS` | `200` | 200 |

## 反馈的上限

`skmr feedback send` 的文档上限（码点，ADR 0181）与每个账号每小时可提交的条数。CLI 保留一份同值常量用于发送前失败，服务端仍是闸。

| 常量 | 原始表达式 | 值 |
|---|---|---|
| `FeedbackSafety.MAX_CODE_POINTS` | `20_000` | 20000 码点 |
| `SubmitFeedbackUseCase.MAX_PER_HOUR` | `10` | 10 条/小时 |

## 反馈附件的上限

`skmr feedback send --attach` 的四个上限。CLI 保留同值常量用于发送前失败，服务端仍是闸；数值改动是改对外契约，按大版本走（ADR 0191）。

| 常量 | 原始表达式 | 值 |
|---|---|---|
| `FeedbackAttachmentSafety.MAX_ATTACHMENTS` | `5` | 5 个/条 |
| `FeedbackAttachmentSafety.MAX_IMAGE_BYTES` | `10L * 1024 * 1024` | 10485760 字节（10 MiB） |
| `FeedbackAttachmentSafety.MAX_VIDEO_BYTES` | `50L * 1024 * 1024` | 52428800 字节（50 MiB） |
| `FeedbackAttachmentSafety.MAX_TOTAL_BYTES` | `100L * 1024 * 1024` | 104857600 字节（100 MiB） |
