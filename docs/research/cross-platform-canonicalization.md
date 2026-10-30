# 跨系统差异与「入库前统一」的清单

> **核对日期**：2026-10-30。
> **用途**：决定服务端要抹平哪些跨系统差异、做成一个什么模块，以及它和现有约束（尤其是 ADR 0005）的冲突。
> **口径**：服务端现状以代码为准（`modules/ingest`、`modules/distribution`、写路径）；外部事实带出处；没核实的标「未核实」。

## 三句话结论

1. **今天服务端是「原样保存」**：`IngestedFile` 的注释写明「never normalised, reformatted or re-encoded」，ADR 0005 的后果也明确要求「上传时不得改写内容，不做换行符或编码规范化」。**统一入库是对这条的直接反转**，必须新开一条 ADR 取代它，不能顺手改。
2. **要抹的差异不止换行**：编码（UTF-8 BOM / UTF-16）、Unicode 归一（NFC/NFD）、路径大小写碰撞、Windows 非法文件名与保留名、归档垃圾（`__MACOSX`、`.DS_Store`、`Thumbs.db`）、zip 文件名编码，都会让「同一份 skill」在不同系统上表现不同。
3. **能做的是「新写入统一、旧版本不动」**：线上版本不可变（ADR 0005/0064），历史数据无法回写；全库统一是目标态，不是对旧数据的承诺。

## 一、现状：服务端今天怎么处理

| 项 | 今天 | 代码 |
|---|---|---|
| 文件字节 | **原样保存**，不归一化 | `IngestedFile` 注释；`SkillDistributionService.bodyOf` 注释 |
| 二进制判定 | 不是合法 UTF-8 即二进制 | `IngestedFile.isBinary()` |
| 路径分隔符 | 拒绝 `\`、绝对路径、`..`、空段 | `SkillPaths.check` |
| 大小写碰撞 | **不检查**（`README.md` 与 `readme.md` 可并存） | `ZipReader` 只按字符串去重 |
| Unicode 路径归一 | **不做**（macOS 的 NFD 与 Linux 的 NFC 会被当成两个文件） | 同上 |
| Windows 非法名 | **不检查**（`a:b.md`、`CON`、尾随空格/点都可入库） | 同上 |
| 归档垃圾 | **不过滤**（`__MACOSX/`、`.DS_Store`、`Thumbs.db`、`desktop.ini` 会进 manifest） | 同上 |
| 符号链接 | 拒绝 | `ZipReader` |
| zip 文件名编码 | 交给 commons-compress：默认 UTF-8，有 EFS 标志或 Unicode extra field 时按其解析 | 未显式 `setCharset` |
| BOM | **保留**；只在解析 frontmatter 时临时跳过 | `FrontmatterParser` |
| 可执行位 | 不保存（CLI 打包不写 mode，下载也不还原） | `api.Archive` 用 `Create` |

## 二、需要抹平的差异（按优先级）

### A. 文本内容

| # | 差异 | 跨系统的表现 | 建议 |
|---|---|---|---|
| A1 | **换行**：LF / CRLF / CR | Windows 保存的 `SKILL.md` 全 CRLF；与 LF 文件做编辑/diff 时「全文件都变了」 | 文本统一为 **LF**；CR 也转 LF |
| A2 | **UTF-8 BOM** | Windows 记事本可能加 BOM；BOM 让首行不再等于 `---`，不同工具行为不一 | 文本统一**去掉 BOM**（frontmatter 解析已经容忍，但存储里保留会让 digest 与「同一段文字」不一致） |
| A3 | **UTF-16 / 其他编码** | Windows 记事本可存成 UTF-16；今天会被判成「二进制」，不可读、不可 diff | 识别 UTF-16 BOM（`FF FE` / `FE FF`）并**转成 UTF-8**；其余非 UTF-8 维持二进制（不猜编码） |
| A4 | **Unicode 归一**：NFC / NFD | macOS 输入法产生的重音字符常是 NFD，Windows/Linux 多为 NFC；同一段文字字节不同 → digest 不同 | 文本统一为 **NFC**。风险：讲解 Unicode 归一化的示例会被改写（见「开放问题」） |
| A5 | 文件末尾是否有换行 | 有的编辑器补、有的不补 | **不统一**：补一个换行会改变内容，且没有跨系统的一致预期；保留原样 |

### B. 路径与文件名

| # | 差异 | 跨系统的表现 | 建议 |
|---|---|---|---|
| B1 | 分隔符 | Windows zip 可能用 `\` | **已解决**：拒绝 `\`（commons-compress 读时也会改写） |
| B2 | 大小写 | macOS/Windows 文件系统大小写不敏感；`A.md` 与 `a.md` 在 Linux 是两个文件，在另外两个系统是一个 | **拒绝**大小写不敏感的碰撞（同一版本内） |
| B3 | Unicode 归一 | macOS 把文件名存成 NFD，Linux 存字节；`café.md` 两种写法看起来一样 | 路径段统一为 **NFC**；归一后碰撞则拒绝 |
| B4 | Windows 非法字符 | `< > : " \| ? *` 与控制字符在 Windows 上不能做文件名；今天能入库，用户下载时解不出来 | **拒绝** |
| B5 | Windows 保留名 | `CON`、`PRN`、`AUX`、`NUL`、`COM1…9`、`LPT1…9`（含带扩展名的形式） | **拒绝** |
| B6 | 尾随空格与点 | Windows 会静默去掉，导致与另一个名字碰撞 | **拒绝**（段首尾空白也一并拒绝） |
| B7 | 路径长度 | Windows 默认 260 字符上限；今天无限制 | 加**上限**（建议单段 ≤ 255 字节、整路径 ≤ 1024 字节），超了拒绝 |

