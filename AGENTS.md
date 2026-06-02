# Agent Guide

This file helps AI coding agents work effectively on the Kyo codebase. Read [CONTRIBUTING.md](CONTRIBUTING.md) first — it is the comprehensive reference for all conventions, patterns, and design decisions.

## Operating Premise (read before anything else)

**Only complete and correct solutions are acceptable.**

This is non-negotiable. It applies to every change at every scope ; a one-line fix, a refactor across a module, a campaign that touches three modules. The framework is the same:

1. **Identify the correct fix.** Diagnose root cause. The correct fix is the one a reviewer with full context would call right. It is not the cheapest fix; it is not the fix that fits a time budget; it is not the smaller-scope alternative offered as a "pragmatic choice."

2. **Scope the correct fix honestly.** Read every layer it touches. If a type-system change at one factory cascades into another module's engine, that is part of the scope. Do not estimate the visible surface; estimate the actual surface. If the actual surface is larger than first thought, say so.

3. **Execute the correct fix.** Across every layer, end to end. Tests pass. Docs reflect the new shape. No dead branches. No "we will polish this later."

4. **Never propose alternatives that trade correctness for scope.** If you find a smaller fix that achieves a similar user-visible outcome via a less-rigorous path, that is information for the conversation. It is NOT an option for you to silently take. Surface it; let the user pick. Default to the correct one.

5. **Never normalize incomplete results.** If a refactor is half-done because the remaining layers are large, the work is half-done. Do not commit it. Do not declare it shipped. Do not write a celebratory summary listing what was done while omitting what was skipped.

6. **A change that breaks tests, breaks compile, or breaks documentation is not a change. It is a partial change.** Roll forward to completion or revert and reset.

7. **When in doubt, ask.** Asking for the correct scope is cheaper than shipping the wrong one.

The phrase **"shall I take the smaller fix?"** is structurally an offer to be incomplete. Do not offer it. If only the larger fix is correct, do the larger fix. If a smaller fix IS the correct fix, it would not have surfaced as "smaller" ; it would have surfaced as the natural answer.

The phrase **"this is good enough for now"** has no place in a kyo change. Either it is correct and done, or it is not done.

### The "pending issue" / "known issue" stop-pattern is banned

If a campaign goal is "X works end-to-end" and validation surfaces multiple bugs, fixing 4 of 5 and writing the 5th up as a "known issue requiring deeper investigation" does NOT meet the goal. The campaign is half-done. The 5th bug IS the work.

Forbidden phrasings, structurally — when you catch yourself drafting one, treat it as a flag that you are about to commit the anti-pattern:

- "Known issue: …" / "Documented as a known limitation."
- "This requires sustained X-internals investigation beyond a validation session."
- "Fix path: either … or … . That work is substantially larger than this session."
- "We've surfaced enough; let's pause and assess."
- "Documenting in FINDINGS for follow-up."
- "Out of scope for this session."

Re-scope instead: the unfixed bug is the next concrete task. Pick the fix path, execute it. Validation isn't done when the diagnostic writeup is comprehensive; it is done when the demonstrable end-state is true (host connects, tool calls succeed, response shape matches the spec, …).

The only legitimate stops within an in-flight campaign are:

1. The user explicitly says stop / pivot / park.
2. The next step requires a destructive or scope-affecting action that policy requires confirmation for (push, force-reset, schema-breaking change with downstream impact).
3. A genuine block: 3 attempts have failed AND each failure mode was novel AND there is no information you could gather independently to make a 4th attempt better.

"This is hard," "this touches a layer I haven't worked in yet," and "this needs deeper investigation" are not blocks. They are work.

## Core Rules

### Fix the Code, Not the Test

When a test fails or code doesn't compile, **diagnose the root cause before changing anything**:

1. **Determine if it's a code bug or a test bug.** Read both the implementation and the test. Understand what the test is verifying and why.
2. **Don't assume which side is wrong.** Read the implementation and the test. Understand the intended behavior, then determine where the bug is. Changing whichever side is easier is not diagnosing.
3. **Only change a test when the test itself is incorrect** — wrong assertion, outdated expectation, flawed setup. Document why the test was wrong.
4. **Never weaken a test to make it pass.** Removing assertions, loosening checks, or catching exceptions to suppress failures hides bugs.

### Write Meaningful Tests

- **Every test must assert something specific.** A test that just runs code without checking results proves nothing. `assert(result == expected)` — not `assert(true)` or no assert at all.
- **Assert on concrete values, not just types or non-emptiness.** `assert(result == List(1, 2, 3))` — not `assert(result.nonEmpty)` or `assert(result.isInstanceOf[List[_]])`.
- **Test behavior, not implementation.** Verify what the code does from the caller's perspective, not how it does it internally.
- **Cover edge cases.** Empty inputs, error paths, boundary conditions, concurrent scenarios — not just the happy path.

