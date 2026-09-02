# Plan: the proto becomes kyo-kernel

Scope: the `kyo-kernel` module only. Other modules keep compiling against the old surface
until their own migration; from the first phase below onward, everything downstream of
kyo-kernel is red on this branch by design, and the gates are the kernel's own suites,
benches, and docs.

## Is it just moving sources?

No. Moving is the smallest part. Four other things have to happen for the result to be
the kernel rather than a renamed prototype:

1. **The old kernel goes away in the same step**, not later: `kyo.kernel.<` and
   `kyo.proto.kernel.<` cannot both exist in package `kyo`, so the delete and the move
   land in one commit, or the module does not compile in between.
2. **The public surface has real differences**, not just package names. The proto's
   `ContextEffect`, `Isolate`, and bracket protocols are not the old ones, `Effect.catching`
   and `handleCatching` have no proto counterpart, `Mask` moved into `ArrowEffect`, and
   `Loop` grew `Done`. Each is a decision the kernel has to settle before the module is
   the kernel, because downstream migrates against whatever this step ships.
3. **The old kernel's docs are the kernel's.** The README and CONTRIBUTING describe the old
   node ADT and stack, and the README is doctested against the old API. The tests and
   benches are already the proto's: every old suite has its proto twin, and the 30-row
   `KernelBench` set is in `ProtoBench` under the same row names.
4. **Scaffolding that exists only because the proto was a prototype** goes: the
   `protodemo` runner and its JOL dependency, `kyo/mini/mini.scala`, `ProtoKernelBench`,
   the `outsidekyo/ProtoKernelTest` twin.
5. **The proto has no scaladoc**, by the ruling that stripped it. The old kernel has 70
   scaladoc blocks on the five public files alone. As the kernel, the public types fall
   under the repository rule that public types carry scaladoc. Decision D1 below.

## Inventory

| area | old kernel (`kyo.kernel`, `kyo.*`) | proto (`kyo.proto.*`) |
|---|---|---|
| shared main | 16 files under `kyo/kernel`, plus `kyo/Arrow.scala`, `kyo/Kyo.scala`, `kyo/Mask.scala`, `kyo/Closed.scala`, `kyo/kernel.scala` | 19 files: `kyo/proto/{Arrow,Kyo,Loop,kernel}.scala`, `kyo/proto/kernel/{ArrowEffect,ContextEffect,Effect,Isolate,Pending}.scala`, `kyo/proto/kernel/internal/*` (10) |
| jvm-native main | `Safepoint`, `StackPlatformSpecific`, `DebuggerPlatformSpecific` | `Safepoint`, `Report` |
| js-wasm main | same three | `Safepoint`, `Report` |
| jvm main | none | `kyo/proto/debug/{ConsoleDebugger,DebugSession}.scala`, `protodemo/{Main,SuspensionDebug}.scala` |
| internal files only on one side | `Finalizer.scala`, the two `*PlatformSpecific` | `Context.scala`, `Report.scala` |
| shared tests | 15 under `kyo/kernel`, 5 under `kyo/` (Arrow, Kyo, KyoForeach, KyoForeachColl, Mask), `outsidekyo/KernelTest`, `kyo/Test.scala`, `kyo/TestVariant.scala` | 21 under `kyo/proto/kernel` (StackTest, HandlerTest, DebuggerTest included), 5 under `kyo/proto/` (Arrow, Kyo, KyoForeach, KyoForeachColl, Loop), `outsidekyo/ProtoKernelTest` |
| jvm-native tests | 10, plus the `SafepointStop` helper | 10, the stop request inlined where a suite needs it |
| js-wasm tests | 1 | 1 |
| jvm tests | 3 | 4 |
| old test files with no proto twin | none: MaskTest's cases live in `ArrowEffectMaskTest` | |
| proto test files with no old twin | | `EffectBracketTest`, `EvalConcurrencyTest`, `ReportTest`, `LoopTest`, `ArrowEffectMaskTest` |
| benches (`jvm/src/jmh`) | `KernelBench` (30 rows), `ProtoKernelBench` (20-row twin, measures `kyo.kernel`), `cross/{CatsEffect,Turbolift,Zio,ZioBlocks}Bench` joined to KernelBench by row name | `ProtoBench` (35 rows: the 20 proto rows plus the 15 KernelBench rows, same names) |
| docs | `README.md` (695 lines, doctested), `CONTRIBUTING.md` (432 lines) describe the old kernel | none |
| build | `kyo-kernel` block: scalatest, javassist for bytecode pins, JOL plus fork settings for the proto demo, Jmh settings | |
| skills | `kyo-kernel/.claude/skills/kernel`: `package-check.sh` examples name `kyo/proto/kernel`; the bench harness has proto run files | |

