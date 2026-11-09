<!-- 生成物，勿手改。源：skillmaster-server/src/main/resources/db/migration/V*.sql；重新生成：python3 scripts/docs/generate.py -->

# 表结构（生成）

表与列来自迁移脚本的 `CREATE TABLE`、`ADD COLUMN`、`DROP COLUMN`、`RENAME COLUMN`、`RENAME TO`、`DROP TABLE`，按版本号顺序应用。**索引、约束、触发器不在本文**——它们在迁移脚本里，这里只回答「有哪些表、各有哪些列」。

| 表 | 模块 |
|---|---|
| `app_user` | M1 |
| `credential` | M1 |
| `identity` | M1 |
| `oauth_client` | M2 |
| `auth_code` | M2 |
| `access_token` | M2 |
| `refresh_token` | M2 |
| `namespace` | M4 |
| `namespace_member` | M4 |
| `blob` | M6 |
| `blob_content` | M6 |
| `skill` | M12 |
| `skill_version` | M7 |
| `version_file` | M7 |
| `audit_event` | M10 |
| `phone_verification` | M1 |
| `auth_throttle` | M1 |
| `spring_session` | M1 |
| `spring_session_attributes` | M1 |
| `captcha` | M1 |
| `oauth2_authorization_consent` | M2 |
| `oauth_authorization` | M2 |
| `skill_grant` | M12 |
| `api_key` | M2 |
| `cli_sign_in_code` | M2 |
| `skill_access_request` | M12 |
| `skill_library_join` | M12 |
| `skill_public_tag` | M12 |
| `my_tag_definition` | M12 |
| `my_tag_attachment` | M12 |
| `skill_category` | M12 |
| `skill_embedding` | M13 |
| `blob_deletion` | M6 |
| `notification` | M14 |
| `notification_recipient` | M14 |
| `skill_invitation` | M12 |
| `skill_source` | M12 |
| `skill_source_snapshot` | M12 |
| `skill_version_license` | M12 |
| `skill_usage_day` | M15 |
| `skill_usage_total` | M15 |
| `service_credential` | M16 |
| `credential_binding` | M16 |
| `credential_use` | M16 |
| `credential_share` | M16 |
| `legal_consent` | M1 |

## `app_user`

- `id` — TEXT PRIMARY KEY
- `handle` — TEXT NOT NULL UNIQUE
- `display_name` — TEXT NOT NULL DEFAULT ''
- `email` — TEXT UNIQUE
- `status` — TEXT NOT NULL DEFAULT 'active'
- `created_at` — TEXT NOT NULL
- `phone_hash` — TEXT UNIQUE
- `phone_enc` — TEXT
- `deleted_at` — TEXT
- `purged_at` — TEXT

## `credential`

- `user_id` — TEXT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE
- `type` — TEXT NOT NULL
- `secret_hash` — TEXT NOT NULL
- `updated_at` — TEXT NOT NULL

## `identity`

- `provider` — TEXT NOT NULL
- `external_id` — TEXT NOT NULL
- `user_id` — TEXT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE
- `linked_at` — TEXT NOT NULL

## `oauth_client`

- `client_id` — TEXT PRIMARY KEY
- `name` — TEXT NOT NULL
- `registration` — TEXT NOT NULL
- `redirect_uris` — TEXT NOT NULL
- `grant_types` — TEXT NOT NULL
- `client_secret_hash` — TEXT
- `metadata` — TEXT NOT NULL DEFAULT '{}'
- `created_at` — TEXT NOT NULL

## `auth_code`

- `code_hash` — TEXT PRIMARY KEY
- `client_id` — TEXT NOT NULL REFERENCES oauth_client(client_id)
- `user_id` — TEXT NOT NULL REFERENCES app_user(id)
- `redirect_uri` — TEXT NOT NULL
- `scope` — TEXT NOT NULL
- `code_challenge` — TEXT NOT NULL
- `method` — TEXT NOT NULL
- `resource` — TEXT
- `expires_at` — TEXT NOT NULL
- `used_at` — TEXT
- `authorization_id` — TEXT NOT NULL

