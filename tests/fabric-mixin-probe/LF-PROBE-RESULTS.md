# Load-frontier / mixin-target-index probe: raw readings

Environment: Fabric 1.21.1, loader 0.16.9, sponge-mixin 0.15.4+mixin.0.8.7, JBR 21.0.8,
KnotServer launched from a scratch Loom project (`../../../minecraft-fabric-template` copy,
Loom 1.17.21), Remold agent + plugin-mixin 0.2.8 built from this worktree.

Raw log: [`evidence/lf-target-index-run1.txt`](evidence/lf-target-index-run1.txt) (330 lines, unfiltered
`[LF-PROBE]` lines). Driver: [`lf/run-lf.sh`](lf/run-lf.sh).

## Window 1 — mod init (no hot-swap batch has run yet)

```
[INDEX window1] available=false size=0 stamp=0
[CONFIG window1] processor=MixinProcessor configsType=java.util.ArrayList configsSize=32
```

The index is `UNKNOWN` while 32 configs are already assembled. This is the window the ready-gate
publish exists for: a birth patch registered before the first batch would have no target data.

## Window 2 — after `MixinHotswapPlugin.isHostReady()`

```
[INDEX window2-before-ready-gate] available=false size=0 stamp=0
[INDEX window2-after-ready-gate] available=true size=162 stamp=1
```

The production ready gate flips it: `available=true`, 162 targets, `stamp=1` (one publish).

## Three-way key forms and differences

| Source | Raw key form | Example |
|:--|:--|:--|
| `mixinMapping.keySet()` | dotted | `com.example.item.RemoldWandItem` |
| `unhandledTargets` | dotted | `com.example.probe.StringTarget` |
| `config.mixins -> info.getTargetClasses()` | **slash** | `com/example/item/RemoldWandItem` |

Differences (all normalized to slash names before comparing):

```
[TABLE] inTargetsOnly=[]
[TABLE] inMappingOnly=[]
[TABLE] inUnhandledOnly=[]
[TABLE] classTargetPresent=true stringTargetPresent=true
```

So on this runtime `mixinMapping ∪ unhandledTargets` already covers every
`getTargetClasses()` entry; the third source adds nothing. `StringTarget`
(`@Mixin(targets = "com.example.probe.StringTarget")`) is present in both
`mixinMapping` and `unhandledTargets`, so string-form targets are covered the same way as
class-literal targets.

## Lookups after the gate

```
[LOOKUP] com/example/item/RemoldWandItem -> TARGET
[LOOKUP] com/example/probe/StringTarget -> TARGET
[LOOKUP] com/example/ExampleMod -> NOT_TARGET
```

## What this does and does not establish

Observed: the two-form key mismatch is real (dotted mapping/unhandled vs slash
`getTargetClasses`), the target index is `UNKNOWN` until something publishes, the ready gate
publishes correctly, and the two-source union is complete for this mod set.

Not established: coverage for configs added after startup without a `mixins.json` change
(the probe publishes once at the gate), and behaviour when `mixinMapping` is populated lazily
per environment — both need a second run with a hot-added Mixin.
