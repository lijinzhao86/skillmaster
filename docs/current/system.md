<!-- 生成物，勿手改。源：skillmaster-server/src/main/java/com/skillmasterai/common/ModuleMap.java；重新生成：python3 scripts/docs/generate.py -->

# 模块表（生成）

模块编号、包名、「拥有哪些表」与对外发布类型由 `ModuleMap.java` 定义，`ArchitectureTest` 与 `TableOwnershipTest` 强制。本文只是它的视图。

| 模块 | 职责 | 包后缀 | 拥有的表 | 对外发布类型 |
|---|---|---|---|---|
| M1 | account | `.account` | `app_user`、`credential`、`identity`、`phone_verification`、`legal_consent`、`auth_throttle`、`captcha`、`spring_session`、`spring_session_attributes` | `AccountDirectory`、`AuthThrottle`、`Throttle`、`ThrottledException` |
| M2 | token | `.token` | `oauth_client`、`oauth_authorization`、`auth_code`、`access_token`、`refresh_token`、`oauth2_authorization_consent`、`api_key`、`cli_device_sign_in` | — |
| M3 | auth | `.auth` | — | `AuthenticatedSubject`、`TokenValidation`、`TokenValidator` |
| M4 | namespace | `.namespace` | `namespace`、`namespace_member` | `Namespace` |
| M5 | ingest | `.ingest` | — | `ReservedPaths`、`Utf8Probe` |
| M6 | blob | `.blob` | `blob`、`blob_content`、`blob_deletion` | `BlobSettings`、`BlobStore` |
| M7 | version | `.version` | `skill_version`、`version_file` | `Manifest`、`ManifestEntry`、`SkillSnapshot`、`SkillVersionService`、`VersionPin`、`VersionState`、`VersionSummary`、`VersionProjectionSink` |
| M8 | search | `.search` | — | — |
| M9 | distribution | `.distribution` | — | — |
| M10 | audit | `.audit` | `audit_event` | `AuditCounts`、`AuditEvent`、`AuditLog` |
| M13 | retrieval | `.retrieval` | `skill_embedding`、`my_skill_when_to_use_embedding` | `EmbeddingTextSource` |
| M12 | skill | `.skill` | `skill`、`skill_grant`、`skill_access_request`、`skill_invitation`、`skill_library_join`、`skill_public_tag`、`my_tag_definition`、`my_tag_attachment`、`my_skill_when_to_use`、`skill_category`、`skill_source`、`skill_source_snapshot`、`skill_version_license`、`skill_copy` | `Access`、`Caller`、`DraftProjection`、`GrantRole`、`NotPermittedException`、`SkillCatalogService`、`SkillMetadata`、`SkillMetadataService`、`VersionProjection` |
| M14 | notification | `.notification` | `notification`、`notification_recipient` | `Notification`、`NotificationDraft`、`NotificationKinds`、`NotificationOutcome`、`NotificationPage`、`NotificationQuery`、`NotificationService`、`NotificationStateException`、`NotificationNotFoundException` |
| M15 | metrics | `.metrics` | `skill_usage_day`、`skill_usage_total` | `SkillLoad`、`UsageQuery`、`UsageRecorder`、`UsageScope`、`UsageSnapshot` |
| M16 | secret | `.secret` | `service_credential`、`credential_binding`、`credential_use`、`credential_share` | `SecretService`、`CredentialRef`、`CredentialBinding`、`CredentialLease`、`CredentialValue`、`IncomingBinding`、`SecretSettings`、`SecretNotFoundException`、`CredentialBindingNotFoundException`、`BindingConflictException`、`SecretValueTooLargeException`、`QuotaExceededException`、`NotShareableException`、`ShareNotFoundException`、`CredentialVaultUnavailableException` |
| M17 | activity | `.activity` | `activity_event`、`skill_collaborator_day` | `ActivityEvent`、`ActivityKinds`、`ActivityRecorder`、`ArchiveSettings`、`InvokeActivity`、`SearchActivity`、`SearchHit`、`UsageHistory` |
| M18 | feedback | `.feedback` | `user_feedback` | `Feedback`、`FeedbackConsent`、`FeedbackSafety`、`FeedbackService` |

包结构、依赖规则与它们的强制强度见 [`rules.md`](rules.md)；每个模块为什么这样切见 [`modules/`](modules/)。