## `access_token`

- `token_hash` — TEXT PRIMARY KEY
- `client_id` — TEXT NOT NULL
- `user_id` — TEXT NOT NULL REFERENCES app_user(id)
- `scope` — TEXT NOT NULL
- `audience` — TEXT NOT NULL
- `expires_at` — TEXT NOT NULL
- `revoked_at` — TEXT
- `created_at` — TEXT NOT NULL
- `authorization_id` — TEXT NOT NULL

## `refresh_token`

- `token_hash` — TEXT PRIMARY KEY
- `client_id` — TEXT NOT NULL
- `user_id` — TEXT NOT NULL REFERENCES app_user(id)
- `scope` — TEXT NOT NULL
- `expires_at` — TEXT NOT NULL
- `revoked_at` — TEXT
- `rotated_from` — TEXT
- `created_at` — TEXT NOT NULL
- `authorization_id` — TEXT NOT NULL

## `namespace`

- `id` — TEXT PRIMARY KEY
- `slug` — TEXT NOT NULL UNIQUE
- `title` — TEXT NOT NULL DEFAULT ''
- `owner_user_id` — TEXT NOT NULL REFERENCES app_user(id)
- `visibility` — TEXT NOT NULL DEFAULT 'private'
- `created_at` — TEXT NOT NULL
- `official` — BOOLEAN NOT NULL DEFAULT FALSE

## `namespace_member`

- `namespace_id` — TEXT NOT NULL REFERENCES namespace(id) ON DELETE CASCADE
- `user_id` — TEXT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE
- `role` — TEXT NOT NULL
- `added_at` — TEXT NOT NULL

## `blob`

- `sha256` — TEXT PRIMARY KEY
- `size` — INTEGER NOT NULL
- `created_at` — TEXT NOT NULL
- `storage` — TEXT NOT NULL DEFAULT 'db'
- `location` — TEXT

## `blob_content`

- `sha256` — TEXT PRIMARY KEY REFERENCES blob(sha256) ON DELETE CASCADE
- `bytes` — BYTEA NOT NULL

## `skill`

- `id` — TEXT PRIMARY KEY
- `namespace_id` — TEXT NOT NULL REFERENCES namespace(id) ON DELETE CASCADE
- `name` — TEXT NOT NULL
- `title` — TEXT NOT NULL DEFAULT ''
- `description` — TEXT NOT NULL
- `frontmatter` — TEXT NOT NULL
- `visibility` — TEXT NOT NULL DEFAULT 'private'
- `current_version_id` — TEXT
- `created_by` — TEXT NOT NULL REFERENCES app_user(id)
- `created_at` — TEXT NOT NULL
- `updated_at` — TEXT NOT NULL
- `deleted_at` — TEXT
- `public_rung` — TEXT
- `when_to_use` — TEXT
- `current_version_name` — TEXT
- `current_digest` — TEXT
- `current_version_submitted_at` — TEXT
- `draft_count` — INTEGER NOT NULL DEFAULT 0
- `newest_draft_submitted_at` — TEXT
- `newest_draft_version` — TEXT
- `newest_draft_digest` — TEXT
- `newest_title` — TEXT
- `newest_description` — TEXT
- `offline_at` — TEXT
- `category_key` — TEXT REFERENCES skill_category(key)
- `ever_published_at` — TEXT

## `skill_version`

