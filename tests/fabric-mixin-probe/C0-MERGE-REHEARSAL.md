# C0 merge rehearsal and hygiene

- Merge rehearsal in one-off worktree from `agent/batch-a-p3-and-l0`:
  `git merge --no-commit --no-ff agent/batch-b-mixin-probe` -> automatic merge, no conflicts.
  Merged full JUnit: 444 tests, 0 skipped, 0 failures, 0 errors. Worktree removed.
- B commit stats: `8b4d6de` 12 files / 1995 insertions (probe + 9 raw logs);
  `66028f6` moves analysis under tests; `1a187f6` deletes the accidental docs file;
  `f2ff6bf` link fix. No jar/downloaded binary in B commits; largest B file is a raw
  log, not a binary.
- Real Fabric probe is NOT a root Gradle task. It is a standalone `MixinProbe.java`
  plus README/evidence, copied into a real Fabric Loom project. It does not enter
  `check` and does not affect JUnit. This is a C0 gap versus the requested "explicit
  Gradle task"; adding a fake root task was rejected.
