# 06 · 端到端典型场景走查

> 四个真实闭环场景：字节流、状态转移与可核对的日志读数。所有读数均为真机 `[观测]`。

## 场景 1 · 原版大类 `LivingEntity` 方法体热更

```
改 LivingEntity.java → 批次 → readOriginalBytes(磁盘 .class)
  → applySourceTransforms（PackageAccessFixer 重放）
  → MixinReWeaver.reweave（多个 Mixin 命中）
  → MixinMemberAlign（handler$* 名字回映基线）
  → AccessAlign（112 个成员权限对齐 + 自检门）
  → LayoutGate PASS → InitFix → redefineClasses
```
日志：`[DYNAMIC-REDEFINE] Successfully redefined net.minecraft.entity.LivingEntity`。
失败形态（修复前）：`VerifyError: Bad access to protected data` → `InternalError`。

## 场景 2 · 先存 `mixins.json`、后 IDEA 编译子包新 Mixin

```
保存 mixins.json（无字节）→ 待决集 pendingUnresolvedMixins（TTL + 重试预算）
Ctrl+F9 只落 out/production/classes → 批次扫到新 .class
  → NewMixinRegistrar（子包相对名 honkai.XxxMixin 准入）
  → MixinClassByteSources 四级查找（内存 → watch 目录 → 批次缓存 → 宿主 provider）
  → MixinNewMixinBytes 在 prepare() 之前灌入 + 驱逐 ClassInfo 负缓存
  → MixinBytecodeOverlay.applyToConfigs() 替换启动期缓存的 service
  → 目标重织入 + $N 合成类登记
```
修复前：`Error loading class: …Mixin$1 (ClassNotFoundException)` + `innerClass is null`。

## 场景 3 · `priority` + `Duck 接口` + `targets A→B→A` 往返

```
R1 改 priority      → Unsafe 写 final 字段 + TreeSet 摘除/重插 → reload → 全目标重织入
R2 加 Duck 接口     → ClassInfo.interfaces + 产物 implements 双向对齐
R3 targets A→B      → MixinRetarget 就地迁移四处索引（快照回滚）
R4 targets B→A      → 反向迁移；合成类按新模板重新生成并重定义
```
期间两条锤线程持续调用注入方法：`hammer-failure=null` / `window-failure=null` /
`anon-ctor-roundtrip ok=true`。修复前 R4 抛 `NoSuchMethodError: <Target>$Anonymous$<hash>.<init>(…)`。

## 场景 4 · 同会话：改捕获字段被拒 → 还原后自愈放行

```
A1~A5：每次改捕获结构（+int / int→long / +String …）
  → 闸 1 fieldTableChanged=true → gateRejected++ → blockHost → Abort → 宿主 was NOT redefined
  → newlyNull=0，不崩；Adup 同字节重发 → 增量 0/0（抑制）
B1~B5：把捕获字段还原到与已加载类一致，只改匿名类方法体
  → fieldTableChanged=false → 放行 → redefined in place; metrics: executed=1→5
  → v1-restored … v5-restored 全部在运行态生效，newlyNull=0，双通道 agree=6100 / conflict=0
```
关键点：`BLOCKED` 每轮清、抑制签名锚候选字节，两者缺一都会让 B1~B5 无法脱离 `stays aborted`。

## 判据清单（走查即验收）

1. 每个场景都必须有"修复前红 / 修复后绿"的同序列对照；
2. 读数必须落在业务终局（运行态返回值 / `newlyNull` / 双通道一致数），不得用"日志看起来对了"代替；
3. 场景 4 的两段必须在**同一会话、不重启**下完成。