- `id` — TEXT PRIMARY KEY
- `skill_id` — TEXT NOT NULL REFERENCES skill(id) ON DELETE CASCADE
- `number` — INTEGER NOT NULL
- `digest` — TEXT NOT NULL
- `file_count` — INTEGER NOT NULL
- `total_bytes` — INTEGER NOT NULL
- `changelog` — TEXT NOT NULL DEFAULT ''
- `source` — TEXT NOT NULL DEFAULT ''
- `submitted_by` — TEXT NOT NULL REFERENCES app_user(id)
- `submitted_at` — TEXT NOT NULL
- `state` — TEXT NOT NULL DEFAULT 'draft'
- `state_at` — TEXT
- `title` — TEXT NOT NULL DEFAULT ''
- `description` — TEXT NOT NULL DEFAULT ''
- `frontmatter` — TEXT NOT NULL DEFAULT '{}'
- `version` — TEXT
- `draft_name` — TEXT
- `revoked_reason` — TEXT
- `first_published_at` — TEXT
- `release_type` — TEXT

## `version_file`

- `version_id` — TEXT NOT NULL REFERENCES skill_version(id) ON DELETE CASCADE
- `relpath` — TEXT NOT NULL
- `blob_sha256` — TEXT NOT NULL REFERENCES blob(sha256)
- `size` — INTEGER NOT NULL
- `is_binary` — INTEGER NOT NULL DEFAULT 0
- `mode` — INTEGER NOT NULL DEFAULT 420

## `audit_event`

- `id` — BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY
- `actor_user_id` — TEXT
- `action` — TEXT NOT NULL
- `target_type` — TEXT NOT NULL
- `target_id` — TEXT
- `detail` — TEXT NOT NULL DEFAULT '{}'
- `at` — TEXT NOT NULL

## `phone_verification`

- `id` — TEXT PRIMARY KEY
- `phone_hash` — TEXT NOT NULL
- `purpose` — TEXT NOT NULL
- `code_hash` — TEXT NOT NULL
- `attempts` — INTEGER NOT NULL DEFAULT 0
- `expires_at` — TEXT NOT NULL
- `consumed_at` — TEXT
- `created_at` — TEXT NOT NULL

## `auth_throttle`

- `scope` — TEXT NOT NULL
- `key_hash` — TEXT NOT NULL
- `window_start` — TEXT NOT NULL
- `attempts` — INTEGER NOT NULL DEFAULT 0

## `spring_session`

- `primary_id` — CHAR(36) NOT NULL
- `session_id` — CHAR(36) NOT NULL
- `creation_time` — BIGINT NOT NULL
- `last_access_time` — BIGINT NOT NULL
- `max_inactive_interval` — INT NOT NULL
- `expiry_time` — BIGINT NOT NULL
- `principal_name` — VARCHAR(100)

## `spring_session_attributes`

- `session_primary_id` — CHAR(36) NOT NULL
- `attribute_name` — VARCHAR(200) NOT NULL
- `attribute_bytes` — BYTEA NOT NULL

## `captcha`

- `id` — TEXT PRIMARY KEY
- `answer_hash` — TEXT NOT NULL
- `attempts` — INTEGER NOT NULL DEFAULT 0
- `expires_at` — TEXT NOT NULL
- `consumed_at` — TEXT
- `created_at` — TEXT NOT NULL

## `oauth2_authorization_consent`

- `registered_client_id` — varchar(100) NOT NULL
- `principal_name` — varchar(200) NOT NULL
- `authorities` — varchar(1000) NOT NULL

## `oauth_authorization`

- `id` — TEXT PRIMARY KEY
- `registered_client_id` — TEXT NOT NULL
- `principal_name` — TEXT NOT NULL
- `grant_type` — TEXT NOT NULL
- `authorized_scopes` — TEXT NOT NULL
- `attributes` — TEXT NOT NULL DEFAULT '{}'
- `created_at` — TEXT NOT NULL
- `state` — TEXT
- `revoked_at` — TEXT
- `label` — TEXT
- `address` — TEXT
- `last_used_at` — TEXT

## `skill_grant`

- `skill_id` — TEXT NOT NULL REFERENCES skill(id) ON DELETE CASCADE
- `grantee_id` — TEXT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE
- `role` — TEXT NOT NULL
- `granted_by` — TEXT NOT NULL REFERENCES app_user(id)
- `created_at` — TEXT NOT NULL

## `api_key`

