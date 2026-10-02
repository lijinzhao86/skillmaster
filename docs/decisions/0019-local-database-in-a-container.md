# ADR 0019 · 本地开发只把数据库放进容器，应用留在宿主机

- **状态**：已采纳
- **日期**：2026-10-02
- **依赖**：[ADR 0010](0010-storage-in-postgres.md)（唯一的存储是 PostgreSQL）、[ADR 0011](0011-server-and-cli-stack.md)（服务端栈）、[ADR 0015](0015-web-frontend-stack.md)（前端栈）

## 背景

在此之前，本地起一套环境要自己装并维护一个 PostgreSQL 实例：`brew services start postgresql@16`，再手工 `createdb`，`skillmaster-server/scripts/init-test-db.sh` 里那句「Docker is not available on every machine」就是这个状态的自述。这对**已经在跑的人**没问题，对**新同事**是这一段路里唯一需要理解和维护的一步——JDK 与 Node 有 `mvnw`、`.nvmrc` 兜着，数据库没有。

于是要决定的是：容器化到什么程度。选项：

1. 只把 PostgreSQL 放进容器，服务端与前端跑在宿主机上；
2. 三样都进容器（compose 起 db + server + web）；
3. 不进容器，继续用宿主机的 PostgreSQL，把 `createdb` 写进文档。

## 决定

**选 1。** 仓库根目录一个 `compose.yaml`，只定义 PostgreSQL 16.15（版本钉到次版本），数据落具名卷，初始化脚本建第二个库（测试库）；端口绑在 `127.0.0.1:5432`，与 `application.yml`、`application-test.yml` 的默认值一致，所以同事克隆后不用设任何数据库相关的变量。四个本地变量（静态令牌、两把钥匙、短信写日志）放 `.env.example`，`.env` 已在 `.gitignore` 里。

## 理由

**为什么不是 2。** 开发时改一行代码要重建镜像，热重载与断点调试都要另想办法，而这两样是前端与后端日常迭代里用得最多的东西。容器化买到的「环境一致」在这里是多余的：JDK 与 Node 的版本本来就由 `mvnw` 与 `.nvmrc` 钉住，真正难装、且版本会影响行为（排序规则、扩展可用性）的只有数据库。**把唯一难装的那一样放进容器，其余的留在能热重载的地方**，这就是这条决定的全部内容。

**为什么数据库要钉到次版本**（`postgres:16.15` 而不是 `postgres:16`）：本项目的测试里有依赖排序与 `ORDER BY` 的断言，次版本漂移会让「同事那边红、我这边绿」成为可能。这条与仓库其它依赖的处理一致。

**为什么不是 3。** 它把「新同事要理解并维护的东西」留在了原地，而这一条正是要解决的问题。

**为什么端口是 5432 而不是 5433。** 用一个不冲突的端口（5433）能保住本机已有的 PostgreSQL 实例，代价是**所有人**都要多设两个环境变量才跑得起来，而那正是要消除的摩擦。选 5432 与全部默认值一致，代价落在**本机已经装了 PostgreSQL 的那个人**身上——他要么停掉它，要么把自己那套挪到别的端口。这是一次有意识的取舍：**让新人少一步，而不是让旧人多一步**（写这条时本机确有一个自装的实例，已停用；`pigugu` 那个库仍在原数据目录里，`brew services start postgresql@16` 随时能恢复）。

**为什么不是 `trust` 认证 + 以 `$USER` 命名的角色**（那样能连 `.env` 都省掉）：它把「不用密码」写进一个同事可能改坏绑定地址的地方，而本项目的其余部分一律要求显式凭据。固定口令加回环绑定更钝，但不会因为一次手滑变成一个局域网上无口令的数据库。

## 后果

- **本地环境多两个文件**：`compose.yaml` 与 `.env.example`（`.env` 不入库）。README 的 Quick start 是权威用法。
- **`scripts/init-test-db.sh` 降为第二条路**：它自己的注释里那句「Docker 不是每台机器都有」已经不成立，改成了「这本是给宁愿自己跑 PostgreSQL 的机器的」。容器这条路上建测试库的是 `docker/postgres/initdb/`。
- **CI 可以对齐**：`.github/workflows/server.yml` 重启用时要的 `services: postgres` 可以照抄同一个镜像与同一份初始化脚本，本地与 CI 的库版本、库名不再各自漂移（该 workflow 目前是有意停用的）。
- **仍然未验证的**：容器里跑的是 macOS 上的 Linux 虚拟机（colima + Virtualization.framework），与生产（阿里云 RDS）是两套环境；`Dockerfile` 依旧从未构建过——本地这条路由不上它，它服务的是部署。
- **不影响任何对外可验收的行为**：没有新端点、没有改契约，`application.yml` 的默认值一个没动。
