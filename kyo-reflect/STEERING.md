# STEERING.md — kyo-reflect performance plan

Read this on every compile/test cycle. Follow any directives immediately.

## Active plan

`kyo-reflect/execution-plan-perf.md` — 8-phase cold-load optimization plan. Baseline 55 ms cold-load / 57 ms snapshot; targets 25 ms / 5 ms.

## Scope integrity (read every cycle)

- Every line item in execution-plan-perf.md's Files to produce/modify/delete and Tests sections is mandatory.
- You may not silently drop, weaken, or substitute. If you cannot implement an item, mark its subtask `pending` with a reason and continue. The supervisor will resolve it.
- You do NOT commit. Leave the working tree dirty; the supervisor reads `git diff` and commits.
- You do NOT modify the plan, analysis docs, PERF-VERIFICATION.md, COLD-LOAD-PROFILE-FULL.md, or PLAN-VALIDATION.md.
- "Simpler" is not a justification. "Redundant with X" is not a justification. "Edge case" / "out of scope" / "probably not needed" are not justifications. Implement exactly as specified or escalate.
- Refactor phases preserve existing behavior byte-for-byte unless the plan explicitly says otherwise.

## Project-specific guardrails

- **No `Co-Authored-By` lines in commits.** Supervisor handles all commits.
- **No em-dashes** (`—` or `–`) in any output (code, prose, commits, comments).
- **No `Frame.internal`** anywhere in production code.
- **No `AllowUnsafe`** outside of justified bridging sites; if added, include a `// Unsafe:` comment with the reason.
- **No `asInstanceOf`** outside macro source (emitted in `'{...}` quotes is allowed).
- **No `null`** in new code; use sentinel objects or Maybe.
- **No `var` for shared mutable state.** Use AtomicRef/AtomicInt/AtomicLong/AtomicBoolean.
- **No default params on internal/private APIs.** Every caller passes explicitly.
- **No explicit `[E]` on `Abort.fail`** that inference could resolve; keep only when removing it breaks compilation.
- **Public API in `kyo` package; implementation in `kyo.internal`.**
- **Tests live cross-platform in `kyo-reflect/shared/src/test/scala/kyo/`** unless the feature is platform-specific (JVM-only for jar handling: tests go in `kyo-reflect/jvm/src/test/`).
- **Use Kyo types:** Maybe not Option, Chunk not Seq (for internal storage), Span where mutability not needed, Result not Either.
- **No `Fiber.block`.** Use `fiber.safe.get` or `onComplete`.
- **No semicolons** to chain statements.
- **No manual JSON.** Use kyo-http's Json with derives Json.

## NEVER STOP (supervisor's hard rule)

The supervisor drives every phase through commit and immediately launches the next. Valid stopping points: plan exhausted (Phase 8 green), 3-retry blocked task with documented repro, explicit user "stop". Anything else is a stall.

## Test-run cadence (impl agent rule)

Inside a sub-phase agent: targeted `testOnly` for files touched in this phase + cross-platform `Test/compile`. Never run the full suite from inside an agent. Phase-group full suites are supervisor-driven.

## Verification before commit

Supervisor runs verification before committing each phase. Agents leave dirty trees; falsely claiming "committed" is a hard violation.

## Sequential cross-platform test runs

Never run JVM/JS/Native suites in parallel. One platform at a time (resource contention, Chrome instances, ports). For kyo-reflect: JVM first, then Native, then JS.

## Steering log (mid-flight corrections)

(none yet)
