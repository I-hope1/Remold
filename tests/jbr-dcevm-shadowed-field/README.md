# JBR DCEVM 同名遮蔽字段槽位污染 —— 最小复现工程

本目录**不含任何 Remold 代码**，只用一个 `Instrumentation` 载体类 + `Instrumentation.redefineClasses`
复现上游 JBR DCEVM 行为：

- 子类声明与父类**同名同描述符**的字段后，下一轮**结构性**重定义（子类新增字段）会把最派生声明的值
  按字段名写进同名祖先槽位；
- **重定义之前就存在**的实例上，父类字段槽位里读到的是子类的值；重定义之后新建的实例正常。

读数表、对照组与"对 Remold 的影响"见
[`docs/runtime/05-jbr-dcevm-shadowed-field-slot-pollution.md`](../../docs/runtime/05-jbr-dcevm-shadowed-field-slot-pollution.md)。

## 用法

```bash
./build.sh          # 最小 agent jar + 版本类目录 + 两个 harness
./run.sh v12        # V1 -> V2
./run.sh v123       # V1 -> V2 -> V3
./run.sh repeat     # 同字节码重复重定义（无新增字段对照）
./run-sys.sh v123   # 系统 loader，带类型的 ((Base) inst).x 读法
./run-sys.sh v123an # 子类字段改名 sx 的对照
```

需要 `-XX:+AllowEnhancedClassRedefinition`，脚本默认用 `/opt/idea/jbr/bin/java`（JBR 21）。
构建产物落在本目录 `out/`、`classes/`，均不入库。