### Write Clean, Simple, and Safe Code

- **Never block a thread.** No `Thread.sleep`, `synchronized`, `Future.await`, `CountDownLatch.await`, or any blocking primitive. Use `Async`-based suspension (`Channel.put`, `Fiber.get`, `Clock.sleep`). 
- **Minimize allocations.** Use opaque types over wrapper classes. Provide pure variants (`mapPure`, `filterPure`) for hot paths. Fast-path degenerate cases (empty, single-element) before entering general logic. Prefer `@tailrec` loops over recursive allocations.
- **Keep it simple.** Don't over-engineer. No unnecessary abstractions, no speculative generality. Three similar lines are better than a premature abstraction.

The goal is always improvement. Making things compile and pass is not the goal — correct, well-designed code is.

## Build & Test

Building automatically formats the code — no need to run formatting separately. Re-read any files you've edited after building, since formatting may have changed them.

```sh
# Set JVM options (required for stable builds)
export JAVA_OPTS="-Xms3G -Xmx4G -Xss10M -XX:MaxMetaspaceSize=512M -XX:ReservedCodeCacheSize=128M -Dfile.encoding=UTF-8"
export JVM_OPTS="$JAVA_OPTS"

# Test a specific module (JVM)
sbt 'kyo-core/test'

# Test a specific test class
sbt 'kyo-core/testOnly kyo.ChannelTest'
```

## Project Structure

Layered stack — each module depends only on modules above it:

| Layer | Modules | Purpose |
|-------|---------|---------|
| Foundation | `kyo-data`, `kyo-kernel` | Data types, effect system kernel |
| Effects | `kyo-prelude`, `kyo-core`, `kyo-combinators` | Effect definitions and combinators |
| Integrations | `kyo-caliban`, `kyo-cats`, `kyo-zio`, ... | Library integrations |
| Applications | `kyo-http`, `kyo-cache`, `kyo-stm`, `kyo-actor`, ... | Higher-level modules |

Source layout within each module:
```
kyo-<module>/
  shared/src/main/scala/kyo/       # Cross-platform source
  shared/src/test/scala/kyo/       # Cross-platform tests
  jvm/src/main/scala/kyo/          # JVM-specific source
  js/src/main/scala/kyo/           # JS-specific source
  native/src/main/scala/kyo/       # Native-specific source
```

## Writing Module READMEs

To create or rewrite a module's README, invoke the `/readme <module-path>` skill (defined at `.claude/skills/readme/SKILL.md`). The skill carries the conventions and runs the full source-analysis, draft, critique, and doctest-verification pipeline. `sbt <module>/doctest` validates every fenced Scala block.

## Pre-Change Checklist

- [ ] Read the file first — understand existing patterns before editing
- [ ] Read CONTRIBUTING.md for the relevant section

## Pre-Submission Checklist

- [ ] Tests pass — `sbt '<module>/test'` for affected modules
- [ ] Naming follows conventions — action verbs, no symbolic operators in core (see [Naming](CONTRIBUTING.md#naming))
- [ ] Public APIs have explicit return types
- [ ] Public types have scaladoc (8-35 lines)
- [ ] `using` clauses correctly ordered — `Tag` before `Frame` (inline), `Frame` before evidence (non-inline), `AllowUnsafe` last
- [ ] `inline` used correctly — effect suspension paths yes, handling paths no. Use to avoid function dispatch. (see [Inline Guidelines](CONTRIBUTING.md#inline-guidelines))
- [ ] Tests use the module's `Test` base class, not ScalaTest directly
- [ ] No `protected`, no `@uncheckedVariance` — use `private[kyo]` for cross-package visibility
- [ ] Kyo types used — `Maybe` not `Option`, `Result` not `Either`, `Chunk` not `List` (see [Types](CONTRIBUTING.md#types))

## Common Gotchas

1. **`kyo.System` shadows `java.lang.System`** — use fully qualified `java.lang.System` when needed
2. **Effect handlers are not inline** — `Abort.run`, `Var.run` are regular methods; only suspend/create methods are inline
3. **`Frame` required on every effectful method** — but not on pure data accessors like `capacity` or `size`
4. **Overloads delegate to canonical** — never duplicate logic across method variants
5. **`Scope`-managed resources are the default** — use `init`/`initWith` unless there's a specific reason for `initUnscoped`
6. **Unsafe tier mirrors safe tier** — every safe operation has an `Unsafe` equivalent; bridge via `Sync.Unsafe.defer`
