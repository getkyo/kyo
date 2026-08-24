# Resume anchor: kernel2 session (post-compaction)

Written at commit `7f9ff61ad2` for a fresh context. Everything below is committed; nothing lives
only in the tree or in scratch.

## Standing working rules (user-set, binding)

- Edit/Write tools ONLY for file changes; never sed/python/heredoc edits. This slipped twice and
  was called out; hold the line.
- `sbt --client` for all builds in this tree (a one-shot `sbt` per round was called out as too
  slow; the server is already running). Never `sbt --batch`. Bench runs stay one-shot in the
  worktree `/Users/fwbrasil/workspace/kyo-bench-wt` under the mutex `/tmp/kyo-bench-mutex`; never
  compile in this tree while a bench runs.
- Commit as you go (commit = preservation, not a green claim; say the state in the message).
  Identity: `git -c user.name='Flavio Brasil' -c user.email='fwbrasil@gmail.com'`.
- Scope set by the user: (1) finalize debugger validation, (2) kyo-prelude MAIN sources compiling
  against kyo-kernel2. No tests compiles/runs for prelude (they need kyo-test -> kyo-core, later
  ladder). Do not touch kyo-core. Do not introduce indirections/workarounds for inference issues;
  fix them in kernel signatures (user was emphatic; `Loop.answer` was vetoed and deleted).
- Always report bench results as complete tables, time AND alloc, with the emoji conventions.

## The inference fix (validated, mid-rollout)

Root cause: answer positions in loop-family clauses are pending-typed (`O[C] < (E & S)`) and the
clause result's trailing `< S` is a conversion boundary; Scala types the `Loop.continue` args
before conversion search and never propagates the expected type, so raw answers minimize and fail.
Old kernel never hit it because cont-style clauses always passed `cont(...)` (already pending).
NOT an inline issue (ascribed args compile under the same inline).

The fix (user-approved path): clause result type becomes the union
`Outcome2[...] | (Outcome2[...] < S)` — the conversion-free left branch propagates the expected
answer type, raw answers lift ARGUMENT-side (CanLift-guarded, so boxed answers still Nested-wrap:
nest-once holds, T9 stays sound). Kernel consumes both branches as one representation via a single
categorized cast per consumption point ("a raw outcome is the lifted union's first arm").
Prerequisite already in: Continue/Continue2/3/4 and Outcome/2/3/4 fully covariant.

DONE: `handleLoopState` (canonical overload) + `Handler.answerStepState` + `Handler.answersLoopState`
params and casts + the `run` override cast in handleLoopState's expansion. VALIDATED: Check:92 raw
`()` compiles.

REMAINING rollout (mechanical, mirror the done ones):
1. ArrowEffect.scala: stateless `handleLoop` (canonical at ~113 and done-less at ~149) clause
   types -> union; done-less `handleLoopState` overload (~176-ish after edits); `handleLoopWith`;
   `handleLoopStateWith` (both have their own handle params with `< S`).
2. Handler.scala: `answerStep` + `answersLoop` params -> union + consumption casts (answerStepState
   and answersLoopState are the templates already done; mirror exactly).
3. The stateless `HandlerLoop` run override in handleLoop's expansion gets the same cast as
   handleLoopState's run override got.
4. Class-level `HandlerLoop.run` / `HandlerLoopState.run` signatures deliberately KEEP `< S`
   (generic path); the union lives only at the user-facing inline parameters.
5. THEN revert prelude workarounds to plain raw code: every `Loop.continue(x, Kyo.unit)` ->
   `Loop.continue(x, ())` or better the original shapes; `Loop.continue(Kyo.unit)` -> nullary
   `Loop.continue`; drop pending ascriptions in Poll.run (`(state.headMaybe: Maybe[V] < Any)`),
   Var.runWith (`(state: V < Any)` x3), Pipe contramap family (4 ascriptions), Sink contramap
   family (4). Check runAbort's explicit type args stay (that one is a row instantiation, correct).
   Stream zip's ZipOutcome/continueBoth/zipDone constructors: re-test whether still needed; their
   root cause is HandleFirst's B fixed by the handle lambda (separate from the clause-signature
   fix); if still needed they stay (they are legible), else revert.
