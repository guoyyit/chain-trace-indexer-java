# chain-trace-indexer-java

本地区块链数据的**交易追踪**与**地址状态变更索引**服务（Spring Boot 3 / Java 17）。

仅依赖本地 HTTP 接口提交区块与交易原文，不依赖任何外部链服务或常驻进程，
提供：

- 确定性重放，产出内部调用追踪帧与地址状态变更；
- 嵌套调用、失败回滚、重复交易、跨交易依赖的正确归因；
- 按地址 / 交易哈希 / 高度区间查询，offset 稳定分页；
- 增量索引，且与全量重放逐条一致；
- 分叉重组（含进入归档深度的重组）；
- 任意时刻崩溃后的精确恢复；
- 索引构建与查询并发（单写者 + 不可变快照）；
- 已固化高度区间的 zip 归档压缩，归档后历史照常可查。

## 快速开始

```bash
mvn test
mvn spring-boot:run
```

默认数据目录：`data/chain-indexer`（可用 `chain-indexer.data-dir` 覆盖）。

## 文档

详见 [`docs/TRACING.md`](docs/TRACING.md)：追踪模型、trace script 指令、
一致性/提交/恢复语义、分叉与归档机制、HTTP 接口与测试覆盖。
