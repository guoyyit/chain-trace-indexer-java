# Chain Trace Indexer (Java)

本地区块链数据的**交易追踪**与**地址状态变更索引**服务。输入只有区块与交易原文
（通过本地 HTTP 接口模拟提交），不依赖任何外部链服务或常驻进程。服务重放每笔交易，
产出内部调用树（trace）与地址状态变更（changes），支持分页查询、增量索引、分叉重组、
崩溃恢复、读写并发以及历史归档压缩。

## 1. 运行与构建

```bash
mvn spring-boot:run
# 或
mvn package && java -jar target/chain-trace-indexer-java-1.0-SNAPSHOT.jar
```

配置（`src/main/resources/application.properties`，均可由环境变量覆盖）：

| 配置 | 默认值 | 含义 |
| --- | --- | --- |
| `chain-trace.data-dir` | `data/chain-trace` | WAL、归档对象与状态目录 |
| `chain-trace.wal-segment-bytes` | `67108864` | WAL 单段大小 |
| `chain-trace.compaction-threshold-bytes` | `268435456` | 超过该大小触发 WAL 压缩 |
| `chain-trace.default-page-limit` | `50` | 默认分页大小 |
| `chain-trace.max-page-limit` | `1000` | 单页最大条数 |

## 2. 追踪模型

### 2.1 输入结构

区块 `RawBlock{height, hash, prevHash, allocations[], transactions[]}`：

- `allocations`：区块级余额预置（本地模拟创世/充值），产生 depth = `-1` 的
  `BALANCE` 变更，不属于任何交易。
- 交易 `RawTransaction{hash, from, value, code[]}`：`code` 是迷你指令语言的文本行。

指令语法（`#` 起为注释，支持缩进但不依赖缩进，`call` 必须由 `end` 结束）：

```text
transfer TO VALUE            # 在当前执行地址内向 TO 转账
call TO VALUE                # 开启一个嵌套调用帧，向 TO 转入 VALUE
  <嵌套指令...>
end
revert REASON                # 回滚当前帧并向上传播失败
sset KEY VALUE               # 在当前执行地址写存储
sdel KEY                     # 删除当前执行地址的某个存储键
```

数字支持十进制、`0x` 十六进制以及下划线分隔（如 `1_000`）。

### 2.2 执行与回滚语义

每笔交易开始时：发送者 nonce +1，交易 `value` 从发送者余额扣除（记入零地址 `0x0`）。
随后逐条解释指令：

- `call` 产生一个**子调用帧** `CallFrame{from,to,value,depth,success,revertReason,children}`，
  先转账，再在 **callee 上下文**中执行其指令体；root 帧 depth=0，顶层指令 depth=1，逐层加深。
- 任一帧失败（`revert`、余额不足等）：
  1. 该帧产生的余额/nonce/存储修改与变更记录**全部回滚**（帧级检查点 + 逆操作日志）；
  2. 失败信息与（已完成的）子帧挂在该失败帧上；
  3. 失败**向上传播**，最终导致整笔交易失败，交易级状态（nonce、value、所有调用）
     全部回滚，该交易不产生任何已提交变更，trace 为空。
- 交易 `value` 从发送者扣除（记入零地址）这一步本身也可能失败（余额不足），
  此时整笔交易同样回滚（nonce 递增一并撤销），结果 `success=false`、`trace=null`、
  `changes=[]`。失败交易仍计入链上交易序列（`applied=true`），其哈希被消费，
  重复提交时按重复交易处理。
- 存储按 `(address, key)` 记录字符串值；删除记为 `newValue = null`。
- 同一 `tx hash` 在链上只应用一次：重复提交时结果标记 `applied=false`，
  不修改世界状态、不产生 trace/changes。
- 跨交易共享同一个世界状态，后一笔交易可见前一笔的最终余额、nonce 与存储。

输出：

- `CallFrame`：内部调用树。
- `StateChange{address, type(BALANCE|NONCE|STORAGE), key, oldValue, newValue, depth}`：
  有序的状态变更序列。
- `TransactionResult{hash, applied, success, revertReason, trace, changes[]}`。
- `BlockResult{height, hash, allocations[], transactions[]}`。

重放是**确定性纯函数**：相同的区块序列与初始状态必然产出逐条一致的结果，
不读取时钟、随机数或外部输入。

## 3. 一致性与存储语义

### 3.1 提交协议（无部分写入）

每个区块变更按严格顺序进行：

1. 重放生成 `BlockResult`；
2. 将 `BlockCommitted`（或重组时的 `BlockUndone`）追加到 **WAL** 并 `flush`；
3. 才更新内存索引与链头。

WAL 帧格式为 `crc32 | length | payload | '\n'`。崩溃后重启时：

- 校验所有已提交段，尾部半截/CRC 错误的帧被截断丢弃（未完成的写入不可见）；
- 从头重放 `committed/undone/archived` 记录，重建索引与世界状态，
  因此**恰好从最后一次已提交位置继续**，不重复应用也不跳过区块。

