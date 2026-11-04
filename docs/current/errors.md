<!-- 生成物，勿手改。源：skillmaster-server/src/main/java/com/skillmasterai/common/ErrorCode.java；重新生成：python3 scripts/docs/generate.py -->

# 错误码（生成）

线上的稳定标识是**引号里那串**；Java 常量名不是契约，改名不会改线上值。HTTP 状态码由各接口的异常处理器决定（`ApiExceptionHandler`、`UploadExceptionAdvice`），本文只列线上名字。

| 常量 | 线上值 |
|---|---|
| `UNAUTHENTICATED` | `unauthenticated` |
| `TOKEN_REVOKED` | `token_revoked` |
| `TOKEN_EXPIRED` | `token_expired` |
| `TOKEN_IDLE` | `token_idle` |
| `ACCOUNT_SUSPENDED` | `account_suspended` |
| `INVALID_CREDENTIALS` | `invalid_credentials` |
| `INSUFFICIENT_SCOPE` | `insufficient_scope` |
| `FORBIDDEN` | `forbidden` |
| `TOO_MANY_REQUESTS` | `too_many_requests` |
| `INVALID_REQUEST` | `invalid_request` |
| `INVALID_UPLOAD` | `invalid_upload` |
| `VERSION_ALREADY_EXISTS` | `version_already_exists` |
| `VERIFICATION_CODE_INVALID` | `verification_code_invalid` |
| `SKILL_NOT_FOUND` | `skill_not_found` |
| `TAG_DEFINITION_NOT_FOUND` | `tag_definition_not_found` |
| `KEY_NOT_FOUND` | `key_not_found` |
| `SESSION_NOT_FOUND` | `session_not_found` |
| `CODE_NOT_FOUND` | `code_not_found` |
| `INVALID_SCOPE` | `invalid_scope` |
| `INVALID_EXPIRY` | `invalid_expiry` |
| `FILE_NOT_FOUND` | `file_not_found` |
| `DRAFT_NAME_REQUIRED` | `draft_name_required` |
| `DRAFT_NAME_TOO_LONG` | `draft_name_too_long` |
| `DRAFT_NAME_IS_FROZEN` | `draft_name_is_frozen` |
| `NOTE_TOO_LONG` | `note_too_long` |
| `NOTE_IS_FROZEN` | `note_is_frozen` |
| `INVALID_VERSION_STATE` | `invalid_version_state` |
| `VERSION_REVOKED` | `version_revoked` |
| `INVALID_RELEASE_NAME` | `invalid_release_name` |
| `RELEASE_TYPE_IS_FROZEN` | `release_type_is_frozen` |
| `RELEASE_NAME_REQUIRED` | `release_name_required` |
| `VERSION_NAME_IS_ASSIGNED` | `version_name_is_assigned` |
| `RELEASE_TYPE_NEEDS_EXPLICIT_NAME` | `release_type_needs_explicit_name` |
| `DUPLICATE_TARGET` | `duplicate_target` |
| `SKILL_OFFLINE` | `skill_offline` |
| `IF_MATCH_REQUIRED` | `if_match_required` |
| `DIGEST_MISMATCH` | `digest_mismatch` |
| `CONTENT_ALREADY_EXISTS` | `content_already_exists` |
| `FILE_ALREADY_EXISTS` | `file_already_exists` |
| `SKILL_MD_CANNOT_BE_DELETED` | `skill_md_cannot_be_deleted` |
| `LAST_FILE_CANNOT_BE_DELETED` | `last_file_cannot_be_deleted` |
| `VERSION_IS_NOT_DRAFT` | `version_is_not_draft` |
| `GREP_REQUIRES_DRAFT` | `grep_requires_draft` |
| `INVALID_PATH` | `invalid_path` |
| `SKILL_DELETED` | `skill_deleted` |
| `SKILL_ALREADY_EXISTS` | `skill_already_exists` |
| `SKILL_PUBLISHED` | `skill_published` |
| `RESERVED_PATH` | `reserved_path` |
| `SKILL_NOT_JOINABLE` | `skill_not_joinable` |
| `LIBRARY_REFERENCE_MISSING` | `library_reference_missing` |
| `LIBRARY_ENTRY_DORMANT` | `library_entry_dormant` |
| `TAG_NOT_ATTACHED` | `tag_not_attached` |
| `SKILL_NOT_DELETED` | `skill_not_deleted` |
| `SEMANTIC_UNAVAILABLE` | `semantic_unavailable` |
| `NOTIFICATION_NOT_FOUND` | `notification_not_found` |
| `INVALID_NOTIFICATION_STATE` | `invalid_notification_state` |
| `INTERNAL_ERROR` | `internal_error` |
