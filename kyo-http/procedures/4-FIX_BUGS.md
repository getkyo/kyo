# Procedure 4: Fix Bugs

> **Before starting:** Read [0-READ_THIS_FIRST.md](0-READ_THIS_FIRST.md), especially the **Bash command rules**.

Fix the bugs surfaced by [Procedure 3: Write Tests](3-WRITE_TESTS.md). The failing tests are your specification — a fix is correct when the test passes and the demo works. Do not modify tests to match your fix.

## Integrity

The same reward-hacking risk from test writing applies here, but in reverse. The path of least resistance to "all tests pass" is to weaken, skip, or delete the tests that fail. **The tests are the reference. The code is what changes.**

- **Never modify a test to make it pass.** If a test asserts a non-empty error body, the fix is to make the server return a non-empty error body — not to remove the assertion.
- **Never weaken an assertion.** If a test checks `status == 404`, do not change it to `status >= 400`.
- **Never delete a failing test.** A deleted test is a hidden bug.
- **Never skip a test on a platform.** If a test fails on Native, the fix must work on Native. Platform-specific `if` guards that disable assertions are test modifications in disguise.
- **If a test is genuinely wrong** (asserts incorrect expected behavior), explain why in detail, get approval, then fix the test. This should be rare — tests were written against the framework's intended behavior, not its current behavior.

## Process

### Step 1: Fix demo bugs first

Demo bugs (identified in `TEST_COVERAGE_PLAN.md`) must be fixed before framework bugs. Demo bugs are in demo source code — wrong API usage, platform-specific APIs in shared code, incorrect error types, etc. Fixing them first ensures that when you later validate framework fixes with demos, the demos themselves are already correct.

For each demo bug:

- Make the minimal change to the demo
- Verify the demo still compiles and runs on all platforms
- If a demo bug revealed a framework usability issue (confusing API, missing error message), note it but do not fix the framework here

### Step 2: Triage failing tests

Read `TEST_RESULTS.md` from the run folder. Group failing tests by root cause — multiple test failures often share a single underlying bug. Fix root causes, not symptoms.

For each root cause, identify:
- Which tests fail because of it
- Which platforms are affected
- Where in the framework the fix likely lives
- Whether fixing it could affect passing tests (regression risk)

**Output:** A prioritized list of root causes with their affected tests. Write to an analysis file.

### Step 3: Fix one root cause at a time

For each root cause, in priority order:

1. **Read the relevant framework code.** Understand the current behavior before changing it.
2. **Make the minimal fix.** Change the least amount of code necessary. Do not refactor, clean up, or "improve" surrounding code. Do not add features. A bug fix is a bug fix.
   - **Only change the specific code path exercised by the failing tests.** If you find the same problematic pattern in multiple code sites (e.g., error handling in a main path and in edge-case fallbacks), only fix the site that the failing tests actually exercise. The other sites may look wrong but they serve different purposes — changing them without test coverage risks regressing working behavior. If you believe the other sites also need fixing, note them for a separate fix with its own tests.
3. **Run the affected tests.** Verify they pass on all platforms.
4. **Run the full test suite.** Verify no regressions.
5. **Validate with the demo.** Run the demo that originally exposed the bug and confirm the fix works end-to-end (see "Running demos" below).
6. **Record the fix** — what changed, why, which tests now pass, and what the demo output looks like after the fix.

Move to the next root cause only after the current fix is verified with both tests and demos.

### Step 4: Clean up

After all fixes are verified:

- **Remove debug comments from tests.** Comments like `// Reproduces: SSE headers not flushed on Native` were useful during development but are noise in the final codebase. Test names and assertions should be self-documenting. Remove all references to validation runs, bug IDs, and procedure files from test source code.
- **Remove analysis files** created during this procedure.
- **Verify the full test suite passes on all platforms** one final time.

## Running demos

Use the same setup as [Procedure 1: Validate Demos](1-VALIDATE_DEMOS.md).

### Setting the main class

JVM demos can be run directly with `sbt 'kyo-http/runMain demo.XXX'`. For JS and Native, sbt's `runMain` doesn't work with cross-projects — you must set the main class in build.sbt temporarily.

**For JS**, add both settings to the `.jsSettings(...)` block:
```scala
.jsSettings(
    `js-settings`,
    // ... existing settings ...
    scalaJSUseMainModuleInitializer := true,  // required for JS
    Compile / mainClass := Some("demo.XXX")
)
```

**For Native**, add the main class to the `.nativeSettings(...)` block:
```scala
.nativeSettings(
    `native-settings`,
    Compile / mainClass := Some("demo.XXX"),
    // ... existing settings ...
)
```

Then run:
- JVM: `sbt 'kyo-http/run'` or `sbt 'kyo-http/runMain demo.XXX'`
- JS: `sbt 'kyo-httpJS/run'`
- Native: `sbt 'kyo-httpNative/run'`

Revert build.sbt changes when done.

### Port management

- **Before starting a demo**, check if its port is free: `lsof -i :<port>`
- **Kill stale processes** from previous runs: `ps aux | grep kyo-http`
- **After stopping sbt**, verify the port was released — sbt's `run` task sometimes doesn't clean up child processes.

## Code style

Fixes must match the project's coding standards. Read `CONTRIBUTING.md` for the full guide. Key points for bug fixes:

- **Classes must be `final`** unless `sealed` or `abstract`.
- **Use Kyo primitives** over stdlib equivalents: `Maybe` not `Option`, `Result` not `Either`, `Chunk` not `Seq`, `Duration` not `java.time.Duration`.
- **Use `.map` chains**, never explicit `.flatMap` on pending types. Use `.andThen` to discard and sequence, `.unit` to discard to `Unit`.
- **Use `discard(expr)`** to suppress unused value warnings, never `val _ = expr`.
- **No `protected`** — use `private[kyo]` for internal visibility.
- **Avoid unsafe casts** — `asInstanceOf` only inside opaque type boundaries when strictly necessary.
- **Avoid impure features** — no `var`, `while`, `return`, mutable collections, or `null` unless strictly necessary for performance. Use `@tailrec` recursive functions instead.
- **Performance matters** — avoid unnecessary allocations and suspensions. Use `inline` on creation paths, not handling paths.
- **Explain the surprising, skip the obvious** — a comment on a race condition is essential; a comment on a getter returning a value is noise.
- **Prefer `Abort.recover`** over `Abort.run` + `Result` pattern matching.

## Output

- Fixed demo code (step 1)
- Fixed framework code (step 3)
- Clean test files with no debug comments (step 4)
- Updated `TEST_RESULTS.md` showing all tests passing
- Demo validation notes confirming fixes work end-to-end