**崩溃恢复的保证范围**：

- 已 fsync 到 WAL 的 `BlockCommitted` / `BlockUndone` / `BlockArchived` 记录在重启后必然可见，
  索引重建到这些记录所确定的状态。
- 重组过程中（`BlockUndone` 已落盘、`BlockCommitted` 尚未落盘）进程被终止时，
  重启后链头停在撤销后的位置（旧分支已撤销、新分支未提交），用户可重新提交分叉块继续推进，
  不重复应用也不跳过区块。
- 失败交易在重组中的语义与正常路径一致：失败交易不产生任何已提交追踪或变更，
  其哈希随区块撤销而释放，重组前后均无残留或丢失。
- 未 fsync 的写入（包括内存中的索引状态）在崩溃后丢失，由 WAL 重放重建。

### 3.2 增量索引与全量一致

`height = tip+1` 的区块只重放该区块，绝不重放历史。增量构建完成后的索引内容
（每个 trace、每条 change、每个地址计数）与从空库全量重放逐条一致，
由 `IncrementalConsistencyTest` 保证。

### 3.3 分叉重组

- `height <= tip` 且哈希相同：
  - 等于 tip → 幂等空操作（`ignored_duplicate_block`）；
  - 小于 tip → 回滚到该锚点高度（`rolled_back`），用于深层重组。
- `height == tip` 但哈希不同且 `prevHash` 与当前父块一致 → 单块替换（`reorganized`）。
- 深层重组的调用方式：先逐块提交"新链上的锚点"（同哈希回退到分叉点），
  再从分叉点逐块延伸新链。

回滚按高度逐条撤销 WAL 与索引，世界状态按剩余链的最终变更值重建。
旧分支的 trace/change 全部消失，共享前缀与仍在新链上的数据完整保留。

**失败交易的重组语义**：跨越包含失败交易的区块时，重组正常完成并返回 `reorganized`。
失败交易不产生任何已提交追踪或变更（`trace=null`、`changes=[]`），其哈希随区块撤销而释放；
重组后按地址、交易哈希与高度区间查询到的追踪与变更，与从空库对最新链上区块序列做全量重放的结果
逐条一致，不残留旧分支数据，也不丢失仍在新链上的数据。

### 3.4 读写并发（MVCC）

索引每次 apply/undo/archive 都构造一棵**不可变状态**并原子发布：

- 查询在开始时获取一个 `Snapshot`，整个查询期间读取同一已提交版本，
  看不到未提交的中间状态，也不受后续索引推进影响（相同输入结果稳定）。
- 写操作不等待读者完成；慢查询只持有旧的不可变数据，不阻塞索引推进。
- 快照带引用计数；归档垃圾回收只删除"当前状态与所有存活快照都不可达"的对象。

### 3.5 归档压缩

`POST /api/v1/archive?toHeight=N` 将连续的历史热块移到内容寻址归档
（`archive/blocks/<sha256>.json`，临时文件 + 原子 rename）：

1. 先落不可变归档对象；
2. WAL 追加 `BlockArchived`（轻量 marker：交易身份/结果/from/trace 骨架、
   每地址 change 计数）；
3. 发布 marker 并把热块移出内存。

归档后查询接口不变：轻量索引保留全部计数与归属，需要完整 trace/change 时
**按需穿透读取**归档对象。归档不影响重组（回滚会从归档对象重建索引）与恢复
（重启时重放 WAL 并重建归档 marker/对象）。

### 3.6 WAL 压缩的崩溃安全

WAL 段累计超过 `compaction-threshold-bytes` 时触发压缩：把当前全部有效记录
（所有已提交/未撤销/已归档状态）重写到一个新段，随后删除旧段。压缩按以下严格顺序进行，
`manifest` 是唯一的提交点：

1. 关闭当前活动段，把有效记录写入 `compact-<nano>.tmp`，`fsync` 落盘；
2. 原子 `rename` 为 `seg-NNNNNNNN.log`，`fsync` 目录；
3. **原子更新 `manifest`**（先写 `manifest.tmp` 并 fsync，再 rename，再 fsync 目录）
   到新段区间 `[N, N+1)` —— 这是提交点；
4. 在新段上重新打开活动段；
5. 删除旧段，`fsync` 目录。

崩溃窗口与恢复规则：

| 崩溃窗口 | 磁盘状态 | 重启恢复行为 |
| --- | --- | --- |
| 步骤 1 写入中 | `compact-*.tmp` 部分写入，旧段与 manifest 完好 | 删除临时文件，读旧段，数据不丢 |
| 步骤 2 后、步骤 3 前 | 新段已就位，manifest 仍指向旧段 | 删除孤儿新段，读旧段，数据不丢 |
| 步骤 3 后、步骤 5 前 | manifest 指向新段，旧段未删 | 读新段，删除孤儿旧段，数据不丢 |
| 步骤 5 后 | manifest 指向新段，旧段已删 | 读新段，数据不丢 |