### C. 归档垃圾与平台产物

| # | 差异 | 建议 |
|---|---|---|
| C1 | macOS：`__MACOSX/`、`._*`（AppleDouble）、`.DS_Store` | **丢弃**（不是内容，是 Finder 的产物） |
| C2 | Windows：`Thumbs.db`、`desktop.ini` | **丢弃** |
| C3 | Office/temp：`~$*`、`.swp`、`.tmp` | **丢弃**（或在报告里列出后丢弃） |
| C4 | 版本库目录：`.git/`、`.svn/` | **丢弃并报告**（体积大、与 skill 无关；若确实要，用户可打裸包）。最初建议的是「拒绝」，v33 定稿改成丢弃：这些是工具的产物，不是作者的决定 |

**丢弃与拒绝要分开**：C1/C2/C3 是系统产物，丢弃并在上传结果里说明；C4 按 v33 定稿也是工具的产物，同样丢弃并报告（不是用户错误，不拒绝）。真正拒绝的是路径层面的用户错误：大小写 / NFC 碰撞、Windows 非法字符与保留名、段首尾空白与结尾的点。

### D. 元数据

| # | 差异 | 建议 |
|---|---|---|
| D1 | 可执行位 | 今天的包格式不存 mode，下载也不还原——Windows 提交的脚本本来就没有 +x，Unix 提交的 +x 会丢。**本版不做**，记录为已知限制 |
| D2 | 符号链接 | 已拒绝 |
| D3 | 时间戳 | 不进 digest，无需处理 |

### E. zip 文件名编码

- commons-compress 默认按 UTF-8 解析名字；有 EFS 标志（bit 11）或 InfoZIP Unicode extra field 时按其解析。**转引自官方文档与 DeepWiki，1.28 未逐行核实**。
- 风险：Windows 用系统代码页（如 CP936）写的 zip、且没打 EFS 标志时，名字会解成替换字符（`U+FFFD`）却仍被接受。
- 建议：名字里出现 `U+FFFD` 即**拒绝**，并提示「重新用 UTF-8 打包」；不猜原始编码。

## 三、和现有约束的冲突

[ADR 0005](../decisions/0005-content-addressing.md) §后果原文：

> **上传时不得改写内容。** 托管要保真——不做格式化、不重写 frontmatter、不做"顺手"的换行符或编码规范化，否则 digest 会变、且内容与作者上传的不一致。

本方向要**取代这一句**（不是全部 ADR 0005：内容寻址、digest 算法、幂等都保留）。新 ADR 必须写清：

- 为什么当初不许改写（保真、digest 描述上传字节），现在为什么可以（跨平台同一内容的幂等、编辑与 diff 的可预期性）；
- 改写只发生在**入口**，读取路径仍然原样服务存储字节（`bodyOf` 的注释继续成立）；
- 旧版本不回写（不可变）；
- 审计里记录「这次上传被归一化了哪些文件」。

