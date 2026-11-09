<!-- 生成物，勿手改。源：skillmaster-server/src/main/resources/application.yml；重新生成：python3 scripts/docs/generate.py -->

# 配置项（生成）

服务端的配置键与默认值（**仅 main profile**；测试 profile 的键不在这里）。`${NAME:default}` 表示可以用环境变量覆盖，冒号后面是没给时的值。**部署时真正要设哪些，见部署目录的说明**——这里只列代码认得哪些。

| 键 | 默认值 |
|---|---|
| `spring.application.name` | `skillmaster-server` |
| `spring.datasource.url` | `${SKILLMASTER_DB_URL:jdbc:postgresql://localhost:5432/skillmaster}` |
| `spring.datasource.username` | `${SKILLMASTER_DB_USER:${USER}}` |
| `spring.datasource.password` | `${SKILLMASTER_DB_PASSWORD:}` |
| `spring.flyway.locations` | `classpath:db/migration` |
| `spring.flyway.baseline-on-migrate` | `false` |
| `spring.servlet.multipart.max-file-size` | `520MB` |
| `spring.servlet.multipart.max-request-size` | `520MB` |
| `spring.mvc.async.request-timeout` | `5m` |
| `spring.jackson.property-naming-strategy` | `SNAKE_CASE` |
| `spring.session.jdbc.initialize-schema` | `never` |
| `spring.session.timeout` | `${skillmaster.sessions.idle}` |
| `spring.task.scheduling.pool.size` | `2` |
| `server.forward-headers-strategy` | `framework` |
| `server.servlet.session.cookie.http-only` | `true` |
| `server.servlet.session.cookie.same-site` | `lax` |
| `server.servlet.session.cookie.name` | `SKILLMASTER_SESSION` |
| `server.tomcat.max-swallow-size` | `520MB` |
| `skillmaster.public-base-url` | `${SKILLMASTER_PUBLIC_BASE_URL:http://localhost:8080}` |
| `skillmaster.account.phone-hmac-key` | `${SKILLMASTER_PHONE_HMAC_KEY:}` |
| `skillmaster.account.phone-enc-key` | `${SKILLMASTER_PHONE_ENC_KEY:}` |
| `skillmaster.account.bcrypt-strength` | `${SKILLMASTER_BCRYPT_STRENGTH:10}` |
| `skillmaster.sessions.idle` | `${SKILLMASTER_SESSIONS_IDLE:24h}` |
| `skillmaster.sessions.absolute` | `${SKILLMASTER_SESSIONS_ABSOLUTE:7d}` |
| `skillmaster.site.origin` | `${SKILLMASTER_SITE_ORIGIN:}` |
| `skillmaster.site.cookie-domain` | `${SKILLMASTER_COOKIE_DOMAIN:}` |
| `skillmaster.welcome.skills` | `${SKILLMASTER_WELCOME_SKILLS:skillmaster/skillmaster-startup,skillmaster/skillmaster-guide}` |
| `skillmaster.sync.enabled` | `${SKILLMASTER_SYNC_ENABLED:false}` |
| `skillmaster.sync.sources-file` | `${SKILLMASTER_SYNC_SOURCES_FILE:classpath:sync-sources.yml}` |
| `skillmaster.sync.github-token` | `${SKILLMASTER_GITHUB_TOKEN:}` |
| `skillmaster.sync.trigger-token` | `${SKILLMASTER_SYNC_TRIGGER_TOKEN:}` |
| `skillmaster.sync.concurrency` | `${SKILLMASTER_SYNC_CONCURRENCY:5}` |
| `skillmaster.sync.cron` | `${SKILLMASTER_SYNC_CRON:0 0 2 * * *}` |
| `skillmaster.sync.max-repo-bytes` | `${SKILLMASTER_SYNC_MAX_REPO_BYTES:67108864}` |
| `skillmaster.sync.max-files` | `${SKILLMASTER_SYNC_MAX_FILES:512}` |
| `skillmaster.sync.max-file-bytes` | `${SKILLMASTER_SYNC_MAX_FILE_BYTES:104857600}` |
| `skillmaster.sync.max-total-bytes` | `${SKILLMASTER_SYNC_MAX_TOTAL_BYTES:104857600}` |
| `skillmaster.sync.max-unpacked-bytes` | `${SKILLMASTER_SYNC_MAX_UNPACKED_BYTES:268435456}` |
| `skillmaster.sms.access-key-id` | `${SKILLMASTER_SMS_ACCESS_KEY_ID:}` |
| `skillmaster.sms.access-key-secret` | `${SKILLMASTER_SMS_ACCESS_KEY_SECRET:}` |
| `skillmaster.sms.sign-name` | `${SKILLMASTER_SMS_SIGN_NAME:}` |
| `skillmaster.sms.template-code` | `${SKILLMASTER_SMS_TEMPLATE_CODE:}` |
| `skillmaster.sms.log-codes` | `${SKILLMASTER_SMS_LOG_CODES:false}` |
| `skillmaster.sms.accept-any-code` | `${SKILLMASTER_SMS_ACCEPT_ANY_CODE:false}` |
| `skillmaster.blobs.threshold-bytes` | `${SKILLMASTER_BLOB_THRESHOLD_BYTES:1048576}` |
| `skillmaster.blobs.max-bytes` | `${SKILLMASTER_BLOB_MAX_BYTES:104857600}` |
| `skillmaster.blobs.temp-dir` | `${SKILLMASTER_BLOB_TEMP_DIR:}` |
| `skillmaster.blobs.oss-enabled` | `${SKILLMASTER_OSS_ENABLED:false}` |
| `skillmaster.blobs.endpoint` | `${SKILLMASTER_OSS_ENDPOINT:}` |
| `skillmaster.blobs.bucket` | `${SKILLMASTER_OSS_BUCKET:}` |
| `skillmaster.blobs.access-key-id` | `${SKILLMASTER_OSS_ACCESS_KEY_ID:}` |
| `skillmaster.blobs.access-key-secret` | `${SKILLMASTER_OSS_ACCESS_KEY_SECRET:}` |
| `skillmaster.blobs.role-name` | `${SKILLMASTER_OSS_ROLE_NAME:}` |
| `skillmaster.secret.sealer` | `${SKILLMASTER_SECRET_SEALER:local}` |
| `skillmaster.secret.kms-key-id` | `${SKILLMASTER_SECRET_KMS_KEY_ID:}` |
| `skillmaster.secret.region` | `${SKILLMASTER_SECRET_KMS_REGION:}` |
| `skillmaster.secret.access-key-id` | `${SKILLMASTER_SECRET_ACCESS_KEY_ID:}` |
| `skillmaster.secret.access-key-secret` | `${SKILLMASTER_SECRET_ACCESS_KEY_SECRET:}` |
| `skillmaster.secret.local-key` | `${SKILLMASTER_SECRET_LOCAL_KEY:}` |
| `skillmaster.search.weights.name` | `${SKILLMASTER_WEIGHT_NAME:100}` |
| `skillmaster.search.weights.title` | `${SKILLMASTER_WEIGHT_TITLE:40}` |
| `skillmaster.search.weights.description` | `${SKILLMASTER_WEIGHT_DESCRIPTION:20}` |
| `skillmaster.diff.max-file-bytes` | `${SKILLMASTER_DIFF_MAX_FILE_BYTES:262144}` |
| `skillmaster.diff.max-lines` | `${SKILLMASTER_DIFF_MAX_LINES:5000}` |
| `skillmaster.diff.max-files` | `${SKILLMASTER_DIFF_MAX_FILES:20}` |
| `skillmaster.api-keys.allow-no-expiry` | `${SKILLMASTER_API_KEYS_ALLOW_NO_EXPIRY:true}` |
| `skillmaster.api-keys.last-used-window` | `${SKILLMASTER_API_KEYS_LAST_USED_WINDOW:PT10M}` |
| `skillmaster.tokens.access-token` | `${SKILLMASTER_ACCESS_TOKEN_TTL:PT1H}` |
| `skillmaster.tokens.refresh-token-idle` | `${SKILLMASTER_REFRESH_TOKEN_IDLE_TTL:P30D}` |
| `skillmaster.tokens.refresh-token-absolute` | `${SKILLMASTER_REFRESH_TOKEN_ABSOLUTE_TTL:P180D}` |
| `skillmaster.tokens.authorization-code` | `${SKILLMASTER_AUTHORIZATION_CODE_TTL:PT5M}` |
| `skillmaster.tokens.refresh-replay-grace` | `${SKILLMASTER_REFRESH_REPLAY_GRACE:PT60S}` |
| `skillmaster.tokens.activity-window` | `${SKILLMASTER_ACTIVITY_WINDOW:PT5M}` |
| `skillmaster.retrieval.enabled` | `${SKILLMASTER_RETRIEVAL_ENABLED:false}` |
| `skillmaster.retrieval.api-key` | `${DASHSCOPE_API_KEY:}` |
| `skillmaster.retrieval.model` | `${SKILLMASTER_RETRIEVAL_MODEL:text-embedding-v4}` |
| `skillmaster.retrieval.dimensions` | `${SKILLMASTER_RETRIEVAL_DIMENSIONS:1024}` |
| `skillmaster.retrieval.timeout` | `${SKILLMASTER_RETRIEVAL_TIMEOUT:PT5S}` |
| `skillmaster.retrieval.threshold` | `${SKILLMASTER_RETRIEVAL_THRESHOLD:0.40}` |
| `skillmaster.retrieval.over-fetch` | `${SKILLMASTER_RETRIEVAL_OVER_FETCH:20}` |