- `id` — TEXT PRIMARY KEY
- `user_id` — TEXT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE
- `name` — TEXT NOT NULL
- `note` — TEXT
- `token_hash` — TEXT NOT NULL UNIQUE
- `scope` — TEXT NOT NULL
- `expires_at` — TEXT
- `last_used_at` — TEXT
- `last_used_from` — TEXT
- `revoked_at` — TEXT
- `created_at` — TEXT NOT NULL

## `cli_sign_in_code`

- `code_hash` — TEXT PRIMARY KEY
- `authorization_id` — TEXT NOT NULL
- `code_challenge` — TEXT NOT NULL
- `expires_at` — TEXT NOT NULL
- `used_at` — TEXT
- `created_at` — TEXT NOT NULL

## `skill_access_request`

- `skill_id` — TEXT NOT NULL REFERENCES skill(id) ON DELETE CASCADE
- `requester_id` — TEXT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE
- `wanted_role` — TEXT NOT NULL
- `created_at` — TEXT NOT NULL

## `skill_library_join`

- `user_id` — TEXT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE
- `skill_id` — TEXT NOT NULL REFERENCES skill(id) ON DELETE CASCADE
- `created_at` — TEXT NOT NULL

## `skill_public_tag`

- `skill_id` — TEXT NOT NULL REFERENCES skill(id) ON DELETE CASCADE
- `tag_key` — TEXT NOT NULL
- `tag_name` — TEXT NOT NULL
- `created_at` — TEXT NOT NULL

## `my_tag_definition`

- `id` — TEXT PRIMARY KEY
- `user_id` — TEXT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE
- `name` — TEXT NOT NULL
- `name_key` — TEXT NOT NULL
- `description` — TEXT
- `created_at` — TEXT NOT NULL
- `updated_at` — TEXT NOT NULL

## `my_tag_attachment`

- `definition_id` — TEXT NOT NULL REFERENCES my_tag_definition(id) ON DELETE CASCADE
- `skill_id` — TEXT NOT NULL REFERENCES skill(id) ON DELETE CASCADE
- `created_at` — TEXT NOT NULL

## `skill_category`

- `key` — TEXT PRIMARY KEY
- `name` — TEXT NOT NULL
- `state` — TEXT NOT NULL DEFAULT 'enabled'
- `sort_order` — INTEGER NOT NULL
- `created_at` — TEXT NOT NULL

## `skill_embedding`

- `skill_id` — TEXT NOT NULL REFERENCES skill (id) ON DELETE CASCADE
- `model` — TEXT NOT NULL
- `text_hash` — TEXT NOT NULL
- `embedding` — vector(1024) NOT NULL
- `updated_at` — TEXT NOT NULL

## `blob_deletion`

- `sha256` — TEXT PRIMARY KEY
- `location` — TEXT NOT NULL
- `created_at` — TEXT NOT NULL

## `notification`

- `id` — TEXT PRIMARY KEY
- `kind` — TEXT NOT NULL
- `subject_key` — TEXT
- `actor_id` — TEXT REFERENCES app_user(id) ON DELETE SET NULL
- `actor_handle` — TEXT
- `skill_id` — TEXT REFERENCES skill(id) ON DELETE CASCADE
- `role` — TEXT
- `outcome` — TEXT
- `expires_at` — TEXT
- `title` — TEXT
- `body` — TEXT
- `created_at` — TEXT NOT NULL
- `resolved_at` — TEXT

## `notification_recipient`

- `notification_id` — TEXT NOT NULL REFERENCES notification(id) ON DELETE CASCADE
- `user_id` — TEXT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE
- `read_at` — TEXT
- `created_at` — TEXT NOT NULL

## `skill_invitation`

- `skill_id` — TEXT NOT NULL REFERENCES skill(id) ON DELETE CASCADE
- `invitee_id` — TEXT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE
- `role` — TEXT NOT NULL
- `invited_by` — TEXT NOT NULL REFERENCES app_user(id)
- `created_at` — TEXT NOT NULL
- `expires_at` — TEXT NOT NULL

