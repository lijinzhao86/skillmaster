#!/bin/sh
# 建测试库。主库（skillmaster）由 POSTGRES_DB 建好，官方镜像只允许这样建一个，所以第二个
# 库必须在这里出现。它只在数据卷为空时跑一次——也就是说，第一次 `docker compose up` 之后
# 改这里不会有任何效果，要重建得先 `docker compose down -v`（那会连同数据一起删掉）。
set -e

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
    CREATE DATABASE skillmaster_test OWNER "$POSTGRES_USER";
EOSQL
