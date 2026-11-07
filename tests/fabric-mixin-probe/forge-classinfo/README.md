# Forge（ModLauncher）Mixin ClassInfo 成员同步 · 真机 E2E 探针

本目录是 Mixin 模板热更时**新增 `@Inject` handler / 新增 `@Unique` 字段**在 **Forge 侧**的真实服务端
端到端探针。它回答一个问题：Forge（ModLauncher + `TransformingClassLoader`）上把一份 Mixin 模板
热更后，新增的成员能不能真正落到目标类上，以及原有成员会不会被悄悄丢掉。

- 竞态/契约（不变量）定义在战役指令包 `.battle/briefs/a.md`（C1 时序与回滚、C2 ClassLoader 隔离）。
- Fabric 侧对应机制是 `plugin-mixin` 的 `MixinClassInfoSync`；本探针就是它的 Forge 对照。

## 1. 探针做了什么

真实 **Forge 1.21.1-52.1.21 服务端**（ForgeGradle 7 `runServer`，`--offline`，official mappings），
真实 Remold agent（`-javaagent`）+ 真实 `plugin-forge`，目标类 `ciprobe.ClassInfoTarget` 真正被
两个 Mixin 模板织入。服务端 `ServerStartedEvent` 之后、启动期类加载已经平静时开始测量：

| 阶段 | 真实动作 | 观测量（业务终局） |
|:--|:--|:--|
| 基线 `CI-BASE` | 无（Mixin v1 已织入） | 一次 `onReload()` 调用让模板 1 的 handler 计数 +1，`@Unique ciUnique` 读到 123 |
| 阶段 1 `CI-M1` | 模板 1 真实 `javac` 出**新增 `@Inject` handler** `ciInject2`，文件监听 → 模板 reload → 目标重织入 | 一次调用让 `ciInject2` 的计数 +1 |
| 阶段 2 `CI-M2` | 模板 2（同目标的第二份 Mixin）真实 `javac` 出**新增 `@Unique` 字段** `ciAdded = 456`，reload → 目标重织入 | 一次调用把 `ciAdded` 读回 456 |
| 保留 `CI-PRESERVE-1/2` | 上面两次热更之后 | 原有 handler 仍**每次调用恰好触发一次**、原有 `@Unique` 字段仍为 123 |

所有断言都落在**每次调用的增量**（delta）上，而不是累计计数器：若重织入把织入成员悄悄丢掉，
累计值仍然“看起来还在”，增量会立刻归零。这正是红线 —— 本目录的一次真机运行就抓到了这种形态。

## 2. 为什么需要 `Verdict.java`

探针只输出事实（`[CI] CHECK <id> PASS|FAIL <detail>`）。**归因**由 `Verdict.java` 单独判定，
它额外要求驱动记录每个阶段的 reload 事实：

```
[CI-DRIVER] phase=stage1 reload=success|rejected|weave-failed|not-attempted
[CI-DRIVER] phase=stage2 reload=...
```

- `not-attempted` / 缺失 ⇒ `INVALID`：**“什么都没发生”永远不许被读成红**。
- 某阶段 `success` 但对应成员检查 FAIL，或某阶段 `rejected`/`weave-failed` 但检查 PASS
  ⇒ `INVALID`（自相矛盾）。这条规则专门击杀“断言被改成永远 PASS”的弱探针。
- 其余情况：所有成员检查通过 ⇒ `GREEN`，否则 `RED` 并列出失败检查 id。

退出码：`0` GREEN / `1` RED / `2` INVALID。

## 3. 验收命令

```bash
bash tests/fabric-mixin-probe/forge-classinfo/verify-evidence.sh
```

三段互相独立的检查（全部离线、确定性）：

1. **判定力（反空转）**：用 `fixtures/` 的合成样本跑 `Verdict`，必须得到
   `RED{CI-M1,CI-M2}` / `GREEN` / `INVALID(PRECOND-RELOAD)` / `INVALID(SELF-CONTRADICTION)`。
   断言一旦不再区分“成员同步了”与“没同步”，这一步立刻变红。
2. **证据**：`evidence/` 下每份真机证据按文件名契约复核（`*-baseline*` 必须 RED 且两阶段都观测到
   reload 尝试；`*-after-fix*` 必须 GREEN 且两阶段 reload 成功），并检查 agent/plugin 的 sha256 溯源行。
3. **可选真机复跑**：`CI_E2E=1 bash verify-evidence.sh`（会重新拉起真实 Forge 服务端）。

## 4. 复跑真机 E2E

```bash
bash tests/fabric-mixin-probe/forge-classinfo/run-forge-classinfo.sh [label]
```

前置条件：JDK 21（`CI_JBR`，默认 `C:/Users/ASUS/.jdks/JBR-21`）；首次需要本机 Gradle 缓存里已有
ForgeGradle 7 (`7.0.40`) 与 `1.21.1-52.1.21` 的 mavenizer/MCP 产物（脚本全程 `--offline`）。
MDK 工作区每次由 `mdk/` + `mdk-overlay/` 重新物化到 `<repo>/build/forge-classinfo/mdk`，
不污染版本库；一次完整运行约 4 分钟。

驱动实际做的事：编译探针 mod → 启动 `runServer`（`-javaagent` + `plugin-forge`）→ 等基线织入 →
把阶段模板源码 `javac` 进被监视目录 → 等测量结果 → 记录阶段归属 → 写证据并跑 `Verdict`。

## 5. 已记录证据

| 文件 | 含义 |
|:--|:--|
| [`evidence/forge-1.21.1-baseline.txt`](evidence/forge-1.21.1-baseline.txt) | 当前实现（Forge 侧**没有** ClassInfo 成员同步）的真实运行：`RED`，阶段 1 被 Mixin 的 conform 校验拒绝，阶段 2 的重织入失败后回退源码字节码、把已织入的成员一起丢掉（`CI-PRESERVE-2` 变红）。这是本战役要修的缺陷的真实形态。 |

绿腿（`*-after-fix*`）需要 `plugin-forge` 侧补上 ClassInfo 成员同步（战役批次 B）；同一份脚本在
修复后复跑会自动落到 `forge-1.21.1-after-fix.txt` 并被本目录验收命令复核。

## 6. 明确的边界（不要读成“Forge 端到端全绿”）

- 只覆盖 **dev 模式 `runServer`**（FG7 classpath mod），Mixin 配置由探针在目标类首次加载前
  `MixinEnvironment.addConfiguration("ci.mixins.json")` 手动注册；`mods.toml` / jar manifest 的
  `MixinConfigs` 声明路径、生产 refmap、真实客户端与模组包都不在覆盖范围。
- 只覆盖**新增**成员（新 `@Inject` handler、新 `@Unique` 字段）；删除/改名、修改既有字段初值、
  方法签名变更不在本探针断言范围。
- 探针不注入合成观测代码：观察通道是目标类的静态计数器 + Mixin handler 自身写入，避免用
  “日志出现某行”代替业务终局。
