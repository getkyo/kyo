## Scope integrity (read every cycle)

- Every line item in the plan's `### Files to produce / modify / delete` and `### Tests` sections is mandatory.
- You may not silently drop, weaken, or substitute. If you cannot implement an item, mark the subtask `pending` with a reason and continue. The supervisor will resolve it.
- You do NOT commit. Leave the tree dirty; the supervisor reads `git diff` and commits.
- You do NOT modify the plan, design doc, validation docs, or open-items audit.
- "Simpler" is not a justification. "Redundant with X" is not a justification. Implement exactly as specified or escalate.
- Refactor phases preserve existing behavior byte-for-byte. Any default or derivation not in the plan must match prior code's computed value; do not invent new values.

## NEVER STOP (mirrored from /implement skill)

Once Stage 2 begins, the supervisor drives every phase through commit and immediately launches the next. The loop only stops when:

1. The plan is fully exhausted (all phases committed and final audit green).
2. A task is genuinely blocked after 3 retries on the same agent AND a concrete repro is in this file for the user.
3. The user has explicitly typed "stop", "pause", or equivalent.

If you find yourself wanting to pause for input mid-plan, re-read this section. The user opened the loop with `/implement`; the loop stays open until the plan is exhausted.

## Resume protocol

If context is compacted or any interruption occurs:

1. Do NOT ask "where were we?" or "should I continue?"
2. Run `TaskList`. The next `in_progress` or `pending` task is the resumption point.
3. Read `PROGRESS.md` to confirm which phases are committed at HEAD.
4. Read this file (STEERING.md) for any pending directives.
5. Resume by re-launching the next agent / verification / commit step.

## Project-specific rules

- **No em-dashes anywhere**: per `feedback_no_em_dashes`, never use `—` or `–` in any output (code, comments, docs, commit messages). Use commas, parentheses, colons, periods.
- **No coauthor**: per `feedback_no_coauthor`, never add `Co-Authored-By` lines to commits.
- **Never push to remote**: per `feedback_no_push`, never run `git push` under any circumstance.
- **kyo public API only, kyo.internal for implementation**: per `feedback_kyo_package`. Within kyo-reflect, internals nest as `kyo.internal.reflect.{binary,tasty,classfile,symbol,type_,query,reads,snapshot}.*` (sub-packages allowed under `kyo.internal`); macro entry points live flat at `kyo.internal.ReflectMacro` / `kyo.internal.SymbolToRecordMacro` per the `StructureMacro` / `TagMacro` precedent.
- **All platforms, all tests**: tests live in `shared/src/test/scala/kyo/` and run on JVM + JS + Native.
- **Span over Array**: per `feedback_prefer_span`. Use `Array[Byte]` only when mutability is strictly needed (e.g., read buffers).
- **No AllowUnsafe / Frame.internal**: per `feedback_no_unsafe`. Propagate `(using Frame)`.
- **No semicolons**: per `feedback_no_semicolons`.
- **Lowercase namespace objects** (`internal`, `isolate`, etc.) per `feedback_lowercase_namespace_objects`.

## Active directives (cleared as agents comply)

(none right now)