Downstream use of surface the proto does not have, counted in files outside kyo-kernel
(these are not migrated in this plan; they size what the surface decisions affect):
`Isolate.` 10, `onFork`/`onJoin`/`onRelease` 5, `ContextEffect.` 3, `handleCatching` 1,
`Effect.catching` 1, `finalizeResources` 1, `kyo.Mask` 1. `Loop.continue`/`Loop.done` 184
and `Kyo.lift` 43 are unchanged in shape.

## Decisions to rule before phase 1

- **D1. Scaladoc.** The proto carries none by ruling. The repository rule is that public
  types carry scaladoc. Options: (a) restore scaladoc on the public files only
  (`ArrowEffect`, `ContextEffect`, `Effect`, `Isolate`, `Pending`, `Arrow`, `Loop`, `Kyo`),
  written for the proto's semantics, with internals staying comment-free; (b) ship without
  and amend the rule for the kernel. Recommendation: (a), done as its own phase after the
  move so the diff is readable.
- **D2. `Effect.catching` and `ArrowEffect.handleCatching`.** No proto counterpart; one
  downstream user each. Options: port to the proto's shape as a cold construct over
  `handleCont`'s recover arm, or drop and let the downstream site move to the recover arm.
  Recommendation: drop; the recover arm is the proto's expression of the same law, and the
  backlog already records the catching cases as divergent by design.
- **D3. `Pending.finalizeResources`.** The old finalizer lane; the proto's counterpart is
  `Effect.bracket` and the owed dumps. Recommendation: drop, with the one downstream site
  moving to bracket when its module migrates.
- **D4. `ContextEffect` and `Isolate` protocols.** The proto's `derive`/`fork`/`join`/
  `release`/`reenter`/`done` and the single `cont` isolate are the kernel's protocols from
  this step; the old `bound`/`onFork`/`onJoin`/`onRelease` and `fork`/`join`/`resume`/
  `updates` are gone. This is already ruled by the proto being the kernel; listed so the
  downstream count (18 files) is visible.
- **D5. `Mask`.** Stays inside `ArrowEffect` as ruled ("Mask is in ArrowEffect");
  `kyo/Mask.scala` is deleted and the one downstream import changes when it migrates.
- **D6. The debug package.** `kyo.proto.debug` (jvm: `ConsoleDebugger`, `DebugSession`)
  becomes `kyo.kernel.debug`; the `protodemo` runner and the JOL dependency go. Whether
  `Debugger.enabled` stays a compile-time constant (backlog Q1) is independent of the move;
  DebuggerTest pins the law under either value.

## Phases

Each phase ends green on the kernel's own gates and is one commit. The tree does not
compile between phase 1's delete and move, so they are one operation.

### Phase 1. Delete the old kernel and move the proto into its place

Deletions:
- `shared/src/main/scala/kyo/kernel/**`, `jvm-native/.../kyo/kernel/**`, `js-wasm/.../kyo/kernel/**`
- `shared/src/main/scala/kyo/{Arrow,Kyo,Mask}.scala`, `kyo/mini/mini.scala`
- `jvm/src/main/scala/protodemo/**`
- old tests: `shared/src/test/scala/kyo/kernel/**`, `jvm-native/.../kyo/kernel/**`,
  `js-wasm/.../kyo/kernel/**`, `jvm/.../kyo/kernel/**`, `shared/src/test/scala/kyo/{Arrow,Kyo,KyoForeach,KyoForeachColl,Mask}Test.scala`,
  `outsidekyo/KernelTest.scala`
