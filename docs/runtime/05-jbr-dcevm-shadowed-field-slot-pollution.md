# JBR DCEVM 同名遮蔽字段的槽位污染（纯 JBR 对照实测）

> 文档类型：实测记录。本文只记录读数与复现方式；实现状态与残余风险以 [`docs/status.md`](../status.md) 为准。

## 要回答的问题

子类声明一个与父类**同名同描述符**的字段（字段遮蔽）后，经过一轮结构性重定义（子类新增字段），
**重定义之前就存在的实例**上，父类字段的槽位里会读到子类的值。这是 Remold 的补丁造成的，还是
JBR DCEVM 自身的行为？

## 结论

**上游 JBR DCEVM 行为，与 Remold 无关。** 复现环境里没有任何 Remold 代码：agent jar 只含一个
`Instrumentation` 载体类，每个版本只调用一次 `Instrumentation.redefineClasses`（单阶段，不是 Remold 的
Stage A/C 两阶段）。

- **已观测**：污染出现在「重定义之前创建、且子类本轮新增了字段」的实例上；同一批里重定义之后新建的
  实例完全正常。
- **已观测**：父类槽位（`Base.x`，offset 12）**内容本身**被改写为子类值，因此父类方法体、外部
  `getfield`、反射 `Field`、`Unsafe` 直读四条路径读数一致——不是常量池/解析路径差异。
- **已观测**：删掉同名条件（把子类字段改名 `sx`）后，relayout 照常发生（`Leaf.l` 偏移 32 → 36），
  但父类槽位保持 10，不再污染。同字节码重复重定义（无新增字段）也不污染。
- **推断**（未读 JBR 源码）：迁移过程按**字段名**而非声明位置搬运值，名字解析落到最派生的声明上，
  于是子类的值被写进所有同名槽位。

## 复现方式

```bash
cd tests/jbr-dcevm-shadowed-field
./build.sh          # 最小 agent jar + 三个版本的类目录 + 两个 harness
./run.sh v12        # V1 -> V2（自定义 loader）
./run.sh v123       # V1 -> V2 -> V3
./run.sh repeat     # 同字节码重复重定义（无新增字段的对照）
./run-sys.sh v123   # 系统 loader，带类型的 ((Base) inst).x 读法
./run-sys.sh v123an # 子类字段改名为 sx 的对照
```

底层命令：

```bash
/opt/idea/jbr/bin/java -XX:+AllowEnhancedClassRedefinition \
  -javaagent:out/agent.jar -cp out/harness Harness "$PWD" v123
```

## 读数（JBR 21.0.8+9-1163.69，本机 Linux x86_64）

实例分组：`c1` = V1 时创建；`c2` = V2 之后创建；`c3` = V3 之后创建。V1→V2→V3 一轮跑完。

| 读数 | c1 | c2 | c3 |
|:--|:--|:--|:--|
| `((Base) inst).x`（mid / leaf） | 10 / 10 | **200 / 300** | 10 / 10 |
| `Base` 方法体 `baseGetX()` | 10 / 10 | **200 / 300** | 10 / 10 |
| 匿名子类 `((Base) inst).x` | 10 | **300** | 10 |
| 裸 `Base` 实例 | 10 | 10 | 10 |
| `Base.x` 槽位直读（offset 12） | 10 / 10 | **200 / 300** | 10 / 10 |
| 同实例 `Mid.m` / `Leaf.l` | 20 / 30 | 20 / 30（保留） | 20 / 30 |

对照组：

| 对照 | 读数 |
|:--|:--|
| `V1 → V2` 单阶段 | 父类读数无差异；但 c1 实例上 `Mid.x` 新槽位 = 10（等于 `Base.x`），说明迁移在 V2 就已发生，只是拷进去的值恰好与父类相同 |
| 无新增字段的重复重定义 | 父类读数 10 / 10，槽位无污染 ⇒ 需要子类本轮新增字段 |
| 子类字段改名 `sx` | relayout 照常（`Leaf.l` 32 → 36、`Mid.sx@24`，`m`/`l` 值保留），`((Base) inst).x` 保持 10 ⇒ 只在同名时污染 |

## 对 Remold 的影响

- InitFix 对「新增的遮蔽字段」按新初值写入子类槽位，这是正确的、且必须保留（否则子类槽位停在迁移
  复制进来的祖先值上）。相关机制见 [`docs/status.md`](../status.md) §4。
- **父类槽位被污染这件事，Remold 侧不修正**：它的成因在 relayout，不在补丁。命中遮蔽字段时 InitFix 会
  打一条 `[SHADOW-FIELD]` 告警，直接建议用户避免跨热更边界的同名遮蔽或改名字段。
- 因此「快照父槽再回填」这类补救属于修改已有字段值，按 [`AGENTS.md`](../../AGENTS.md) §1 需要显式通道，
  当前**未实现**、也不在计划内。

## 未排除

- 未模拟 Remold 的两阶段 Stage A/C 顺序（本文只做单阶段 `redefineClasses`）。
- 机制解释是从上述三组观测推出的**推断**，未读 JBR 源码。
- 未覆盖：其它 JBR 版本、其它平台、实例经由父加载器创建、`Base` 自身也参与重定义的形态。
- 可作为上游 Issue 的最小 repro：`tests/jbr-dcevm-shadowed-field/`（不含任何 Remold 代码）。
