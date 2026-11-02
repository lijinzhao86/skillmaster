# M03 · 请求鉴权

> **状态**：已实现 · 拥有表与对外发布类型见 [`system.md`](../system.md) 的对应行（源是 `ModuleMap`）

## 职责

校验 `Authorization: Bearer`，把令牌解析成 subject 与 scope，并把 401 / 403 组织成 API 的形状。它横切 `/api/v1/**`，是每一个 API 请求都要经过的那一层。

## 边界

- **不做**：签发、刷新、撤销令牌——那是 M02（令牌与 AS）。
- **不做**：判断某个 skill 的权限梯子——那是 M12（skill 元数据）的 `Access`。
- **不做**：浏览器会话——浏览器面走 Spring Session（M01 的登录），不经过 Bearer。

## 对外契约

`AuthenticatedSubject` 是 API 层拿到的「谁在调用」；`TokenValidator` 与 `TokenValidation` 是校验结果。其余类型都是这一层的内部实现。

## 为什么这样切

鉴权是横切关注点，但它不是业务模块：把它放进任何业务模块都会让那个模块变成「所有请求的入口」。单独一层让 M02 只负责令牌本身，让 M12 只负责业务权限，两者在 `api` 层汇合。
