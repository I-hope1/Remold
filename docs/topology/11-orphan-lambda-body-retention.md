# 孤儿 Lambda 的方法体保留（Ghost Body Retention）

> **文档类型**：设计规格（只写规格/决策；实现状态以 [`docs/status.md`](../status.md) 为准）

> 本文件是设计规格：定义"孤儿 lambda 桩"在什么条件下保留上一轮方法体、判据与不变量。
> 当前实现状态与验收证据见 [`docs/status.md`](../status.md)；相关决策与历史缺口见
> [`docs/initfix/08-test-audit.md`](../initfix/08-test-audit.md) §10.11。

---

## 1. 问题

热更删除一个 lambda 后，老 {@code CallSite} 仍按方法名调用合成方法。对齐器为此注入"幽灵桩"，
默认返回类型零值（`0 / null / false`）。对**非 `void`** 的返回值，这会把"热更没生效"升级成
级联故障：

- `Supplier.get()` / `Function.apply()` 返回 `null` → 调用方链式调用直接 `NullPointerException`；
- `ToIntFunction` / `IntSupplier` 返回 `0` → 除零、数组越界、状态机条件反转。

同时，一概"跑旧逻辑"也不对：`void` lambda（`Runnable` / `Consumer`）的旧体是用户**从源码删掉的副作用**
（扣血、刷怪、发包），保留旧体会让"删不掉的幽灵行为"在后台继续执行。

---

## 2. 语义分流

| 返回类型 | 默认行为 | 依据 |
|:--|:--|:--|
| 非 `void` | **保留上一轮方法体**（安全判据通过时），否则回退默认值桩 | 调用方依赖返回值；合法旧值 > `null/0` |
| `void` | **no-op 空壳**（现状不变） | 删掉的副作用不应继续执行；需要时用开关显式打开 |

保留体不区分"真删除"与"对齐失配 / 门禁拒配"：两种成因下，只要旧实例还在被调用，调用方期待的
都是合法返回值。判据只看"旧体还能不能安全运行"，不看孤儿成因。

---

## 3. 技术前提

### 3.1 方法体搬运（含 StackMapTable）

旧方法体从**上一轮实际生效的字节码**里读取。`LambdaAligner` 的分析期 `ClassNode` 用
`SKIP_DEBUG | SKIP_FRAMES` 读入（指纹计算只需要指令），因此保留候选存在时用
`EXPAND_FRAMES` 再解析一次，取方法体（指令 + 标签 + `StackMapTable` 帧 + 异常表）。

写入侧仍是 `COMPUTE_MAXS`（**不切 `COMPUTE_FRAMES`**：`getCommonSuperClass` 会触发目标类加载，
这是对齐器从第一天起就规避的路径）。旧帧之所以仍然有效：

- 方法名、描述符不变，初始局部变量表与旧版一致；
- 注入的前缀是栈中性的（指纹标记、`onOrphanInvoked` 调用），不改变局部变量与操作数栈；
- 类名不变，帧里的类型引用仍是同一个内部名。

帧/标签必须**重建并映射**后写入（`AbstractInsnNode#clone(Map<LabelNode, LabelNode>)`），
直接复用旧 `LabelNode` 会让异常表/帧指向另一个 `MethodNode` 的标签对象，偏移计算错误。

### 3.2 本类成员引用存活判据（`remold.OrphanBodyRetention`）

旧体是"上一轮类版本"的代码，它引用的本类成员在新版本里可能已被删除。直接在重定义时抄过去会抛
`NoSuchFieldError` / `NoSuchMethodError` / `VerifyError`。因此保留前对旧体做一次**纯静态扫描**：

- 只看指向**本类**的引用：`GETFIELD/PUTFIELD/GETSTATIC/PUTSTATIC`、`INVOKE*`、
  `invokedynamic` 引导参数里的 `Handle`、`LDC` 句柄常量；