恢复时还会清理所有 `*.tmp` 临时文件与提交区间之外的孤儿段，因此压缩在任意阶段崩溃
都不会导致已索引数据整体丢失、链头回退到空链、或最近一次已提交位置之前的追踪与变更无法查询。

## 4. 查询接口

所有列表接口均按 `(height, txIndex, changeIndex)` 升序稳定排序，分页参数
`offset >= 0`、`limit <= 1000`；trace 帧按深度优先前序遍历展开。

### 提交区块

```http
POST /api/v1/blocks
Content-Type: application/json

{
  "height": 1,
  "hash": "h1",
  "prevHash": "g",
  "allocations": [{"address": "A", "balance": 1000000}],
  "transactions": [{
    "hash": "tx-1", "from": "A", "value": 10,
    "code": ["call B 4", "  sset k hello", "  end"]
  }]
}
```

返回 `{action, fromHeight, toHeight, chain[]}`，action 为
`extended | reorganized | rolled_back | ignored_duplicate_block`。

### 按地址查交易追踪

```http
GET /api/v1/addresses/{address}/traces?fromHeight=0&toHeight=100&offset=0&limit=50
```

返回 `Page<TransactionResult>`（仅含该地址出现过、且有 trace 的已应用成功交易）。

### 按地址查状态变更

```http
GET /api/v1/addresses/{address}/changes?fromHeight=0&toHeight=100&offset=0&limit=50
```

返回 `Page<StateChange>`，包含区块级 allocation（depth=-1）与各交易内变更。

### 按交易哈希查询

```http
GET /api/v1/transactions/{txHash}?traceOffset=0&traceLimit=1000&changeOffset=0&changeLimit=1000
```

返回 `{height, transaction, tracePage, changes}`；重复交易的 `applied=false`。
不存在返回 404。

### 归档与健康

```http
POST /api/v1/archive?toHeight=1000
GET  /api/v1/health          # {tipHeight, tipHash}
```

分页响应统一为：

```json
{"items": [...], "limit": 50, "offset": 0, "total": 123, "hasMore": true}
```

错误：`400 invalid_request`、`409 missing_parent`（重组父块不匹配）、
`422 non_sequential_height`（跳号提交）、`404`（哈希查无交易）。

## 5. 测试

```bash
mvn test
```

覆盖场景：

- `ReplayEngineTest`：嵌套调用追踪、内层失败帧级回滚、顶层 revert 全交易回滚、
  重复交易不应用、跨交易状态依赖。
- `IncrementalConsistencyTest`：增量与全量重放逐条一致。
- `ReorgTest`：分叉撤销 + 共享前缀保留、跨归档边界深层重组。
- `RecoveryTest`：重启从最后提交位置续跑、WAL 尾部截断后无部分写入。
- `ConcurrencyTest`：并发读写快照隔离、慢查询不阻塞索引。
- `ArchiveTest`：归档后查询命中、GC 保留旧快照可达对象、归档后重组正确。
- `QueryServiceTest`：查询稳定性（不受后续推进影响）、分页顺序与边界。
- `ApiIntegrationTest`：HTTP 提交/查询/归档/健康端到端。
- `FailedTxReorgReproTest`：含失败交易的链上提交分叉块并重组，重组正常返回 `reorganized`，
  失败交易不产生追踪/变更。
- `CrashRecoveryTest`：重组过程中强制终止后重启恢复（从最后提交位置续跑）、
  WAL 压缩不同阶段（临时文件、提交前、提交后）强制终止后重启恢复、
  恢复后增量与全量重放逐条一致并可继续提交新区块。
- `ReorgConsistencyTest`：失败交易重组后与全量重放逐条一致、
  存储删除在重组后重建正确（不残留旧值）、恢复后深层重组与全量重放一致。

### 复现验证方法

上述崩溃场景通过在测试中直接构造崩溃窗口的磁盘状态来确定性复现，不依赖外部服务与网络：

- **重组崩溃**：先提交区块，再向 WAL 追加 `BlockUndone`（模拟重组的第一个 fsync 已落盘、
  `BlockCommitted` 尚未落盘），关闭后重启，验证链头停在撤销后的位置且可重新提交分叉块。
- **压缩崩溃**：提交区块后，手动复制段文件构造"新段已就位 / manifest 未更新"或
  "manifest 已更新 / 旧段未删"等窗口，或写入 `*.tmp` 临时文件，重启后验证数据完整且孤儿文件被清理。
- **一致性验证**：用 `IncrementalConsistencyTest.assertIndexEquivalent` 逐块比对
  区块哈希、allocation、每笔交易的 `applied/success`、trace 字符串与每条 change 的
  地址/类型/键/旧值/新值/深度，以及按地址的交易计数与变更计数。

## 6. 目录布局

```
<data-dir>/
  wal/seg-NNNNNNNN.log     # CRC 校验的分段追加日志 + manifest
  archive/blocks/<sha>.json # 内容寻址的归档区块对象
```