## 四、建议的模块

**位置**：`modules/canon`（新模块），纯函数，不碰数据库、不碰 HTTP。两个入口共用：

```
上传：ZipReader → Canon.upload(files) → ValidatedUpload
写入：PUT /files  → Canon.text(relpath, bytes) → 存 blob
复制：从底稿建草稿 → 是否归一化？见开放问题
```

**API 草案**：

| 函数 | 职责 | 失败方式 |
|---|---|---|
| `Canon.path(name) -> name or issue` | NFC、大小写/保留名/非法字符/长度 | 返回 issue（沿用 `SkillPaths.Issue` 的形状） |
| `Canon.text(relpath, bytes) -> bytes` | LF、去 BOM、UTF-16→UTF-8、NFC；二进制原样返回 | 不需要失败（非 UTF-8 判二进制） |
| `Canon.upload(files) -> (files, report)` | 丢弃 C1–C3、调 `path` 与 `text`、查大小写/NFC 碰撞 | 任一条 issue → 拒绝整包 |
| `Canon.report()` | 被丢弃的文件、被归一化的文件 | 进上传结果与审计 |

**明确不做**：不改 frontmatter、不改内容语义（不 trim、不重排）、不动二进制、不保存权限与时间戳、不猜非 UTF-8 编码。

## 五、迁移与顺序

1. **新写入生效**：上传（zip）、`write`、`edit`、从底稿建草稿都经过 `Canon`。
2. **旧版本不动**：线上与历史版本不可变，CRLF 的旧内容照旧服务。
3. **从旧版本建草稿时**：若归一化，这一版的 diff 会显示「全文件变了」（CRLF→LF），但只发生一次；若不归一化，草稿里会继续存在 CRLF，编辑仍要容忍。**当前倾向：归一化**，并在草稿页说明原因。
4. **CLI 侧的提示**：`skill submit` 可以在上传前先做同样的归一化并报告（服务端仍是权威），但这会改变客户端算出的包，建议**先不做**，等服务端规则稳定再考虑。

## 六、开放问题

1. **内容 NFC 要不要做？** 跨系统幂等需要它，但它会改写「讲解 Unicode 归一化」这类文件的内容。当前倾向：**做**，并在帮助里写明「入库会做 Unicode NFC 归一」。
2. **`.bat` / `.cmd` 豁免吗？** cmd.exe 对只有 LF 的批处理可能出错。当前倾向：**豁免**，把这两个扩展名当作二进制处理（不归一化、不改换行）。
3. **旧草稿要不要一次性归一化？** 不动已发布版本是确定的；草稿仍可改，是否提供一次「统一为 LF」的维护动作？当前倾向：**不提供**，下次编辑时自然归一。
4. **路径长度上限取多少？** 单段 255 字节、整路径 1024 字节是常见值；Windows 260 是「默认」而不是硬限制（可开启长路径）。
5. **zip 名字出现 `U+FFFD` 时拒绝还是警告？** 当前倾向：拒绝，提示重新打包。

## 七、未核实

- commons-compress 1.28 的默认编码行为（转引自文档与 DeepWiki，未逐行核对源码）。
- macOS 文件名在 APFS 上是否仍以 NFD 存储（HFS+ 是确定的；APFS 一般保留原字节但比较时归一）。
- Windows 各版本对「只有 LF 的 .bat」的实际容忍度（建议按不兼容处理）。

## 出处

- ADR 0005（内容寻址与幂等发布，含「上传不得改写」） / ADR 0064（草稿可改、线上不可改）。
- 代码：`modules/ingest/IngestedFile.java`、`SkillPaths.java`、`ZipReader.java`、`SkillUploadValidator.java`、`FrontmatterParser.java`；`modules/distribution/SkillDistributionService.java`；`skillmaster-cli/internal/api/submit.go`。
- 外部：[commons-compress 编码处理](https://deepwiki.com/apache/commons-compress/3.1.4-unicode-and-character-encoding)、[ZipFile 文档](https://commons.apache.org/proper/commons-compress/apidocs/org/apache/commons/compress/archivers/zip/ZipFile.Builder.html)（默认 UTF-8，EFS 标志优先；1.28 未逐行核实）。
- 本地 `Edit` 的字节级行为（换行归一、整文件重编码）见 [`local-edit-mechanics.md`](local-edit-mechanics.md)。