- 引用必须满足三者之一：
  1. 新类仍声明同 `name + desc` 的成员，且静态性与旧调用点一致；
  2. 该成员会在本轮被重新注入（幽灵桩 / 捕获桥；`plannedMethodKeys`），静态性按旧声明复核；
  3. 该成员在旧类里**也没有声明**——继承成员，解析沿父链继续（类层级变更本来就会摘批）。
- 旧类声明、新类消失 → **拒绝保留**，回退默认值桩，并向 WARN 输出英文原因（按类聚合，最多列 3 条）。

判据是 fail-closed 的：读不到旧方法体、解析失败、结构缺失都按"不可保留"处理。

### 3.3 不覆盖的引用（残余风险）

**指向其它类**的成员不做存活性检查：那些类通常来自游戏/框架 jar，没有可靠的字节码来源可查，
而且"兄弟类被删"不在本次对齐的判据范围内。因此保留体若引用了"上一轮存在、本轮被删的其它类"，
仍可能在重定义校验阶段失败（`VerifyError` / `NoClassDefFoundError`）。这是**已知边界**，
不是可证明安全的放行；遇到该形态应回退到"重启"。

帧中出现的类型同样不做存在性检查，理由同上。

### 3.4 嵌套 lambda 一致性：保留体不得混跑

保留体里若通过 `invokedynamic` 句柄引用了**本类的合成 lambda 方法**，必须保证那个目标
本轮没有被"配对成新方法"（即不在幽灵计划 `plannedMethodKeys` 里、却已经存在于新类）。
否则旧实例会跑出"**旧外层体 + 新内层体**"的混跑：两层语义来自不同版本，输出既不是 v1
也不是 v2，且不抛任何异常（S23 现场形态：外层 varargs lambda 被删、内层
`AutoCloseable` lambda 被原位更新，输出 `closed-v4-v3`）。

判据取保守侧：**目标没进幽灵计划就拒绝保留**，回退默认值桩并点 named 原因
（`its nested lambda ... was updated in place`）。这里不复用
`MethodFingerprinter.isSyntheticName`——它含 `access$` 访问器桩，而访问器是直接调用目标、
不是 indy 的 lambda impl，算进来会误杀。

---

## 4. 身份识别：与"方法体形态"解耦

**幽灵身份不是"空方法 / 短方法"判据**，而是桩体里对 `LambdaAligner.onOrphanInvoked` 的调用
（见 `isGhostMethod`）。保留旧方法体后，这个调用仍在方法最前面，所以：

- 下一轮 `scan` 仍把该方法的 `SyntheticInfo.ghost` 置真，**不参与 Step 1 / Pass A / Pass B 配对**，
  也不作为 `invokedynamic` 目标被认领；
- 不需要额外的合成注解/属性来标记幽灵。方法体里可以装完整旧业务代码，身份判定只看那条调用。

与之配套的收紧：`readGhostOriginalHash` 只认"**方法体最前面**的 `LDC <long>` 且**紧跟 `POP2`**"。
旧实现只扫描前 3 条指令里的任意 long 常量；保留体后方法体自身开头的 long 常量会被误读成生前指纹，
导致 `stepGhostRevive` 拿错票据。

保留体幽灵用**三参**重载 `onOrphanInvoked(String, boolean, boolean)` 标记：
`willRunPreviousBody = true`。运行期日志、熔断栈扫描都据此区分"返回默认值"与"跑旧体"两种桩
（熔断栈定位对连续的 `onOrphanInvoked` 帧一律跳过，重载不破坏"直接调用者 = 栈上 +2 帧"的判据）。

---

## 5. 多轮搬运不变量

第 N+1 轮仍然删着这个 lambda 时，旧类里的方法已经是"保留体幽灵"。此时**原样搬运**该桩体
（连同三参标记与旧指令），不做第二次判据、也不退回默认值桩：

- 老实例全程拿到同一份旧逻辑，不会因为多热更一次就退化成 `null`；
- 产物对相同输入保持确定性（下一轮 `align(old, new)` 的产物与上一轮逐字节一致的不变量不受影响）。

