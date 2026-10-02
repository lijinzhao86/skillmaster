# 0010 · 本地开发环境：数据库进容器，应用留在宿主机

> **日期**：2026-10-02
> **所属版本**：[`../README.md`](../README.md)
> **类型**：小版本迭代——**未改变对外可验收的能力边界**

## 改了什么

**新增**（仓库根目录）：

- `compose.yaml` —— 一个服务：PostgreSQL **16.15**（钉到次版本），具名卷存数据，端口绑在 `127.0.0.1:5432`，健康检查用 `pg_isready`。
- `docker/postgres/initdb/01-create-test-database.sh` —— 官方镜像只允许 `POSTGRES_DB` 建一个库，所以第二个（`skillmaster_test`）由初始化脚本建。它只在数据卷为空时跑一次。
- `.env.example` —— 四个本地变量的值：静态令牌、两把钥匙、`SKILLMASTER_SMS_LOG_CODES=true`，以及数据库用户名口令。`.env` 本来就在 `.gitignore` 里。

**改**：

- 根 `README.md` 的 Quick start —— 从「先 `createdb`」改成「`docker compose up -d` + `cp .env.example .env`」，并把状态行那句「designed, not yet implemented」改成实情（P0a/P0c/M01 已实现，CLI 与 OAuth 未做）。
- `skillmaster-server/README.md`、`skillmaster-web/README.md` —— 建库那一步指向 compose。
- `skillmaster-server/scripts/init-test-db.sh` 的注释 —— 它原来写着「Docker is not available on every machine」；现在它是给「宁愿自己跑 PostgreSQL」的机器的第二条路。

## 为什么

触发是一句提问：「本机要不要开 docker？不然以后的环境都要装在本机，不利于同事配置本地测试环境。」**真正的摩擦只有一样**：JDK 与 Node 的版本有 `mvnw` 与 `.nvmrc` 兜着，而数据库要自己装、自己起、自己建库，且它的版本会影响行为（排序规则、扩展可用性，后者见 [ADR 0010](../../../decisions/0010-storage-in-postgres.md) 里 pg_bigm 的取舍）。

选「只容器化数据库」而不是三样都进容器，取舍与理由写在 [ADR 0019](../../../decisions/0019-local-database-in-a-container.md)；端口选 5432（与全部默认值一致，代价落在本机已装的旧实例上）的理由也在那里。

实现过程中值得记下的两件事：

- **容器的排序规则要与原来那套对齐**，所以选了 Debian 版（glibc + en_US.utf8）而不是 alpine（musl）——测试里有依赖排序的断言，而这是唯一一处「换个镜像就可能让同事那边红」的地方。实测下来：**整套 320 个测试跑在容器上全绿**（19.5 秒，比本机那套慢 7 秒，是容器 I/O 的正常代价）。
- **`docker compose` 装完不能直接用**：Homebrew 的 `docker-compose` 是 CLI 插件，要按 brew 自己的提示往 `~/.docker/config.json` 加 `cliPluginsExtraDirs`。这一步写进了 README 之外的安装顺序里。

## 影响

- **对外可验收的行为：无影响**。没有新端点，没有改契约，`application.yml` 的默认值一个没动。
- **同事的路径变短**：装 JDK 25、Node 24、一个容器运行时，然后 `docker compose up -d` —— 不必装也不必维护 PostgreSQL。
- **同步的文档**：本文件、[ADR 0019](../../../decisions/0019-local-database-in-a-container.md) 与 `decisions/README.md` 的索引、根 `README.md`、两个子项目 README、`init-test-db.sh` 的注释。
- **未验证**：容器跑在 macOS 的 Linux 虚拟机（colima + Virtualization.framework）里，与生产（阿里云 RDS）是两套环境；`Dockerfile` 依旧从未构建过——它服务的是部署，本地这条路由不上它。
- **是否需要新开 ADR**：需要，见 [ADR 0019](../../../decisions/0019-local-database-in-a-container.md)（跨版本：这个项目怎么被开发，以及同事需要装什么）。
