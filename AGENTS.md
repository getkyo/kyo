# Agent Guide

This file helps AI coding agents work effectively on the Kyo codebase. Read [CONTRIBUTING.md](CONTRIBUTING.md) first — it is the comprehensive reference for all conventions, patterns, and design decisions.

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
| Integrations | `kyo-sttp`, `kyo-tapir`, `kyo-caliban`, `kyo-cats`, `kyo-zio`, ... | Library integrations |
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
