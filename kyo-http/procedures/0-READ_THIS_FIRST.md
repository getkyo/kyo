# kyo-http Procedures

Quality assurance pipeline for the kyo-http module. Each procedure produces artifacts that feed into the next.

## Context

The kyo-http module is a cross-platform HTTP framework targeting JVM (Netty), JS (Node.js), and Scala Native (h2o + libcurl). Demo applications in `kyo-http/shared/src/main/scala/demo/` exercise framework capabilities — routing, serialization, streaming, filters, error handling, OpenAPI generation, etc. These procedures use the demos as vehicles to find bugs, write tests, and fix issues.

## Procedures

1. **[Validate Demos](1-VALIDATE_DEMOS.md)** — Run each demo on all three platforms. Break things. Record bugs, platform differences, and usability issues. Output: `VALIDATION.md` in a run folder.

2. **[Plan Test Coverage](2-PLAN_TEST_COVERAGE.md)** — Turn validation findings into a test plan. Categorize bugs by what they reveal, audit existing tests, design reproduction scenarios, expand coverage. Output: `TEST_COVERAGE_PLAN.md`.

3. **[Write Tests](3-WRITE_TESTS.md)** — Implement the plan. Failing tests that reproduce bugs are the most valuable outcome. Run on all platforms. Output: new shared tests + `TEST_RESULTS.md`.

4. **[Fix Bugs](4-FIX_BUGS.md)** — Fix demo bugs first (so demos are reliable), then framework bugs one root cause at a time. Each fix validated by tests + demo. Clean up debug comments. Output: fixed code, all tests green.

5. **[Create Demos](5-CREATE_DEMOS.md)** — Map library features against existing demo coverage, identify gaps (especially client-side), and create new demos that showcase real-world usage of both client and server APIs. Output: coverage matrix, new demos, improved existing demos.

## Run folder structure

Each run lives in `procedures/runs/<N>/` (e.g., `runs/1/`):

```
runs/1/
├── VALIDATION.md         # from procedure 1
├── TEST_COVERAGE_PLAN.md # from procedure 2
└── TEST_RESULTS.md       # from procedure 3
```

## Bash command rules

These rules apply to **all procedures**. Every bash command must be simple and standalone — no compound commands that require manual approval.

- **No subshells or command substitution.** Never use `$()` or backticks in commands.
- **No output redirection.** Never use `>`, `>>`, or `2>&1` to write to files. Use the Write/Edit tools instead.
- **No backgrounding with `&`.** Use `run_in_background: true` on the Bash tool instead.
- **No multi-line commands.** One simple command per invocation. No `&&`, `;`, or `||` chaining.
- **No unnecessary sleeps.** Poll with `TaskOutput` instead of sleeping. If you must wait, keep it under 5 seconds.
- **Keep commands obvious.** If a reader can't understand the command instantly, it's too complex. Break it into multiple simple calls.

**Why:** Complex commands trigger approval prompts, breaking flow and wasting the user's time. Every approval prompt is a failure to follow these rules.

## Key principles

- **One demo at a time, all platforms.** Test BookmarkStore on JVM, JS, Native before moving to PasteBin. Cross-platform differences surface immediately.
- **Tests are the specification.** Procedure 4 fixes code to match tests, never the reverse.
- **Failing tests are valuable.** A test that fails on Native but passes on JVM proves a platform inconsistency exists. Don't delete it — fix the code.
- **No reward hacking.** Don't weaken assertions, skip platforms, or delete tests to get green. The goal is to find and fix real bugs.