- benches: `KernelBench.scala`, `ProtoKernelBench.scala`

Moves, with `git mv` so history follows:
- `kyo/proto/kernel/**` to `kyo/kernel/**` (shared, jvm-native, js-wasm), package
  `kyo.proto.kernel` to `kyo.kernel`, `kyo.proto.kernel.internal` to `kyo.kernel.internal`
- `kyo/proto/Arrow.scala` to `kyo/Arrow.scala`, `kyo/proto/Kyo.scala` to `kyo/Kyo.scala`,
  package `kyo`
- `kyo/proto/Loop.scala` to `kyo/kernel/Loop.scala`, package `kyo.kernel`, matching the old
  placement and the `val Loop = kernel.Loop` alias
- `kyo/proto/kernel.scala` folds into `kyo/kernel.scala`: the old file's aliases stay
  (`<`, `Loop`, `Isolate`, `Id`, `Const`), pointing at the moved definitions
- `jvm/.../kyo/proto/debug/**` to `kyo/kernel/debug/**`
- benches: `ProtoBench.scala` to `kyo/kernel/bench/KernelBench.scala`, class `KernelBench`,
  so the cross benches join by name unchanged
- tests: `kyo/proto/kernel/**` to `kyo/kernel/**` on every platform dir, `kyo/proto/*Test`
  to `kyo/*Test`, `outsidekyo/ProtoKernelTest` to `outsidekyo/KernelTest`; the bytecode
  pins keep their proto sizes
- every `import kyo.proto.*` inside the module rewritten; `Tag` shows and any string that
  names the package (`EffectTrace.isPlumbing` filters `kyo.proto.`) updated

Build: drop the JOL dependency and the fork/attach `run` settings; keep javassist and
scalatest; the comment about the demo goes. `package-check.sh`'s example paths updated.

Gate: `sbt --batch kyo-kernelJVM/clean kyo-kernelJVM/compile` (the clean batch build is
the one that catches the lift macro's suspension cascade), then the full suites on JVM,
JS, and Native, then `Jmh/run -f 1 kyo.kernel.bench.KernelBench` against today's
35-row board with no row outside its error band.

### Phase 2. Surface parity, per the rulings

- Apply D2, D3, D5 as deletions or ports; each port arrives with its pins.
- The test base: the proto suites extend `AnyFreeSpec` directly; the old suites extend
  `kyo.Test`, which exists for a documented reason (the kernel cannot test on kyo-test).
  The moved suites switch to `kyo.Test`; `TestVariant` stays for `KyoForeachCollTest`.

Gate: suites on three platforms; the 35-row board at 1 fork against phase 1's board.

### Phase 3. Scaladoc (D1)

Public files only, written for the proto's semantics, checked by `sbt kyo-kernelJVM/doc`
and the doctest driver. Internals stay comment-free.

### Phase 4. Docs and skills

- `README.md`: rewrite through `/readme kyo-kernel`; every fenced block is doctested, and
  the current README's blocks use the old API, so the rewrite is not optional.
- `CONTRIBUTING.md`: rewrite for the proto's representation (the pending union, the
  Defer/Suspend/Handle/Park/Snapshot nodes, the pooled stack with owed dumps, brackets,
  the trace's cold attaches and the ruling that the dispatch attaches nothing).
- `.claude/skills/kernel`: `package-check.sh` and `SKILL.md` references to the proto
  package and to `ProtoKernelBench`; the bench harness's recorded runs keep their names as
  history.
- `reviews/proto-migration/backlog.md` gains a closing entry pointing at this plan and the
  commits.

### Phase 5. Downstream, out of scope here

Recorded so the branch state is explicit: 71 files in 12 modules import `kyo.kernel`;
their migration is its own plan, starting with `kyo-prelude` (14 files) and `kyo-core`
(10), where the `Isolate` and `ContextEffect` protocol changes land.

## Order and dependencies

Phases 2 and 3 are independent of each other; phase 4 depends on 2 and 3 because the
README's blocks and the CONTRIBUTING's surface table describe the final API. Nothing here
touches other modules.
