# Agent Guide

This file helps AI coding agents work effectively on the Kyo codebase. Read [CONTRIBUTING.md](CONTRIBUTING.md) first: it is the comprehensive reference for all conventions, patterns, and design decisions.

Many modules also carry their own `<module>/CONTRIBUTING.md` (for example, `kyo-browser/CONTRIBUTING.md` documents the transparent-settlement model). It records the module-specific invariants, mechanisms, conventions, and extension recipes that the root guide does not. Before working in a module, read its `CONTRIBUTING.md` when present; its invariants are binding, and a change that alters them must update that file. Generate or review one with the `/contributing <module>` skill.

## Core Rules

### Working Mindset

How to work on Kyo, not just what the code looks like. CONTRIBUTING.md carries the full conventions; these are the principles behind them.

- **Think in values, not actions (FP).** A Kyo computation is a value you compose, not an action you run. The effect row (`< Sync`, `< Async`, `< Abort[E]`) is part of the contract: never widen or hide it to shorten a signature. Default to immutability (`val`, `Span` over `Array`). Use mutable state only when it is genuinely necessary (a hot-path read buffer, a local accumulator); when you do, give it the smallest possible scope and keep it well isolated, never a shared `var` reaching across methods or fibers. For state that must cross fibers, use `Atomic*`. Keep side effects inside effects and pure positions pure. Make functions total: model absence and failure with `Maybe`, `Result`, and `Abort`, never `null`, exceptions-as-control-flow, or partial functions.
- **Never reward-hack.** A failing test or red CI that exposes a real bug is the deliverable, not an obstacle. Never revert a fix, weaken an assertion, catch-and-suppress an error, or relabel a failure "flaky", "pre-existing", or "out of scope" to get green. Fix the root cause. The "Fix the Code, Not the Test" rule below is the test-specific case.
- **Complete and correct, no scope cuts.** Finish the whole task. Do not silently drop, defer, or "simplify" a requirement; if something genuinely cannot be done, flag it rather than quietly skipping it. Never infer priorities or reorder work by assumed importance: order only by technical dependency, and ask when the order is unclear. Treat these words in your own reasoning as red flags: "for now", "out of scope", "edge case", "probably not needed".
- **No AI-generation tells in any output.** No em-dashes or en-dashes, no marketing adjectives ("blazing", "powerful", "seamless"), no filler openers. This holds everywhere: code comments, scaladoc, READMEs, commit messages. Use commas, colons, parentheses, or separate sentences instead of dashes.
- **All platforms, shared tests.** Source and tests target JVM, JS, and Native. Cross-platform tests live in `shared/src/test` and must pass on all three; never move a test into a `jvm/` (or `js/`, `native/`) folder to dodge platform-specific infra cost. Genuine platform-specific behavior is the only reason to split.
- **Safe by default.** Prefer the safe API tier. Reach for `AllowUnsafe` or the unsafe tier only at justified bridging boundaries, and mark each site with a `// Unsafe:` comment explaining why. See CONTRIBUTING.md "Unsafe Boundary" for the tiers.

### Leave No Issue Behind

When you find a problem, it is yours to resolve, whether or not you caused it. A failing test, a compiler warning, a broken link, a flaky result, a latent bug noticed in passing: own it.

- **"Pre-existing", "unrelated", "not my change", "flaky", and "out of scope" are not dismissals.** During any campaign (a CI-greening pass, a refactor, an audit, a test run), every red signal gets owned. A failure you did not introduce is still a failure you found, and walking past it ships it to the next person.
- **Route what genuinely crosses a boundary; never drop it.** If an issue belongs to another module or concern and truly cannot be fixed in place, surface it explicitly: call it out in your summary, leave a tracked note, or hand it off. Silently skipping it is the exact failure mode this rule exists to prevent.
- **No deferral to a phase that never comes.** Fix issues in the current step or the immediately-next one. "Handle it later" and "cleanup phase" reliably resolve to "never"; deferred issues compound.
- **Do not use priority as an excuse.** Never decide a discovered issue is low-priority and therefore skippable. Address what you surface; escalate to the user only when a fix is genuinely value-underdetermined.

### Fix the Code, Not the Test

When a test fails or code doesn't compile, **diagnose the root cause before changing anything**:

1. **Determine if it's a code bug or a test bug.** Read both the implementation and the test. Understand what the test is verifying and why.
2. **Don't assume which side is wrong.** Read the implementation and the test. Understand the intended behavior, then determine where the bug is. Changing whichever side is easier is not diagnosing.
3. **Only change a test when the test itself is incorrect**: wrong assertion, outdated expectation, flawed setup. Document why the test was wrong.
4. **Never weaken a test to make it pass.** Removing assertions, loosening checks, or catching exceptions to suppress failures hides bugs.

