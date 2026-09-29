# 本地链追踪与地址状态变更索引服务

一个完全本地、无外部链服务依赖的交易追踪（internal call trace）与地址状态变更
索引服务。区块与交易原文通过本地 HTTP 接口提交，服务在本地做确定性重放、
增量索引、分叉重组、崩溃恢复、并发查询与历史归档。

## 1. 数据与追踪模型

### 1.1 输入

- `RawBlock`：`height / hash / parentHash / allocations / transactions`。
  - 高度 `0` 为创世块，可携带 `allocations`（地址初始余额）。
  - 其余块必须给出 `parentHash`。
- `RawTransaction`：`hash / from / to / value / gasLimit / input`。
  - `to` 为空表示合约创建（`CREATE`），否则为消息调用（`CALL`）。
  - `input` 是一段行式的 **trace script**，用最少的指令表达内部调用、
    存储读写、转账与失败点。

### 1.2 Trace script 指令

指令按空白分词；`{` `}` 始终是独立 token，既可内联也可跨行：

| 指令 | 参数 | 含义 |
| --- | --- | --- |
| `TRANSFER <to> <amount>` | 2 | 当前执行主体向外转账 |
| `SSTORE <key> <value>` | 2 | 写当前合约存储 |
| `SLOAD <key>` | 1 | 读存储，结果进入帧 `output` |
| `LOG <text...>` | 任意 | 输出文本 |
| `CALL <to> <value> <gas> { ... }` | 3 + 可选内联块 | 嵌套调用 |
| `DEPLOY <value> <gas> { ... }` | 2 + 可选构造块 | 嵌套部署，生成确定性合约地址 |
| `FAIL [reason]` | 0–1 | 当前帧失败并回滚自身写入 |

- 行首 `#` 为注释，空行忽略。
- `CALL` 目标已有已部署代码时执行该代码；无代码时执行内联块（根消息调用
  无代码则执行交易 `input`）。
- `DEPLOY` 的合约地址确定性派生为
  `c<height>_<txIndex>_<createSeq>`，构造块运行一次；部署后持久化的代码只
  保留可观察的存储类指令（`SSTORE/SLOAD/LOG`），使后续 `CALL` 行为可复现。

### 1.3 产出

- `TraceFrame`：调用树的一个帧，按 **DFS 前序** 编号。
  关键字段：`txHash / blockHeight / txIndex / frameIndex / depth / type /
  from / to / value / success / error / input / output / gasUsed /
  rolledBack`。
- `StateChange`：一条**已提交**的地址状态变更。
  关键字段：`txHash / blockHeight / frameIndex / seq / address / field /
  storageKey / oldValue / newValue`。
  - `field ∈ {balance, nonce, code, storage}`。
  - 创世分配的 `txHash` 为 `null`。
  - `seq` 是块内稠密、重放稳定的全局序号，决定同一稳定排序。

### 1.4 失败与回滚语义

- 每个帧在进入时建立 `AccountState` 快照；帧失败时只回滚该帧自身写入，
  父帧继续执行，子帧仍记录为 `success=false, rolledBack=true`。
- 根帧失败时整笔交易的应用层写入全部丢弃（转账、存储、合约代码都不落库、
  不索引），但发送方 `nonce` 递增与 gas 扣除仍然发生，与真实链一致。
- 重复交易哈希在规范链上只执行一次；再次出现进入 `skipped`
  （原因 `duplicate transaction`），不改变状态。
- 跨交易依赖天然成立：后续交易在共享世界状态上执行，可读到先前交易结果。

## 2. 确定性与一致性语义

- 执行引擎是纯函数：无网络、无时钟、无随机。相同有序区块流永远产生逐条
  相同的帧与状态变更。
- **增量 = 全量**：增量索引维护链头世界状态；分叉或恢复时从原始区块按
  genesis→fork 重放。两种路径的产出逐条一致（测试
  `incrementalAndFullReplayProduceIdenticalResults` 校验）。
- **单写者 + 不可变快照**：所有变更在 `IndexService` 内串行化；查询通过
  `LiveIndex.current()` 拿到一个不可变 `Snapshot`，归档视图也在发布时
  `freeze()`。慢查询不阻塞索引，也观察不到未提交中间态，查询负载不会导致
  数据丢失。

## 3. 提交协议与崩溃恢复

单块提交顺序（崩溃安全）：

1. 原始区块以内容寻址原子写入 `blocks/<hash>.json`（永不删除，含分叉分支，
   保证任何链都能重放）。
2. 派生段以临时文件 + 原子 rename 写入 `segments/<height>-<hash>.json`。
3. 仅在段文件落盘后，向 `wal.log` 追加并 fsync 一条 `COMMIT`，然后发布
   内存快照。

