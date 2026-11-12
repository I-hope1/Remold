# 03 · 级联重织入管线与成员对齐（含墓碑保留）

> 本文给出"一次宿主类热更"的完整字节流，以及为什么**不能物理删除**基线合成成员。

## 1. 管线（顺序即契约）

```
磁盘/资源原始字节
  → readOriginalBytes(Class)                 # 未经任何 transformer 的源码级字节
  → RemoldAgent.applySourceTransforms        # 平台预变换重放：PackageAccessFixer / AccessWidener / AccessTransformer
  → MixinReWeaver.reweave                    # 交给当前 IMixinTransformer 重新织入
      → refeedForTarget                      # 目标织入后：提前生成 $N 合成内部类并回灌 BytecodeSources
  → MixinMemberAlign.align                   # Mixin 生成成员名/顺序回映基线
  → AccessAlign                              # 访问权限单调对齐 + fail-closed 自检门
  → LayoutGate / 读者扫描 / InitFix.transform
  → redefineClasses
```

各步要点：

- **`readOriginalBytes` 为什么不能用 `bytecodeCache`**：那份缓存可能已被 Mixin 织入过，再织一遍会重复注入
  `@Unique` 字段与 `handler$*`，下一轮就会被布局门判成"移除了 Mixin 字段"。优先磁盘 → 资源 → 源码缓存兜底。
- **`refeedForTarget`（S7 时序缺口）**：`InitFix` 在**同一次 redefine 内部**就要读
  `<Target>$Anonymous$<hash>.<init>` 的 protected 状态与 `NestMembers`，而合成类的字节要等它首次被加载才生成。
  这里在"目标织入完成之后、`InitFix` 扫描之前"提前生成并回灌 `core/src/remold/BytecodeSources.java`，
  生成后**还原 `InnerClassInfo.loadCounter`**（否则平台会误判"已加载"而跳过真正的 `defineClass`）。
  回灌只放宽"元数据可见性"，不放宽任何安全门：读不到字节时照旧 fail-closed。

## 2. `MixinMemberAlign`：名字与顺序回映

Mixin 每次织入都会给合成成员重新编号（`handler$zci000$…`），字节对比因此把"纯方法体热更"看成"删一个加一个"。
`core/src/remold/ref/MixinMemberAlign.java` 按

```
(前缀, $z<id>$ 之后的尾名, 描述符)
```

把新产物映射回**已加载基线**的名字，并按基线顺序重排方法表/字段表；同时用 ASM `ClassRemapper` 改写
调用点与 `InvokeDynamicInsnNode.bsmArgs` 里的 `Handle`。前缀覆盖
`handler$ / redirect$ / wrapOperation$ / modify$ / localvar$ / args$ / md$`。

**判据与范围边界（顺序即契约）**：

- 只有 **Mixin 生成/注入**的成员才构成进入对齐的条件。`javac` / JVM 自己生成的合成字段
  （枚举 `$VALUES`、内部类外部引用 `this$N`、`$assertionsDisabled`、switch-on-enum 的
  `$SwitchMap$*` / `$EnumMap$*`）不是 Mixin 标记；把它们当标记会让对齐器对普通类执行。
- **枚举类的字段表顺序不得被回映**：常量相对位置是 `ClassDiffUtil` 计算 ordinal 漂移的输入，
  在 `CHANGED_ENUM_ORDER` 判定之前把字段表恢复成基线顺序，等于让该安全门永远看不到变更。
  枚举类的方法表仍按基线顺序回映（Mixin 目标可能本身就是枚举）。
- **被删除的枚举常量不得复活成墓碑字段**：复活会让"删常量"对布局门不可见。
  方法墓碑与 `@Unique` 字段墓碑只适用于 Mixin 合成成员，不适用于 ordinal 语义的一部分。

> `[观测]` 真机采样：同一 Mixin 会话内 `$z<id>$` 的 id 稳定，因此该层在多数场景是防御性 no-op；
> 一旦 id 漂移，它就是唯一的修复点。

## 3. 墓碑保留：宿主方法桩与 `@Unique` 字段

**规则**：重织入导致基线合成成员消失时（删掉某个 `@Inject`、或 `retarget` 到别的目标），
**不得**从宿主类里物理删除这些成员，必须保留墓碑：

| 成员 | 保留形态 | 不保留的后果 |
|:--|:--|:--|
| 合成方法（`handler$*` 等） | 最小空操作桩，同名/同描述符/同访问标志/同方法表位置 | 在途 `tick()` 栈帧调用已被删除的方法 → `NoSuchMethodError` 崩服 |
| `@Unique` 字段 | 保留原字段（同描述符），新产物不再引用它 | 字段表缩容 → JBR/DCEVM `ModifyInstances` 重排全堆 |

> `[观测]` 故障 A：真机在 `retarget` 往返中出现 `handler$*` `NoSuchMethodError`，5 轮 0 复现于墓碑桩接入后
> （每轮日志 `retained 1 tombstone stub(s)`）。故障 B：字段缩容与无关类堆污染相关，见
> [`08-decisions-and-empirical-ledger.md`](08-decisions-and-empirical-ledger.md)。
> `[推断]` "墓碑桩能覆盖所有在途栈帧形态"依赖"方法表位置不变"，未做穷举。

## 4. 判据清单

1. 原始字节必须来自"未经 transformer"的来源，且必须重放平台预变换；
2. `refeedForTarget` 必须在 `InitFix` 扫描之前、且还原 `loadCounter`；
3. 基线合成方法消失时只能补桩，不能删除；
4. 基线 `@Unique` 字段消失时只能保留，不能删除；
5. 对齐器只能改 Mixin 生成成员的名字/顺序；JVM/javac 合成字段不得作为对齐判据；
6. 枚举类的常量字段表顺序必须原样保留到布局门，且被删常量不得被墓碑复活。