判据：`hasRetainedBodyMarker` 只认三参调用；二参默认值桩/转发桩仍走原有重新判定逻辑。

---

## 6. 运行期可观测性（log-once）

对齐期不为每个孤儿发 WARN（绝大多数没有存活实例在调，纯噪音）。改为**首次真正执行**时告警一次，
按 location 去重：

```
[LambdaAligner] orphaned lambda executed on existing instances: <Class>#<method><desc>
  - running the previous method body instead of returning a default value;
  recreate the holder instance or restart to apply the new logic
  (subsequent invocations will be muted)
```

没有存活实例的死孤儿保持安静；有存活实例的活孤儿既靠旧体避免 `null/0` 崩溃，又在第一次被调用时
点名"哪个类的哪个 lambda 在走旧逻辑"。回退默认值桩的孤儿仍走原 WARN 文案。

---

## 7. 配置

| 属性 | 默认 | 含义 |
|:--|:--:|:--|
| `-Dremold.agent.orphan_retain_body` | `true` | 非 `void` 孤儿保留旧方法体 |
| `-Dremold.agent.orphan_retain_void` | `false` | `void` 孤儿也保留旧方法体 |

环境变量同名的下划线形式（`REMOLD_AGENT_ORPHAN_RETAIN_BODY`）可覆盖。测试/嵌入方用
`LambdaAligner.setOrphanBodyRetention(boolean, boolean)` 覆盖，`isOrphanBodyRetainedForNonVoid()` /
`isOrphanBodyRetainedForVoid()` 读取。

关闭开关后，已注入的保留体不会自动变回默认值桩；只有下一次该名字再次被幽灵化（即下一轮热更仍删着
这个 lambda）时，才按当时的开关重新判定（关闭时重新判定为默认值桩）。这也是"配置只在下一轮生效"的
一致性口径：运行期不追溯改写已生效的类。

---

## 8. 与孤儿熔断器的关系

`SMART_ADAPTIVE` 策略下，若直接调用者匹配已注册的 `OrphanBreakerPredicate`（UI / 定时轮询 / 事件
回调），仍**先抛 `NoSuchMethodError`**触发调用方注销回调——此时保留体不执行。熔断优先于"跑旧逻辑"：
受保护回调的语义是"这个回调已经死了，应该被摘掉"，而不是"继续跑旧逻辑"。非熔断的普通业务调用
才进入保留体。

---

## 9. 边界与未覆盖

- 跨类引用不做存活性检查（§3.3）。
- 帧中类型不做存在性检查。
- 保留体是**上一轮的字节码**：它调用的非本类方法在运行期按当前版本解析（与任何热更换版语义一致），
  不保证"逐字节回到旧行为"；它持有的类字面量/常量也来自旧版。
- 已创建的 `CallSite` 缓存无法枚举；保留体只保证"名字还在、返回合法值"，不保证业务上一定正确，
  因此首次执行的 WARN 是必须的（删掉即视为静默降级）。
- `arg` 相关：保留体不重新做参数回溯/捕获桥推导；捕获桥（`BridgeSpec`）优先级高于保留体。

---

## 10. 验收契约

实现与验收证据登记在 [`docs/status.md`](../status.md)；测试入口
`tests/src/OrphanBodyRetentionTest.java`，必须覆盖：

1. 非 `void` 删除 → 保留体执行、反射调用返回**旧逻辑的合法值**（不是 `null`）；
2. 旧体引用本类已删私有方法 → 回退默认值桩（产物无该引用、运行返回 `null`）；
3. `void` 默认 no-op；`orphan_retain_void=true` 后保留并真实执行副作用；
4. 多轮搬运：第 2、3 轮仍保留旧体（三参标记不回退）；
5. 身份：保留体仍被识别为幽灵、不出现在配对映射里；
6. 开关对照组：关闭保留后回退默认值桩；
7. 变异：关闭保留分支 → 用例 1/4/3-optin 变红；关闭存活判据 → 用例 2 变红。
