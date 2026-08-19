# Kernel session handoff (2026-08-19)

You are resuming the kyo kernel prototype work in this worktree
(`/Users/fwbrasil/workspace/kyo/.claude/worktrees/effervescent-painting-backus`). This file is
self-contained; read it fully, then WORK.md's OPEN section (the ledger, the only authority on
state). A separate session runs the harness design program in `~/workspace/kyo-substrate`; do
not touch that repo or its agents.

## First acts, in order

1. `git config user.email` must resolve to fwbrasil@gmail.com; if it shows the CI bot
   (getkyoci), commit with `git -c user.name='Flavio Brasil' -c user.email='fwbrasil@gmail.com'`.
   Never commit as the bot. No attribution lines, no PR interaction of any kind, ever.
2. The owner's kernel WIP is UNCOMMITTED: 16 modified files plus untracked
   `kyo-kernel2/shared/src/main/scala/kyo/proto/Handler.scala` and
   `kyo/kernel/internal/KyoInternal.scala`. Preservation before anything else: ask the owner
   whether they commit it or you do (a WIP commit on this branch is cheap and reversible;
   uncommitted work is the one unrecoverable state).

## Where the code stands (verified)

- The prototype lives in `kyo-kernel2/shared/src/main/scala/kyo/proto/`: `Pending.scala`
  (opaque `<` union), `Arrow.scala` (Arrow as function with head/tail, Chain flattening),
  `Kyo.scala` (Defer/Suspend/Handle), `Handler.scala` (HandlerCont/HandlerLoop/
  HandlerLoopState), `Stack.scala` (ring buffer + parallel `states: Array[Maybe[Any]]`),
  `Eval.scala` (tail-recursive trampoline), `Safepoint.scala` (budget + preemption).
- `kyo.proto.PendingTest`: 38/38 green (last verified run; the long-map-tower passes bounded,
  ~42s, a known perf item not a correctness one). Run it with:
  `export JAVA_OPTS="-Xms3G -Xmx4G -Xss10M -XX:MaxMetaspaceSize=512M -XX:ReservedCodeCacheSize=128M -Dfile.encoding=UTF-8"; export JVM_OPTS="$JAVA_OPTS"; sbt 'kyo-kernel2JVM/testOnly kyo.proto.PendingTest'`
- Six owner-greenlit HandleLoopState fixes are applied in the working tree: Eval's Continue2
  arm resumes with `r._2` (state stored via `putState`); `states` pre-filled/reset with
  `Absent` (null is not a valid Maybe); `state`/`putState` use masked indexing
  `(head + i) & mask`; `ensure` carries `states` in lockstep on growth; vacate-clears in
  `pop`/`truncate`/`dump` with `put` KEPT as the single entry-write funnel (owner ruling:
  "you can't remove put! it's the central place to ensure tracking of state"); the Eval done
  branch reads `state(0)` before popping and completes a popped HandlerLoopState via
  `apply(state, r)` directly.

## The two held items (homework done, owner-authorized direction, elaborate-then-apply)

1. **Uncomment `ArrowEffect.handleLoopState` / `handleLoopStateWith`** (ArrowEffect.scala,
   two commented bodies). Rename pass: `def v` -> `value`, `complete` -> `apply`, add
   `def frame = _frame`; `initialState` from the `state` param. The anonymous class must also
   satisfy `Arrow.Transform`'s abstract one-arg `apply(v: A): B < S`; the prepared proposal is
   a single `final def apply(v: A): B < S = bug(...)` on `HandlerLoopState` itself (unreachable
   on live paths: Eval's done branch matches HandlerLoopState before any one-arg apply runs).
   Then enable the pinning test at `PendingTest:194` (`handleLoopState(Tag[Give], 0, give)`).
2. **The capture gap** (open by design, owner may rule leave-open): `Stack.dump(pos)` crossing
   a stateful region chains the raw handler into the continuation and the slot-keyed state
   stays behind; re-push falls back to `initialState`. Prepared candidate in the existing
   vocabulary: at capture, re-materialize the crossed HandlerLoopState with
   `initialState` = its live slot state, so `put` re-initializes the region on re-push; no new
   node class, no Eval or push changes.

## Open issues beyond the held items

- Full `kyo-kernel2` suite UNVERIFIED since the rewrite: only PendingTest has run;
  ArrowEffectTest/EvalTest current state unknown. A clean full-module JVM run is the standing
  goal, then JS/Native if the module builds them.
- Test corpus: restore ContextEffectTest (commented), enable KyoTest/KyoForeachTest/
  KyoForeachCollTest, ArrowEffectBytecodeTest; commented blocks in EffectTest/EffectTraceTest/
  PendingBytecodeTest to re-audit. Delete the scratch orphan
  `shared/src/test/scala/kyo/proto/OverflowProbe.scala` before the proto work is called done.
- Perf: the dump->push round trip is quadratic under budget exhaustion (the ~42s tower).
- `Outcome`/`Outcome2` are unboxed unions: a done value that is itself a Continue misclassifies
  (accepted edge, worth a note if the surface goes public).
- No `Eval.partial`/preemption in the proto; the corpus cases needing it are commented.
- `Safepoint.scala:8` carries the owner's TODO (`should be private[kernel]`).
- Bench stream: PARKED by owner order. The task-37 kernel-vs-proto bracket stays gated on a
  committed green sha plus a quiet machine (load under 5, no builds); the staged command and
  all bench state are in WORK.md. Do not run measurements alongside builds or agents.

## How the owner works (binding, learned the hard way; full corpus in INTERACTIONS.md)

- Proposals one at a time, in chat, elaboration with code snippets FIRST, then the Edit tool
  (never bash edits); the owner watches and interrupts. No edits or sbt runs on their WIP
  without their go.
- No comments in kernel code, no new terminology, no helper methods without necessity, no new
  concept-nouns (their vocabulary: reuse `apply`, `head`/`tail`, `push`/`pop`; a new noun in
  your draft is a stop sign). Safety and static typing are the bar; casts localized with named
  abstract type members + `@unchecked` in Eval's style. `@tailrec def loop` over while/var.
- Deletion is never a rider on a fix: propose subtractions separately, naming what concern the
  deleted thing homes.
- Watch every long-running command's log; never assert what you inferred (state provenance:
  ran/read/derived); numbers only from real runs; "unverified" is an honest label, use it.
- A failing state is committed (preservation), never left as uncommitted tree state; what is
  gated is pushing/PRs, never local commits on the working branch.

## Reference material

- `WORK.md` (this worktree): the ledger; OPEN section leads with the resume anchor.
- `INTERACTIONS.md` (this worktree): the interaction corpus, failure classes, owner vocabulary.
- `~/workspace/kyo-substrate/harness-design/ledger/merged.md` Part 3: 1,130 verbatim
  kernel-campaign owner rulings mined from the full transcript, greppable (fusion, Safepoint,
  handlers, process rules) with turn references. Read-only from this session.
- `scripts/ci-logs.sh` for any CI inspection (never raw `gh run view --log`).