## `skill_source`

- `id` — TEXT PRIMARY KEY
- `skill_id` — TEXT NOT NULL REFERENCES skill(id) ON DELETE CASCADE
- `role` — TEXT NOT NULL CHECK (role IN ('origin', 'mirror'))
- `kind` — TEXT NOT NULL
- `locator` — TEXT NOT NULL
- `path` — TEXT NOT NULL DEFAULT ''
- `label` — TEXT NOT NULL
- `url` — TEXT
- `created_at` — TEXT NOT NULL

## `skill_source_snapshot`

- `source_id` — TEXT NOT NULL REFERENCES skill_source(id) ON DELETE CASCADE
- `skill_version_id` — TEXT NOT NULL REFERENCES skill_version(id) ON DELETE CASCADE
- `revision` — TEXT
- `signals` — TEXT NOT NULL DEFAULT '[]'
- `status` — TEXT NOT NULL
- `fetched_at` — TEXT NOT NULL
- `written_by_sync` — BOOLEAN NOT NULL DEFAULT FALSE

## `skill_version_license`

- `version_id` — TEXT PRIMARY KEY REFERENCES skill_version(id) ON DELETE CASCADE
- `spdx` — TEXT NOT NULL
- `name` — TEXT NOT NULL
- `copyright` — TEXT
- `status` — TEXT NOT NULL
- `license_text` — TEXT
- `source_path` — TEXT
- `source_url` — TEXT
- `detail` — TEXT NOT NULL DEFAULT '{}'
- `fetched_at` — TEXT NOT NULL

## `skill_usage_day`

- `skill_id` — TEXT NOT NULL REFERENCES skill(id) ON DELETE CASCADE
- `day` — TEXT NOT NULL
- `account_id` — TEXT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE
- `scope` — TEXT NOT NULL DEFAULT 'public'

## `skill_usage_total`

- `skill_id` — TEXT NOT NULL REFERENCES skill(id) ON DELETE CASCADE
- `scope` — TEXT NOT NULL
- `calls` — BIGINT NOT NULL DEFAULT 0

## `service_credential`

- `id` — TEXT PRIMARY KEY
- `owner_user_id` — TEXT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE
- `name` — TEXT NOT NULL
- `ciphertext` — TEXT NOT NULL
- `byte_len` — INTEGER NOT NULL
- `per_day_limit` — INTEGER NOT NULL DEFAULT 500
- `created_at` — TEXT NOT NULL
- `rotated_at` — TEXT
- `last_used_at` — TEXT

## `credential_binding`

- `id` — TEXT PRIMARY KEY
- `credential_id` — TEXT NOT NULL REFERENCES service_credential(id) ON DELETE CASCADE
- `owner_user_id` — TEXT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE
- `skill_id` — TEXT NOT NULL REFERENCES skill(id) ON DELETE CASCADE
- `env_name` — TEXT NOT NULL
- `created_at` — TEXT NOT NULL

## `credential_use`

- `credential_id` — TEXT NOT NULL REFERENCES service_credential(id) ON DELETE CASCADE
- `day` — TEXT NOT NULL
- `count` — INTEGER NOT NULL
- `last_used_at` — TEXT NOT NULL

## `credential_share`

- `id` — TEXT PRIMARY KEY
- `credential_id` — TEXT NOT NULL REFERENCES service_credential(id) ON DELETE CASCADE
- `skill_id` — TEXT NOT NULL REFERENCES skill(id) ON DELETE CASCADE
- `grantee_user_id` — TEXT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE
- `granted_by` — TEXT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE
- `created_at` — TEXT NOT NULL
- `revoked_at` — TEXT

## `legal_consent`

- `user_id` — TEXT PRIMARY KEY REFERENCES app_user(id) ON DELETE CASCADE
- `terms_version` — TEXT NOT NULL
- `privacy_version` — TEXT NOT NULL
- `consented_at` — TEXT NOT NULL
