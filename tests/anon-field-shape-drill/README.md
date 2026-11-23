# 匿名类非合成字段形态矩阵（轻量验证，DCEVM 真机）

> 文档类型：实测记录。这里只放原始日志与跑法；结论与口径见
> `docs/runtime/06-anon-field-shape-drill.md`。

## 跑法

```bash
GRADLE_USER_HOME=/home/ubuntu/remold/.gradle-user \
./gradlew --offline :tests:anonFieldShapeProbe -Pp05.jbr=/opt/idea/jbr \
    -Pdrill.shape=<add|retype|drop> -Pdrill.gate=<reject|warn> [-Pdrill.anonGate=<reject|warn>]
```

每个单元格一轮独立 JVM：`doFirst` 清空 `tests/build/anonfield`，探针现场用 `javax.tools` 编译 v1 匿名类夹具，
持有 2 个存活实例，再把所选形态的 v2 源码编译进同一个被监控目录，等 agent 防抖后热更，最后用**实例方法**
（`ver()` / `read()`）读终局，不用反射字段元数据当判据。

## 两套日志

- `evidence/`：**修复前**读数（当时摘要用"提交定义数"当成功数，见 `CHANGELOG.md` Fixed 条目）。
- `evidence-postfix/`：计数口径修好之后的同一矩阵；每格恰好一条发声行，启动期纯重扫静默。

## 单元格与原始日志（`evidence/`）

| 单元格 | 日志 | 终局 |
|:--|:--|:--|
| retype × reject | `evidence/retype-reject.log` | 拒绝（`ANON-LAYOUT` 拒配对 + 后置换名校验），值全保留 |
| retype × warn | `evidence/retype-warn.log` | 仍拒绝（后置换名校验无档位） |
| retype × warn + anonGate=warn | `evidence/retype-warn-anongate-warn.log` | 仍拒绝（同上） |
| add × reject | `evidence/add-reject.log` | 放行（两阶段），新字段补值，既有字段不动 |
| add × warn | `evidence/add-warn.log` | 同上 |
| drop × reject | `evidence/drop-reject.log` | 放行（`WAIVE_NO_READERS`），既有字段值保留 |
| drop × warn | `evidence/drop-warn.log` | 同上 |
