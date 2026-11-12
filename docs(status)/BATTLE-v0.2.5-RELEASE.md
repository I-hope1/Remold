# 收口战报 · v0.2.5 封板（R1 + R2）

> 阶段：**v0.2.5 稳健发版封板**（不改核心业务算法）
> 基线：`main@d2f6446`（上一战役战报）→ 发版提交 `d97ca1b` + `c1923d8`
> 生成日期：2026-11-07

## 1. 范围与约束

本轮禁止改动核心算法，只做三件事：补齐 Fabric 侧真机证据、发版文档收口、封板总检。
产品代码（`core/` `plugin-mixin/` `plugin-forge/`）本轮**零改动**（`git diff d2f6446..HEAD` 只含版本号与文档/证据）。

## 2. 批次结果

| 批次 | 标题 | 状态 | 验收命令 | 退出码 |
|:--|:--|:--|:--|:--|
| R1 | Fabric SSOT 重构后 E2E 回归（原样重跑既有 `@Inject` 自演化探针） | 完成 | `grep -q "RESULT PASS failures=\[\]" tests/fabric-mixin-probe/evidence/inject-evolution-after-ssot.txt` | 0 |
| R2 | v0.2.5 发版文档收口与封板总检 | 完成 | `./gradlew :tests:verifyDocStatusMarkers :tests:verifyTestCount` | 0（doc 标记 0 违规；JUnit 469 / skipped 0） |

封板总检：`./gradlew check`（`ev-20261107154634-aa85`）exit 0；日志含
`verifyDocStatusMarkers: 全部规范文档与源码均无违规状态标记`、`JUnit 测试数 = 469, skipped = 0`。

## 3. 三端证据矩阵（同一个 `ClassInfoMemberSync` 内核）

| 端 | 运行时 | 探针 | 判定 | 证据文件 |
|:--|:--|:--|:--|:--|
| Fabric 1.21.1 | KnotServer，Fabric Loader 0.16.9，sponge-mixin 0.15.4+mixin.0.8.7，JBR 21 | `tests/fabric-mixin-probe/evo/run-evo.sh`（**未改动**，A2/B2/C2/C3） | `RESULT PASS failures=[]`；两次 `[Mixin] Mixin class-info sync: … addedMethods=1`（B2 换目标方法、C2 新增 handler），C3 移除后 `hookTwo` 不再触发 | `evidence/inject-evolution-after-ssot.txt`（agent `f6225c68…`，plugin `956b5a10…`） |
| Forge 1.21.1 | 专用服务端 `runServer --offline`（FG7，official mappings） | `tests/fabric-mixin-probe/forge-classinfo/` | 红 `VERDICT RED`（`Conform error … isUnique() … "method" is null`）→ 绿 `VERDICT GREEN`；**SSOT 重构后重跑仍 GREEN** | `forge-1.21.1-{baseline,after-fix,after-ssot-refactor}.txt` |
| NeoForge 21.1.265 | MC 1.21.1，ModLauncher 11.0.5，sponge-mixin 0.16.4，ModDevGradle 2.0.148，Gradle 8.12 | `tests/neoforge-mixin-probe/`（原生 `neoforge.mods.toml [[mixins]]` 注册，无手动 `addConfiguration`） | 红 `VERDICT RED`（禁用同步的 plugin jar）→ 绿 `VERDICT GREEN`（`addedMethods=1` / `addedFields=1`，业务终局 PASS）；dev 运行无 `InaccessibleObjectException`、`add_opens=0` | `neoforge-21.1-{baseline-red,after-fix}.txt` |

Fabric 侧关键点：**探针原样重跑**（未为新实现改断言），新增 handler 的 `ClassInfo` 同步是唯一新增日志；
若适配器丢失委托，C2 会立刻回到 `Conform injector …` 红态。这使「Fabric 只是薄适配」从单测升级为真机证据。

## 4. 发版文件齐套

- `CHANGELOG.md`：`Unreleased` 归档为 `## [0.2.5] - 2026-11-07`（Added / Changed / Fixed / Notes；26.x 边界写入 Notes）。
- `docs/releases/v0.2.5.md`：Highlights / Fixes / Added / Verification / Known limits（与 `v0.2.4.md` 同构）。
- `docs/status.md`：发布说明链接指向 v0.2.5；基准提交更新；§0.4 增补 Fabric 回归行；两条 NeoForge 候选条目
  新增**架构指引**——后续若命中命名模块封装，优先 Agent 运行期 `Instrumentation.redefineModule(extraOpens)`
  对 `ClassInfo.class.getModule()` 破壁，`--add-opens` 降级为兜底；并写明落地所需的两条证据。
- 版本号：`gradle.properties` → `0.2.5`，`remold.gradle` / `remold.gradle.kts` shim、`example/build.gradle`、
  `mindustry-mod-template/{gradle.properties,settings.gradle.kts,build.gradle.kts}`、
  `minecraft-fabric-template/{gradle.properties,build.gradle.kts}` 同步。
- 证据：`tests/fabric-mixin-probe/evidence/inject-evolution-after-ssot.txt`。

## 5. 残余风险（不得被 v0.2.5 的「三端绿」掩盖）

1. **NeoForge 26.x / FML 10+ 不支持也未验证**：该代际已移除 ModLauncher（库清单 `cpw.mods:*` 为 0、入口
   `net.neoforged.fml.startup.Client`），现有桥接按 ModLauncher 语义实现，不得从 21.1 证据外推。
2. **NeoForge 生产 installer 启动路径未验证**：E2E 是 dev 运行时（目录 classpath）。生产启动的模块归属可能不同，
   `--add-opens` 需求必须在生产路径重测；本版只提供精确诊断，未做运行期 `redefineModule` 破壁。
3. **单序列证据**：NeoForge/Fabric 证据各是一次 dev 运行序列，Mixin sessionId 与哈希不可跨轮次比较。
4. **真实客户端 GUI 未覆盖**：三端都是专用服务端探针；人工验收清单仍待用户确认。
5. **取证工具链瑕疵（非产品）**：本轮 `.battle` 目录被一个残留句柄占用，`battle_init` 反复 EPERM，故本战报
   以文件形式落盘（`docs(status)/`），R1/R2 的验收退出码在此明确记录；上一战役的 `battle_seal` 也有同样的
   工具侧 `paths[1]` TypeError（战报提交成功、清理步骤失败，已手工补齐并如实记录）。

## 6. 待指令

- 打 tag `v0.2.5`（含发布说明）。打 tag 时可同步发布 `gradle-plugin` / `api` 到 Maven，使模板的
  `remold.version=0.2.5` 可直接解析；本轮已在本地 mavenLocal 以 0.2.4 验证过同源脚本（探针使用）。
- 若需要把「无回归」提到最强：可再执行一次 Fabric 客户端 GUI 人工验收（`docs/acceptance/01-manual-checklist.md`）。