6. Full `kyo-preludeJVM/compile` green, then `kyo-kernel2JVM/test` full suite (signature + variance
   changes obligate it; ArrowEffectTest's ascribed answers should also be revertible to raw), then
   clean batch build (`kyo-kernel2JVM/clean` + `compile`; the union summons nothing new but the
   rule stands), JS/Native compiles.

## Debugger state (validation nearly final)

- Landed shape (all committed): global `Debugger` abstract class = the hook itself (user's design),
  `Noop` default, `@static` members, CHA is the zero-cost mechanism. Gate is FRAMELESS
  `enterStrict()` consulted only in `Safepoint.enterPark` (cold path); a session drains its eval's
  own slot at entry (`Safepoint.drain`, own-thread only, applied after `save`). `enter(slot)` and
  all call sites are byte-identical to pre-debugger. Eval reads the debugger once per eval; three
  stack hooks (onDefer/onSuspend/onDeliver, typed, identity-guarded with `ne Debugger.Noop` so no
  frame reads happen outside sessions); fastPathsAllowed gates the fast dispatches.
- Perf history (recorded in commits + reviews/bench/*.json): gate-in-enter REJECTED (fusion rows
  to 4x; enter 30->44 bytes); frame-param-only REJECTED (probe held at gate levels; the frame
  operand per call site was the whole mechanism); frameless = EXACT parity on the 4 regressed rows
  + evalFixedOverheadBatch (`frameless-probe.json` vs `redfixes-0822-kernel2.json`).
- PROMISED to user, not yet done: rename `Debugger.enterStrict()` -> `enter()` (method on Debugger
  class, enterPark call site, DebuggerTest Recording override, doc). Do it with the next kernel
  edit batch.
- REMAINING validation: the FULL board at the final debugger commit (whole KernelBench class, f2,
  non-gc, worktree, compare vs `redfixes-0822-kernel2.json`; expect parity). Suite last ran 1065/0
  at `a3a79f2923`; must re-run after the union rollout anyway.

## Perf bookkeeping (all artifacts in reviews/bench/, committed)

- `redfixes-0822-kernel2.json`: post-red-fixes board (time reference, non-gc).
- `redfixes-gc-0822-kernel2.json`: gc board at same commit; B/op byte-identical to
  `answerscont-0822-kernel2.json` on every row (allocation table's kernel2 column).
- dynamic-chain rows: board's +23/26% was fork noise; real delta maps +0.16ns (baseline 3.17 ->
  3.33) attributed to the stop-redesign window, not the red fixes; allocation identical; next rung
  if pursued: PrintInlining on maps. Open, low priority, user-visible in the tables discussion.
- Debugger probes: `enterpark-probe.json` (rejected shape), `frameless-probe.json` (parity),
  `debugger-0822-kernel2.json` (rejected full board).
- Cross-lib tables convention: kernel2/zio/ce/turbolift columns, no zioblocks, no oldkernel,
  green = kernel2 beats all, red = loses to any, bold = row best; join via `reviews/crosslib_join.py`;
  kyo map==flatMap already handled by the mirror benches. External boards: `crosslib-0821-*.json`.

## Campaign log (context for why things look as they do)

- All 8 audit reds fixed and verified this session (F1/F1b unwind, F2 acquire-window/BindingStep,
  F4 ruling B clause-throw escape + Stack.escape + Out.ClauseThrew, F6/F6b finally, F7 abandonment,
  F8 carrier sync). Suite was 1052/0 pre-debugger, 1065/0 with debugger.
- Prelude migration done so far: Debug deleted; 8 handle->handleCont; loop family answer-style
  everywhere; HandleFirst helper (inline, in kyo/internal/HandleFirst.scala) replacing
  handleFirst at 15 sites; Local reshaped (fork/join strategies, initNoninheritable kept as
  shorthand); Abort acceptance moved after the region (re-raise unaccepted; no dispatch accept
  filter); ArrowEffect[-I,+O]; Reducible untouched; done-less handleCont/handleLoop overloads
  added; nullary Loop.continue is the cached unit outcome.
- Docs: reviews/KERNEL2-PRELUDE-MIGRATION-PREP.md (site inventory/rules),
  reviews/KERNEL2-OBSERVE-DESIGN.md (debugger design history incl. DAP mapping),
  reviews/KERNEL2-RED-FIXES-DESIGN.md, KERNEL2-CLAUSE-THROW-RULING.md (ruled B).

## Immediate next actions, in order

1. Finish the union rollout (list above), compile prelude to green on `sbt --client`.
2. Rename enterStrict -> enter.
3. Revert prelude + ArrowEffectTest ascription workarounds; prelude green again.
4. `kyo-kernel2JVM/test` full suite; fix anything the union surfaced.
5. Clean batch build + JS/Native compiles.
6. Full debugger board in the worktree (mutex; checkout final commit) vs redfixes board; report
   the complete time table (alloc column from the gc reference) with the standing conventions.
7. Commit at each green step.

## Parked todos

- CanLift: reject lifting a value statically typed `Any`. An erased computation re-entering the
  kernel through the lift is nested as data and delivered unrun (the kyo-http dispatch bug fixed
  in 7abded79a3). The macro sees the static type per summon site, so the guard only fires where
  the type system has lost track; abstract type parameters still lift. After adding, compile the
  whole tree to surface every site the guard catches.
