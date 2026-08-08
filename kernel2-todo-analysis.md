# kernel2 review-TODO analysis (rulings incorporated)

Source: the 31 review notes in `kyo-kernel2` sources plus the follow-up feedback pass
(inline comments in this doc and 4 new TODOs in Safepoint.scala). All user rulings are
baked in below; items marked APPROVED proceed one at a time; two items moved to
dedicated opus design tracks per instruction.

## Answers to questions raised in the feedback

- **"so this is work in progress?" (preemption)**: yes. The Safepoint machinery is
  built and tested (SafepointTest, 8 tests), but the drives do not consult
  `Safepoint.preempted`/`clearPreempt` yet; only `guardedRun` touches Safepoint (depth
  guard). The `preempt()`/`period`/`stride` parameters are the interim stand-in. The
  integration is now design track A.
- **"can we make the observe function effectful with only minor changes?" (C11)**: yes,
  and the change stays contained to the Observe class. The observed loop already runs
  step-by-step (each transform executes with the empty continuation so every
  intermediate value is visible), so an effectful observer
  `(Frame, Any) => Any < S2` composes as `observer(frame, value).map(_ => step)` per
  frame, and the signature becomes `observe[A, S, S2](observer)(v: A < S): A < (S & S2)`.
  Cost: one map node per observed frame, only while observing (debug path). One
  subtlety to handle at implementation: the observer's own computation must sit outside
  the re-armed observed region so observation does not observe itself.
- **"explain why we need this" (C14, the boundary clause carrier)**: `handlePartial`'s
  clause cannot be an installed chain delimiter for two reasons. (1) It must not travel
  with the parked continuation: each scheduler slice re-enters the drive with its own
  clause, so a chain-installed clause would either stack duplicates per slice or need
  dedup; the old kernel has the same shape (the runtime loop in IOTask interprets its
  effect outside the computation, not via an installed handler). (2) It needs the full
  continuation, delimiters included, so a later out-of-band resume re-installs every
  traveling handler; a delimiter would capture only the prefix up to itself. So some
  per-drive carrier must exist; the fix (approved) is making it a typed node threaded
  as `Maybe`, not the nameless `LastResort | Null`.

## Rulings on the analysis facts

1. `Kyo.lift`: it is a user-facing method; do NOT delete. Fix the implementation
   (remove the bare cast, box nesting like `liftSlow` does). See C1.
2. Preemption plumbing: opus design track A (below).
3. `discard` rename: `finalizeBracket`. Rationale recorded: `discard`/`abandon` read
   like benign value-dropping (people could call them thinking `.unit`); the name must
   signal the destructive bracket-finalization semantics.
4. `unsafeGet`: remove; migrate the test to the current API.
5. Truncated TODO on ContextSnapshot: confirmed meaning: ContextSnapshot should not
   exist at all; design a context-as-parameter architecture like the old kernel. Design
   track B.
6. Internal `KyoException` renamed `EffectTrace`: approved. Post-swap obligation
   recorded: the mechanism must integrate with kyo-data's public `KyoException`
   (enriching those exceptions too, not just internal ones).
7. Context to TypeMap: approved as direction; the challenge is performance, not code
   change. Benchmarks decide (see C19).
8. Loop drivers: benchmark first against the old kernel, then optimize (see C17).
9. `Arrow.of` deletion: approved.
10. `LastResort | Null` to typed `Maybe`-threaded node: approved.

## Change items with rulings

### C1. Implicits superclass; FIX `Kyo.lift` (do not delete)  [REVISED PER RULING]
- Superclass name: `Implicits` (not Lifting), holding the implicit conversions
  (macro `lift`, `liftAnyVal`, `liftUnit`, `abortCastUnit`, `liftPureFunction1-6`,
  `Render` given); `object <` extends it.
- `Kyo.lift` stays as the user-facing explicit lift, called only when the implicit
  conversions confuse inference; its implementation is fixed to handle nesting (the
  current bare cast is the bug; the `liftSlow` boxing logic is the correct body).
  `liftSlow` itself is then removed; internal callers use the canonical `Kyo.lift`.
- `Kyo.unwrap` renamed `Kyo.unnest` (stays `@static`).

### C2. Forwarding elimination sweep  [APPROVED]
- Delete `unsafeGet` (test migrates to `eval`/`evalNow`), `driveInstalled`,
  `drivePartial` (callers call the drive directly), `Effect.deferInline`, `Arrow.of`
  (12 sites); `neverPreempt` becomes the single val.

### C3. Typed Handler hierarchy  [APPROVED]
- User note: the original prototype was properly typed; use it as the reference shape
  (`kyo-kernel/shared/src/main/scala/kyo/proto*/`). Kill the `Clause`/`InputClause`/
  `LoopClause` aliases; store user clauses at their public types; erased boundary
  concentrates at the dispatch tag-match. Re-run JMH (removes per-handle adapter
  closures).

