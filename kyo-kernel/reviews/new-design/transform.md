# Kernel transform to the new Handler protocol

Target = the user-authored `Handler.scala`. Base = `cdefdc9e60` (robustness-lineage, the more complex
version of the same machinery). This is a simplification of a working evaluator, checked against the
compiler and the kernel suites.

Ground truth for the design: `rulings.md` (worktrees/robustness/.../kernel/rulings.md), section
"Regions and releases" (2026-09-13) and "New types" (release list shape).

## The new protocol (already in Handler.scala)

- `Handler[E,A,-S]`: only `tag`. No `repeated`, no `escaping`, no `bound`/`unbound`.
- `ArrowHandler`: `done(state,v)`, `recover(state,ex)`.
- `ContHandler`, `MaskingHandler`, `LoopHandler`, `LoopStateHandler`: unchanged shapes.
- `FirstHandler[I,O,E,A,B,S]`: `run[X](input, cont): B < (E & S)`. The peel, one-step (replaces the
  ContHandler+escaping+FirstSuspended two-step). It is the ONLY escaping handler.
- `ContextHandler`: `derive`/`fork`/`join` + `release(state, failure: Maybe[Throwable])`. No `done`,
  no `borrow`/`defers`/`reenter`/`discharge`/`bound`/`unbound`.

## The release mechanism (replaces owed-snapshots + borrow/defers/held/discharge)

Rulings: "each region's release lives in its own stack entry"; "a handler that takes the continuation
moves the dumped entries' lists to its own entry"; "an escaping handler moves its list to the entry
below"; "release runs once, where the holder ends"; "repeated does not exist".

- Stack entry gains a release list `Releases = (Maybe[Throwable] => Unit) | Chunk[...]` (no wrapper).
  Named `releases(i)`, replacing `owed(i): Chunk[Snapshot]`.
- Push a ContextHandler region -> `releases(i) = (failure => handler.release(state, failure))`.
  ArrowHandler regions start empty.
- Own end is run by the evaluator directly, NOT from the list:
  - ContextHandler normal end: `handler.release(state, Absent)`.
  - ContextHandler unwind:      `handler.release(state, Present(ex))`.
  - ArrowHandler: `done`/`recover` as today.
- The list holds only BUMPED releases (from dumped/held remainders). It runs when the entry ends.
- DUMP (handler at idx takes cont over idx+1..top): append the dumped entries' release lists onto
  `releases(idx)`. The dumped snapshot carries handler/state/cont for reinstatement only (no release);
  reinstalled regions have empty release lists (release stays with the holder).
- Escaping (FirstHandler) exit: move `releases(top)` to the entry below (`oweBelow`).
- Reads resolve from the stack via `find` (no Context): innermost matching region; ContextHandler ->
  read `state(idx)`; MaskingHandler -> dispatch as an operation (mask). `Context.scala` is deleted.

### FLAGGED simplification (recording removed)

Base recorded each held remainder's ending (Cell.ended) to pass `Absent` vs the discard signal at the
holder's end. The new ContextHandler has no recording. Recording-free reading of "release runs where
the holder ends": a bumped release is told the HOLDER's ending -> `Absent` if the holder ends clean,
`Present(ex)` if it unwinds. Consequence: a remainder acquired-but-never-cleanly-completed, whose
holder ends clean, sees `Absent` (base would have signaled a discard). Matches "no backpressure on
failure" and the anti-overengineering ruling. Implement this; surface it; the reenter-refusal
(`Closed` on a second shot) is gone (a held cont runs every shot against the live resource).

## Surface (files)

1. `internal/Handler.scala` (target has protocol) - bring back answering helpers from base:
   ContHandler.answering, MaskingHandler.answering, LoopHandler.running/clauseDispatch/answers,
   LoopStateHandler.*, attachReentry/attachReentry2, answersLoop/answersLoopState. Add
   FirstHandler.answering (run peel). Drop bound/unbound/repeated/escaping/borrow/defers/reenter/
   discharge/done-on-ContextHandler.
2. `internal/Stack.scala` - `owed:Chunk[Snapshot]` lane -> `releases:Releases` lane; ops
   owe/oweBelow/take/bump renamed to carry release functions; Snapshot loses its 4th (owed) slot or
   keeps it inert. Drop `held`/`borrow` coupling.
3. `internal/Eval.scala` - drop `ctx: Context` param throughout; SuspendContext resolves via stack;
   FirstHandler arm; release-in-entry on push/pop/dump/escape/unwind; drop rebound/rebuilt/contextExit
   context-threading; `arrowExit`/`contextExit` collapse; `released`/`drain*` run release(state,failure).
4. `internal/Context.scala` - DELETE.
5. `ContextEffect.scala` - `handle` wires public `done`+`release(A,Throwable)` into the single
   `ContextHandler.release(state, failure)`.
6. `ArrowEffect.scala` - `handleFirst` builds `FirstHandler` (run = handle peel; done = no-op-found).
   Drop `handleContRepeated`/`handleFirstRepeated` (repeated gone); update Choice + tests.
7. `Bracket.scala` - Cell keeps only the exactly-once release guard; drop borrow/defers/reenter/
   discharge/ended. ContextHandler overrides only release(state, failure). Keep Arrow.Ensure for the
   atomic acquire->install (ensureMap is the alt; defer that cleanup).

## Consumers outside kernel
- `Choice.scala:98` handleContRepeated -> handleCont; `:125` handleFirstRepeated -> handleFirst.
- kyo-core Scope/Sync/Async/Exchange use ensureMap (stays) and Bracket (public shape stays).

## Milestones
A. Handler + Stack + Eval + delete Context + ContextEffect + ArrowEffect + Bracket compile (kyo-kernelJVM/compile).
B. Kernel suites green (ArrowEffectTest, ContextEffectTest, BracketTest, EvalTest, PendingTest, IsolateTest, EffectTest). Tests encoding removed semantics (reenter refusal, handleContRepeated) updated, each flagged.
C. Choice + kyo-prelude green. Then kyo-core.
