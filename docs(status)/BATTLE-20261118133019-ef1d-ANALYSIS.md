# 战报 · 战役 battle-20261118133019-ef1d

> 生成时间 2026-11-18T13:57:41.254Z · 阶段 reported · 基线 feat/fix-template@6f2d4e8a0ff9 · 证据 9 条（已作废 0）

## 1. 目标
修模版的两个可用性缺陷：① mindustry-mod-template 的 ivy 仓库块与根 settings.gradle 口径不一致导致 CI 首次冷缓存离线必红（先做 H1/H2 归因再决定改哪侧）；② 模版脱离开发树时会静默回落 JitPack 同版本旧 agent jar，需要显式校验或覆盖留痕。

## 2. 批次结果
| 批次 | 标题 | 耦合 | 状态 | 提交数 | 探针(通过/终态) | 红基线 | 变异(击杀/总) | 合流 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| A | fix(template)：Mindustry 仓库块 H1/H2 归因 + agentJar 静默回落防呆 | low | merged | 4 | 5/5 | 0 | 1/1 | ✓ |

### 2.1 台账口径
- **终态探针**：`expect_exit=0` 的活跃探针，计入 `通过/终态` 分母；同一 batch+label 只有最后一条是终态。
- **红基线**：`expect_exit≠0`，只证明“期望失败确实发生”（TDD 红基线），单独计数，**不进终态分母**。
- **已作废**：`battle_invalidate` 排除；**SUPERSEDED**：被同 batch+label 的后续证据覆盖，均不计入终态。

### 2.2 结构变更摘要（git diff --numstat，客观事实）
| 批次 | 文件 | +/- | 标记 |
| --- | --- | --- | --- |
| A | CHANGELOG.md | +29 / -0 |  |
| A | gradle-plugin/src/io/github/ihope1/remold/gradle/RemoldAgentJarCheck.java | +199 / -0 |  |
| A | gradle-plugin/src/io/github/ihope1/remold/gradle/RemoldConfigResolver.java | +48 / -3 |  |
| A | gradle-plugin/test/io/github/ihope1/remold/gradle/AttachAndConfigurationCacheTes… | +20 / -0 | ⚠ 测试/构建/CI |
| A | gradle-plugin/test/io/github/ihope1/remold/gradle/BuildTriggerTestKitTest.java | +12 / -0 | ⚠ 测试/构建/CI |
| A | gradle-plugin/test/io/github/ihope1/remold/gradle/RemoldAgentJarCheckTest.java | +116 / -0 | ⚠ 测试/构建/CI |
| A | gradle-plugin/test/io/github/ihope1/remold/gradle/RemoldAgentJarGuardWiringTest.… | +97 / -0 | ⚠ 测试/构建/CI |
| A | gradle-plugin/test/io/github/ihope1/remold/gradle/RemoldPluginTest.java | +12 / -12 | ⚠ 测试/构建/CI |
| A | gradle-plugin/test/io/github/ihope1/remold/gradle/TestAgentJars.java | +86 / -0 | ⚠ 测试/构建/CI |
| A | mindustry-mod-template/AGENTS.md | +14 / -0 |  |
| A | mindustry-mod-template/README.md | +34 / -0 |  |
| A | minecraft-fabric-template/README.md | +29 / -0 |  |
| A | settings.gradle | +21 / -1 | ⚠ 测试/构建/CI |

> ⚠ 有 7 个测试/构建/CI 相关文件被改动：主指挥官应追问是否削弱了断言或门禁（diffStat 只陈述事实，不做裁决）。

## 3. 不变量证据矩阵
| 不变量 | 声明 | 覆盖证据 |
| --- | --- | --- |
| T1 | Mindustry 仓库块必须归因后再改：H1（根 settings.gradle 少第二条 artifactPattern 导致两边 Gradle 缓存标识不一致）则改根构建适配官方双模式；H2（单条模式在冷缓存在线也能拉齐）才能改模版单条，且必须带独立 GRADLE_USER_HOME 的冷缓存联网构建证据；禁止为了过本地 --offline 而危及独立用户的在线构建 | 202611181347, 202611181348, 202611181349, 202611181353, 202611181357 |
| T2 | agentJar 不得静默回落：模版/构建脚本或 RemoldConfigResolver 必须对 detached 坐标缺 v 前缀 + 同版本对账失效给出显式错误或强提示；并留痕模版脱离开发树时如何安全覆盖本地 agent jar，含“用旧 jar 跑出假绿”的防呆 | 202611181344, 202611181353, 202611181357 |

### 3.1 变异语义锚点（变异点 → 变红用例 → 不变量）
| 批次 | 变异点 | 变红用例 | 不变量 |
| --- | --- | --- | --- |
| A | gradle-plugin/src/io/github/ihope1/remold/gradle/RemoldAgentJarCheck.java:      … | RemoldAgentJarCheckTest > legacyEntryPointIsRejectedEvenWhenTheVersionMatches() …<br>org.opentest4j.AssertionFailedError at RemoldAgentJarCheckTest.java:52 | T2 |

## 4. 实证台账（压缩）
- [ev-20261118134453-f0cb] probe 批次 A · exit 0 · PASS · stdout 5b9adfd04c5e
- [mut-20261118134631-2900] mutation 批次 A · killed · 基线 0 → 变异 1 · 不变量 T2
- [ev-20261118134729-84a0] probe 批次 A · exit 0 · SUPERSEDED · stdout 0b518ce11104 · SUPERSEDED（被 ev-20261118134825-f9ea 覆盖）
- [ev-20261118134825-f9ea] probe 批次 A · exit 0 · PASS · stdout 79bfd8b56f92
- [ev-20261118134927-082d] probe 批次 A · exit 0 · PASS · stdout b2b5e83bd748
- [ev-20261118135303-9e38] probe 批次 A · exit 0 · PASS · stdout d044e5471b29
- [mrg-20261118135342-93af] merge 批次 A · OK · 86c1fd6
- [ev-20261118135345-1f94] probe 批次 A · exit 0 · PASS · stdout 743f0d1524e8
- [ev-20261118135716-9d99] probe 批次 - · exit 0 · PASS · stdout 58646c3fd95a

## 5. 机械缺口与风险 (0)
- 无

## 5.1 已接受局限 / 挂账债务 (5)
- BRIEF 的验收命令 `bash gradlew -p mindustry-mod-template help --offline` 不解析 compileClasspath，抓不住本缺陷（红 GUH 上它也 BUILD SUCCESSFUL）；真正的门禁是 `compileJava --offline`，已由批次实测确认并在 PR 描述中改用后者
- 模版侧 `compileJava --offline` 仍要求 GUH 里已有 Mindustry 缓存：首次仍需联网预热一次（文档已写明）；本轮修的是“根构建预热能对模版生效”，不是“零预热可离线”
- agentJar 校验的能力探针用常量池 UTF-8 搜索符号名（如 lastResultFor），是启发式：只会漏放（旧 jar 侥幸含同名符号）不会误杀；未做符号语义级校验
- 真机 Fabric runServer 未在本批次复跑（无 MC 运行环境），假绿链路的结论来自发布 jar 的 Manifest 实测 + 单测/wiring 用例，非端到端复现
- H2（改模版单条）被否决但保留完整证据：单条在线可拉到 jar，代价是双向预热失效且与官方模版分叉；若上游 Anuken 模版将来改成单条，本结论需重做归因

## 6. 下一步
- 可执行 battle_audit 复核；通过后 battle_seal 结案。
