# 02 · 运行期新 Mixin 准入、覆盖层与异步接力

> 目标：**在 IDEA 里改一个 Mixin 源文件（不重启、不双写输出目录、甚至 `mixins.json` 先于 `.class` 落地）**，
> 新 Mixin 与它的 `$N` 内部类都必须能被 Mixin 自己"看见"。

## 1. 准入：`NewMixinRegistrar`

`core/src/remold/reflect/mixin/NewMixinRegistrar.java` 负责把运行期新出现的 Mixin 类登记进 Mixin 的配置索引。

- **多层子包相对名**：`honkai.XxxMixin`、`client.hud.YyyMixin` 这类相对名按"包名后缀"匹配所属 `mixins.json`，
  不要求全限定名；
- **墓碑自愈**：配置里被删掉的 Mixin 先记 tombstone，若之后同名类重新出现（且点分后缀仍匹配）则复活，
  不重复登记；
- **不直接依赖 Mixin**：core 只经反射（`MixinReflection`）触碰 Mixin 类型，插件侧传方法引用跨边界，
  避免 core 硬链接可选类型在链接期抛 `NoClassDefFoundError`。

## 2. 字节来源：四级查找链

`MixinClassByteSources`（插件侧）按顺序回答"这个内部名的字节在哪"：

| 级 | 来源 | 典型场景 |
|:--|:--|:--|
| 1 | 已登记的内存字节（本会话登记/生成过的） | 新 Mixin 首次 reload 后的 `$N` |
| 2 | **全部 watch 目录**的最新磁盘字节 | 纯 IDEA 编译（`out/production/classes`） |
| 3 | 上一次批次的源码缓存 | 文件已被 IDE 清理但仍在本轮批次内 |
| 4 | 宿主 `IClassBytecodeProvider` 回落 | 原版/只读类 |

`MixinNewMixinBytes` 在 `MixinConfig.prepare()` **之前**把这些字节灌进覆盖层，并驱逐上游 `ClassInfo`
的**负缓存**（`ClassInfo.cache` 里的 `null` 条目）——上游对失败的 `forName` 会缓存 `null` 且永不重试，
不驱逐则新 Mixin 永远"看不见"。

## 3. 覆盖层：`MixinBytecodeOverlay`

- 安装 `IMixinService` 代理：只对**显式登记**的内部名提供字节，其余一律回落宿主 provider（不做全局替换，
  避免影响原版类解析）；
- `applyToConfigs()` 把每个 `MixinConfig` **启动期捕获**的 `IMixinService`（非 final 字段）也换成服务代理。
  这一条专治纯 IDEA 场景：`MixinConfig` 在启动时缓存了 service，只换全局单例对已存在的 config 无效，
  于是 `$1` 仍然 `ClassNotFoundException`；
- 登记成功后必须驱逐上游 `ClassInfo` 负缓存（见 §2）。

## 4. 异步接力：`pendingUnresolvedMixins`

时序倒挂是真实工作流的常态：用户先保存 `mixins.json`，几秒后才按 Ctrl+F9 编译出 `.class`。
`MixinConfigDiffer` 因此区分两种"未就绪"：

- **pending**：配置里声明了、字节还没到 → 记进 `pendingUnresolvedMixins`，带 TTL 与重试预算；
- **abandoned**：名字被从配置里删掉 → 立即放弃（不再重试）。

`retryPending()` 在每次批次末尾推进一次；命中即完成登记与目标重织入，超预算则点名放弃。

**缺少任一环的失败形态**：只落 `out/production/classes` 的新 Mixin → `Error loading class: … (ClassNotFoundException)`，
随后 `innerClass is null`；`mixins.json` 与 `.class` 到达顺序倒挂 → 该 Mixin 永久不可见（上游 `ClassInfo` 的
`null` 负缓存不会重试）。落地与验证记录见 [`docs/status.md`](../status.md)。

## 5. 判据清单

1. 新 Mixin 的字节来源必须覆盖"只在 IDE 输出目录里"这一形态；
2. 不得要求用户把产物双写到 `build/classes`；
3. `mixins.json` 与 `.class` 的到达顺序不得影响最终结果（待决集负责）；
4. 覆盖层只回答**显式登记**的名字，其余回落宿主。