### Reproduce Before You Fix

For any bug, regression, or "this shouldn't happen" report, write a failing test that reproduces it *before* touching the implementation.

1. **Reproduce first.** Add a test that fails because of the bug, and confirm it fails for the *right reason*: the actual symptom (wrong value, leaked resource, hang, panic), not a typo or setup error. A test that fails for the wrong reason does not capture the bug.
2. **Then fix.** Change the implementation until that test passes, without weakening the test you just wrote.
3. **Keep it as a regression guard.** The reproducing test stays in the suite permanently, named and placed with the surface it covers (see [Test Patterns](CONTRIBUTING.md#test-patterns-by-level)), never deleted once green.
4. **A fix with no reproducing test is incomplete.** If the bug was reachable once, an untested fix lets it return silently. The test is both the proof the fix works and the alarm if it regresses.
5. **Reproduce concurrency and timing bugs too.** Races, interrupts, and deadlocks are the hardest and most important to pin down. Drive them with deterministic `Async` constructs (latches via `Channel`/`Fiber`, `Clock` control) rather than sleeps; if a bug is only probabilistic, loop the scenario enough to make the failure reliable before fixing.

### Write Meaningful Tests

- **Every test must assert something specific.** A test that just runs code without checking results proves nothing. `assert(result == expected)`, not `assert(true)` or no assert at all.
- **Assert on concrete values, not just types or non-emptiness.** `assert(result == List(1, 2, 3))`, not `assert(result.nonEmpty)` or `assert(result.isInstanceOf[List[_]])`.
- **Test behavior, not implementation.** Verify what the code does from the caller's perspective, not how it does it internally.
- **Cover edge cases.** Empty inputs, error paths, boundary conditions, concurrent scenarios, not just the happy path.

### Write Clean, Simple, and Safe Code

- **Never block a thread.** No `Thread.sleep`, `synchronized`, `Future.await`, `CountDownLatch.await`, or any blocking primitive. Use `Async`-based suspension (`Channel.put`, `Fiber.get`, `Clock.sleep`).
- **Minimize allocations.** Use opaque types over wrapper classes. Provide pure variants (`mapPure`, `filterPure`) for hot paths. Fast-path degenerate cases (empty, single-element) before entering general logic. Prefer `@tailrec` loops over recursive allocations.
- **Keep it simple.** Don't over-engineer. No unnecessary abstractions, no speculative generality. Three similar lines are better than a premature abstraction.

The goal is always improvement. Making things compile and pass is not the goal: correct, well-designed code is.

## Build & Test

Building automatically formats the code: no need to run formatting separately. Re-read any files you've edited after building, since formatting may have changed them.

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

Layered stack, each module depends only on the modules above it:

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

- [ ] Read the file first: understand existing patterns before editing
- [ ] Read CONTRIBUTING.md for the relevant section

## Pre-Submission Checklist

- [ ] Tests pass: `sbt '<module>/test'` for affected modules
- [ ] Naming follows conventions: action verbs, no symbolic operators in core (see [Naming](CONTRIBUTING.md#naming))
- [ ] Public APIs have explicit return types
- [ ] Public types have scaladoc (8-35 lines)
- [ ] `using` clauses correctly ordered: `Tag` before `Frame` (inline), `Frame` before evidence (non-inline), `AllowUnsafe` last
- [ ] `inline` used correctly: effect suspension paths yes, handling paths no. Use to avoid function dispatch. (see [Inline Guidelines](CONTRIBUTING.md#inline-guidelines))
- [ ] Tests use the module's `Test` base class, not ScalaTest directly
- [ ] No `protected`, no `@uncheckedVariance`: use `private[kyo]` for cross-package visibility
- [ ] Kyo types used: `Maybe` not `Option`, `Result` not `Either`, `Chunk` not `List` (see [Types](CONTRIBUTING.md#types))

## Common Gotchas

1. **`kyo.System` shadows `java.lang.System`**: use fully qualified `java.lang.System` when needed
2. **Effect handlers are not inline**: `Abort.run`, `Var.run` are regular methods; only suspend/create methods are inline
3. **`Frame` required on every effectful method**, but not on pure data accessors like `capacity` or `size`
4. **Overloads delegate to canonical**: never duplicate logic across method variants
5. **`Scope`-managed resources are the default**: use `init`/`initWith` unless there's a specific reason for `initUnscoped`
6. **Unsafe tier mirrors safe tier**: every safe operation has an `Unsafe` equivalent; bridge via `Sync.Unsafe.defer`