### C4. Handler kind naming  [APPROVED]
- `Operation` -> `ArrowHandler`, `Handler.Context` -> `ContextBinding`.

### C5. Type-parameter naming + variance sweep  [APPROVED]
- `Suspension[+A, -S]`, `Continue[A, +B, -S]`, `Offset[-A, B, +C, -S]`, `Step` member
  rename, `isEmpty[A, B, S]`.

### C6. Verb consolidation to eval  [APPROVED]
- `evalLoop` / `evalSuspension` / `evalOperation` / `evalBoundary`.

### C7. toString sweep  [APPROVED]

### C8. Remove `(x: Any) match` widenings  [APPROVED]

### C9. Principled `prepend` via `Arrow.Interceptor`  [DESIGN FOR APPROVAL FIRST]
- User asks for an elaborated design before implementation: exact Interceptor contract
  (pass-through typing), which classes implement it (Catching, Observe, Finalize?),
  how `Suspension.prepend`/`Continue.prepend`/`Bracket.prepend`/`Defer.prepend`
  signatures change, and what casts disappear. To be presented in-line before coding.

### C10. `discard` -> `finalizeBracket`  [DECIDED]

### C11. Observe extraction  [APPROVED + FOLLOW-UP]
- Move to `Observe.apply` in its own file; then (before the migration) make the
  observer effectful per the answer above.

### C12. `EffectTrace` rename + Trace stub deletion  [APPROVED]
- Post-swap note: integrate with kyo-data `KyoException` (see ruling 6).

### C13. Boundary snapshot API  [MERGED INTO DESIGN TRACK B]
- User: launch an opus agent to explore. Since ruling 5 removes ContextSnapshot
  entirely, the boundary API design is a corollary of the context-threading design;
  one agent covers both.

### C14. Boundary clause as typed `Maybe`-threaded handler  [APPROVED]
- Justification recorded above (answers section).

### C15. Preemption integration  [DESIGN TRACK A]
- Additional rulings: the period is always a constant, never overridable through any
  API; the user is unhappy with the current preemption-facing API (the
  `eval(preempt, period)` / `handlePartial(preempt, period)` shape), so the design
  proposes its replacement.

### C16. Single-allocation defer  [PENDING USER RULING]
### C17. Loop driver optimization  [APPROVED, BENCHMARK FIRST]
- Add loop JMH rows measuring old kernel vs kernel2 before changing code; optimize to
  parity or better.
### C18. Context threading  [DESIGN TRACK B]
### C19. Context to TypeMap  [APPROVED AS DIRECTION, PERF-GATED]
- Benchmark TypeMap-backed Context against the current Map on the context-heavy rows
  before adopting.
### C20. Visibility audit  [PENDING USER RULING]

## New Safepoint TODOs (feedback pass 2), folded into design track A

- S1 (`Safepoint.scala` Parked): rename `Parked`? "it's odd to think a safepoint would
  be parked"; user proposes `Preempt`-flavored naming.
- S2 (slots array): use `Maybe[Safepoint]` instead of null-empty slots if it costs
  nothing; requires pre-filling the array with `Absent` and benchmarking `get`.
- S3 (Overflow): current permanently-parked Overflow is too drastic. Analysis confirms
  the user's concern is real: with `enter()` always false, a lone-transform step
  rescues into a Defer whose re-execution rescues again identically, a livelock (the
  same shape as the fixed nested-drive livelock, but permanent). Ruling: a task must
  keep running without preemption/interruption rather than never progress; design a
  fallback (candidate: an unregistered Active, keeping the depth guard and progress,
  losing only preemption delivery).
- S4 (clearPreempt): analyze whether consume-then-check is sufficient in all cases
  (re-preemption during a slice, multiple requesters, interruption vs preemption).

## Design tracks (opus agents, analysis-only, deliverable = design doc)

- **Track A: preemption integration** -> `kernel2-preemption-design.md`.
  Scope: drive polling protocol (cascade for installed-handler drives,
  consume-then-check at boundary drives), constant period (no API override), the
  replacement for the `eval(preempt, period)` / `handlePartial` preemption API,
  scheduler side (how IOTask requests preemption via `Safepoint.preempt`), S1-S4.
- **Track B: context as a parameter** -> `kernel2-context-threading-design.md`.
  Scope: eliminate ContextRead/ContextSnapshot chain-walking suspensions; thread
  context like the old kernel while preserving kernel2's fused-chain execution;
  where bindings live across park/resume; Handler.Context delimiter role;
  `hasHandler` necessity; the Isolate/IOTask boundary snapshot API (C13).

## Execution order

C2 (now) -> C5 -> C6 -> C7 -> C8 -> C10 -> C1 -> C11 -> C12 -> C3+C4+C14 -> C9
(design first) -> C17 (bench first) -> C16, C20 (pending ruling) -> track A landing ->
track B landing -> C19 (perf-gated).
Full kernel2 suite green after each item; JMH after perf-relevant ones.