- 崩溃发生在 2→3 之间：留下的是没有 `COMMIT` 的孤儿临时/段文件，恢复时
  清理并忽略，从上一个已提交高度继续，不重复应用、不跳块。
- 恢复从最后一条 `RESET` 重放；若活段缺失或哈希不符，用原始区块确定性
  重新生成该段，再补齐 `COMMIT` 标记。

## 4. 分叉重组

- 提交时若新区块与规范链不一致，沿 `parentHash` 回溯定位共同祖先。
- 丢弃祖先之后的旧后缀，执行新分支；新分支所有段先全部落盘，再写
  **单条** `RESET`（含完整新规范哈希链与保留归档水位）作为原子切换点。
- 崩溃在重组中途：重启后要么恢复到旧链、要么恢复到完整新链，不会出现
  混合状态。
- **深重组**（分叉点进入已归档区间）：受影响归档先被展开回活段，再从
  genesis 确定性重放到分叉点并执行新分支；共同祖先上的旧数据不丢，旧分支
  数据不残留。

## 5. 归档压缩

- `POST /api/archive?toHeight=N` 把**连续、已固化前缀**
  `archivedTo+1..N` 压成一个不可变 zip（`archives/archive-<from>-<to>.zip`，
  内含 `manifest.json` 与每高度 `frames.json/changes.json`），成功后删除
  对应活段并追加 `ARCHIVE` 日志。
- 原始区块永不归档删除，因此归档不影响后续深重组与恢复的正确性。
- 归档后查询自动合并「归档 zip + 活索引」两部分，历史数据照常命中；
  旧 `Snapshot` 持有冻结归档视图，不受新归档/截断影响。

## 6. HTTP 接口

基础路径 `/api`。

### 提交区块

`POST /api/blocks`

```json
{
  "blocks": [
    {"height": 0, "hash": "g", "parentHash": "",
     "allocations": [{"address": "alice", "balance": 1000000}],
     "transactions": []},
    {"height": 1, "hash": "h1", "parentHash": "g",
     "transactions": [
       {"hash": "t1", "from": "alice", "to": "alice",
        "value": 0, "gasLimit": 200000,
        "input": "DEPLOY 0 100000 {\nSSTORE counter 1\n}"}
     ]}
  ]
}
```

返回 `{appliedHeights, headHeight, headHash, skipped[]}`。重复提交规范块
幂等（`appliedHeights` 为空）。

### 查询追踪帧

`GET /api/traces?txHash=&address=&fromHeight=&toHeight=&offset=&limit=`

- `address` 命中帧的 `from` 或 `to`；`txHash` 精确匹配。
- 稳定排序：`(blockHeight, txIndex, frameIndex)`。

### 查询状态变更

`GET /api/changes?address=&txHash=&fromHeight=&toHeight=&offset=&limit=`

- `address` 命中变更地址，`txHash` 精确匹配（创世记录 txHash 为 null）。
- 稳定排序：`(blockHeight, seq)`。

分页响应统一为：

```json
{"items": [...], "total": 123, "offset": 0, "limit": 50, "nextOffset": 50}
```

`nextOffset = -1` 表示无下一页。相同输入在同一已提交快照上结果稳定，
不受后续索引推进影响；分页为 offset 形式，默认 `limit=50`，上限
`chain-indexer.max-query-limit`（默认 1000）。

### 状态与归档

- `GET /api/status` → `{headHeight, headHash, archivedToHeight}`
- `POST /api/archive?toHeight=N` → `{archivedToHeight, segments, compressedBytes}`

非法参数/不连续/错误父哈希返回 HTTP 400：
`{error, message, timestamp}`。

## 7. 磁盘布局

```
${chain-indexer.data-dir}/
  blocks/                 # 原始区块，内容寻址，永不删除
  segments/               # 每高度派生段（临时 *.tmp 原子 rename）
  archives/               # 已固化前缀的 zip
  wal.log                 # append-only, fsync
```

## 8. 测试覆盖

- `VirtualMachineTest`：嵌套调用前序帧、跨交易依赖、内层失败仅回滚自身、
  根失败回滚应用写入但保留 nonce/gas、重复交易跳过、重放确定性。
- `IndexServiceTest`：增量与全量逐条一致、过滤/高度区间/分页、快照稳定、
  幂等重复提交、分叉重组、崩溃恢复。
- `ArchiveAndRecoveryTest`：归档后历史可查、活段+归档合并、归档连续性校验、
  进入归档区间的深重组 + 恢复一致、并发查询不阻塞/不丢数据、孤儿临时文件
  清理。
- `IndexControllerTest`：提交、追踪/变更分页、status/archive 接口。

运行：

```bash
mvn test
mvn spring-boot:run
```