## 可用的环境变量

`DASHSCOPE_API_KEY`、`SKILLMASTER_ACCESS_TOKEN_TTL`、`SKILLMASTER_ACTIVITY_WINDOW`、`SKILLMASTER_API_KEYS_ALLOW_NO_EXPIRY`、`SKILLMASTER_API_KEYS_LAST_USED_WINDOW`、`SKILLMASTER_AUTHORIZATION_CODE_TTL`、`SKILLMASTER_BCRYPT_STRENGTH`、`SKILLMASTER_BLOB_MAX_BYTES`、`SKILLMASTER_BLOB_TEMP_DIR`、`SKILLMASTER_BLOB_THRESHOLD_BYTES`、`SKILLMASTER_COOKIE_DOMAIN`、`SKILLMASTER_DB_PASSWORD`、`SKILLMASTER_DB_URL`、`SKILLMASTER_DB_USER`、`SKILLMASTER_DIFF_MAX_FILES`、`SKILLMASTER_DIFF_MAX_FILE_BYTES`、`SKILLMASTER_DIFF_MAX_LINES`、`SKILLMASTER_GITHUB_TOKEN`、`SKILLMASTER_OSS_ACCESS_KEY_ID`、`SKILLMASTER_OSS_ACCESS_KEY_SECRET`、`SKILLMASTER_OSS_BUCKET`、`SKILLMASTER_OSS_ENABLED`、`SKILLMASTER_OSS_ENDPOINT`、`SKILLMASTER_OSS_ROLE_NAME`、`SKILLMASTER_PHONE_ENC_KEY`、`SKILLMASTER_PHONE_HMAC_KEY`、`SKILLMASTER_PUBLIC_BASE_URL`、`SKILLMASTER_REFRESH_REPLAY_GRACE`、`SKILLMASTER_REFRESH_TOKEN_ABSOLUTE_TTL`、`SKILLMASTER_REFRESH_TOKEN_IDLE_TTL`、`SKILLMASTER_RETRIEVAL_DIMENSIONS`、`SKILLMASTER_RETRIEVAL_ENABLED`、`SKILLMASTER_RETRIEVAL_MODEL`、`SKILLMASTER_RETRIEVAL_OVER_FETCH`、`SKILLMASTER_RETRIEVAL_THRESHOLD`、`SKILLMASTER_RETRIEVAL_TIMEOUT`、`SKILLMASTER_SECRET_ACCESS_KEY_ID`、`SKILLMASTER_SECRET_ACCESS_KEY_SECRET`、`SKILLMASTER_SECRET_KMS_KEY_ID`、`SKILLMASTER_SECRET_KMS_REGION`、`SKILLMASTER_SECRET_LOCAL_KEY`、`SKILLMASTER_SECRET_SEALER`、`SKILLMASTER_SESSIONS_ABSOLUTE`、`SKILLMASTER_SESSIONS_IDLE`、`SKILLMASTER_SITE_ORIGIN`、`SKILLMASTER_SMS_ACCEPT_ANY_CODE`、`SKILLMASTER_SMS_ACCESS_KEY_ID`、`SKILLMASTER_SMS_ACCESS_KEY_SECRET`、`SKILLMASTER_SMS_LOG_CODES`、`SKILLMASTER_SMS_SIGN_NAME`、`SKILLMASTER_SMS_TEMPLATE_CODE`、`SKILLMASTER_SYNC_CONCURRENCY`、`SKILLMASTER_SYNC_CRON`、`SKILLMASTER_SYNC_ENABLED`、`SKILLMASTER_SYNC_MAX_FILES`、`SKILLMASTER_SYNC_MAX_FILE_BYTES`、`SKILLMASTER_SYNC_MAX_REPO_BYTES`、`SKILLMASTER_SYNC_MAX_TOTAL_BYTES`、`SKILLMASTER_SYNC_MAX_UNPACKED_BYTES`、`SKILLMASTER_SYNC_SOURCES_FILE`、`SKILLMASTER_SYNC_TRIGGER_TOKEN`、`SKILLMASTER_WEIGHT_DESCRIPTION`、`SKILLMASTER_WEIGHT_NAME`、`SKILLMASTER_WEIGHT_TITLE`、`SKILLMASTER_WELCOME_SKILLS`
