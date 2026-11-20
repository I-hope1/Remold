文档类型：实测记录。本文件只记录读数与观测窗口；实现状态与残余风险以 [`docs/status.md`](../../docs/status.md) 为准。

# 第二轮探针：运行期 `mixins.json` 变更是否重推目标索引

环境：Fabric 1.21.1、loader 0.16.9、sponge-mixin 0.15.4+mixin.0.8.7、JBR 21.0.8，
KnotServer 从 scratch Loom 工程启动，Remold agent 与 `plugin-mixin` 由本 worktree 构建后装入。

原始日志：[`evidence/lf-round2-run1.txt`](evidence/lf-round2-run1.txt)（80 行未过滤 `[LF-PROBE]`）。
驱动：[`Round2Probe.java`](Round2Probe.java)（场景 A + 场景 B 同一轮跑完）。

## 场景 A：运行期把新 `@Mixin` 类写进 config

夹具：`LateTargetMixin`（`@Mixin(LateTarget.class)`）已编译、可加载，但**初始不在** `mixins.json`。
探针在运行期改写 `build/resources/main/example_mod.mixins.json`，把它同时加进 `mixins` 与 `client` /
`server` 列表。

```
[INDEX before] available=false size=0 stamp=0
[LOOKUP before] com/example/probe/LateTarget -> UNKNOWN
[CONFIG before] mixinMapping.size=2 hasLateTarget=false unhandledTargets=[com.example.probe.StringTarget]
...
[CONFIG] wrote runtime config build/resources/main/example_mod.mixins.json
=== AFTER: stamp change observed=true stampBefore=0 stampAfter=3 sizeBefore=0 sizeAfter=163
[INDEX after] available=true size=163 stamp=3
[LOOKUP after] com/example/probe/LateTarget -> TARGET
[CONFIG after] mixinMapping.size=3 hasLateTarget=true
                unhandledTargets=[com.example.probe.LateTarget, com.example.probe.StringTarget]
```

读数结论（已观测）：

- 运行期 config 变更后索引**确实被重推**：`stamp` 0 → 3、`size` 0 → 163；
- 目标类从 `UNKNOWN` 翻成 `TARGET`；
- 该 config 的 `mixinMapping` 由 2 增到 3 且含 `LateTarget`；
- `unhandledTargets` 在登记之后**仍然**列出 `LateTarget` 与 `StringTarget`——与第一轮读数一致，
  说明"索引必须取两个来源的并集"这条结论在"运行期新增"这个路径上同样成立。

## 场景 B：未列入任何 config 的 `@Mixin` 类（负对照）

夹具：`UndeclaredTargetMixin`（合法 `@Mixin(UndeclaredTarget.class)`），任何 `mixins.json` 都不列出。

```
[LOOKUP control] com/example/probe/UndeclaredTarget -> NOT_TARGET
[CONTROL] stamp stable=true stamp=3 undeclaredLookup=NOT_TARGET
```

读数结论（已观测）：未被任何 config 列出的 `@Mixin` 类**不构成接入点**——索引不因它的存在而变动
（同轮 `stamp` 保持 3），其目标稳定为 `NOT_TARGET`。这条负读数把"Mixin 的发现必须经由
`mixins.json`"从经验说法变成实测边界。

## 本轮**未**取到的读数

- **CLIENT 侧读数缺失**：runner 无 X 显示（无 `xvfb-run`），KnotClient 无法启动，因此
  "CLIENT / SERVER 双环境下 `mixinMapping` / `unhandledTargets` 填充差异"这一项**未测量**。
  替代口径：本轮只覆盖 SERVER；CLIENT 侧的填充差异需要一次带虚拟显示的运行才能给出同口径读数。
- **config 名称反射为 `?`**：探针按 `getName()` 读 config 名失败（本版 Mixin 的 `MixinConfig`
  不暴露该方法），因此上面按"我们自己的那个 config"（唯一含 `LateTarget` 的条目）定位；
  `mixinMapping.size` 与 `hasLateTarget` 仍是从该条目真实读出的。
- **`stamp` 从 0 直接跳到 3**：`stamp` 是"发布次数"而非"内容版本"，本轮三次发布来自
  配置差异路径 + 就绪门懒发布 + 一次待决重试；读数只说明"发生了重推"，不能用来推断单次变更的
  发布次数上限。

## 附：加载钩子路径的逐类开销（代理测量，非冷启动）

命令：编译 `tests/src/remold/LoadPathOverheadBench.java` 后直接跑 `main`（JBR 21.0.8，`-Xmx1g`，
3000 个类名、每项 2 万次预热）。**这是进程内逐类开销，不等于真机冷启动或懒加载掉帧**；后者需要
KnotServer 起停对照，本轮未做。

```
=== per-op cost over 3000 classes (ns/op) ===
LoadFrontier.birth                 min=  240 p50=  440 max=1239926 mean=  931.5 ns
MixinTargetIndex.lookup            min=  160 p50=  240 max= 132880 mean=  350.7 ns
PendingLoadPatch.decide            min=  240 p50=  360 max=2893774 mean= 1682.8 ns
all three (current hook path)      min=  400 p50=  600 max= 683723 mean=  948.6 ns
```

读数结论（已观测）：

- 三者合并 p50 **600 ns/类**，按 3000 个类合计约 **1.8 ms**；单看 `p50`，这条热路径上的叠加
  在"每个类多花几百纳秒"的量级，不是可感知量级；
- `max` 达到毫秒级的那几次是 GC/JIT 抖动（同轮 `min`/`p50` 稳定），不能当成稳态成本；
- 该基准没有覆盖 `HierarchyTree.register`（需要真实 ASM `ClassNode`）与
  `AnnotationTransformer` 的整链，因此**不能**用它替代真机冷启动读数。

## 附：KnotServer 冷启动开/关对照（真机读数）

开关：`-Dremold.probe.load_hooks=false` 只跳过"出生事件记录"与"待决出生补丁应用"两条新逻辑，
其余（继承树注册、Mixin 三态索引）保持不变。锚点取 `RuntimeMXBean.getUptime()`，在 mod 初始化完成
（此时 Mixin 已织入、43 个 mod 全部加载）时打点。

| 轮次 | hooks 开 | hooks 关 |
|:--|--:|--:|
| 1 | 14029 ms | 14173 ms |
| 2 | 15023 ms | 14260 ms |
| 中位数 | ~14526 ms | ~14217 ms |

读数结论（已观测）：

- 开/关差异（约 0.3 s）**小于同一模式下的轮间离散度**（开：14029 → 15023，跨 0.99 s），
  因此这组读数只能支持"没有可感知量级回归"，**不能**证明亚秒级差异不存在；
- 若要分辨亚秒效应，需要更多轮次并固定世界缓存状态（本轮第二轮复用同一个 `world`，本身就是一个噪声源）。

**未覆盖**：真机懒加载阶段的掉帧/停顿未测（需要跑图脚本）；本轮只测了冷启动到 mod 初始化完成。
