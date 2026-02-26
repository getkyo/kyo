# Procedure 3: Write Tests

Implement the test scenarios from [Procedure 2: Plan Test Coverage](2-PLAN_TEST_COVERAGE.md). A failing test that reproduces a bug is the most valuable outcome.

## Integrity

LLMs writing tests face a specific failure mode: **reward hacking**. The path of least resistance to "all tests pass" is to write tests that can't fail, weaken assertions, or skip platforms where things break. This defeats the purpose of testing.

- **Tests describe expected behavior, not current behavior.** If the code returns an empty body on error but should return a JSON error message, the test asserts a non-empty JSON body. The test fails. That's correct.
- **Never weaken an assertion to make a test pass.** If `assert(body.nonEmpty)` fails, do not change it to `assert(true)`. The failure is the finding.
- **Never modify existing tests to accommodate new code.** If new code breaks an existing test, the new code is wrong. The only exception is a genuinely incorrect test — call it out explicitly with reasoning.
- **Never skip tests on a platform.** A test that fails on Native but passes on JVM stays and fails. That cross-platform inconsistency is what the test caught.
- **Never change framework code during this procedure.** Write the tests, run them, record what passes and fails. Fixes are a separate step.
- **A failing test is more valuable than a passing test.** A passing test confirms something works. A failing test reveals something broken. Both are useful, but discovering bugs is the primary goal.
- **Don't write tests that can't fail.** A test that asserts `status == 200` on a hardcoded response handler isn't testing anything. Tests should exercise behavior that could plausibly go wrong.

## Writing tests

Use the approved plan from `TEST_COVERAGE_PLAN.md`. Each test should use the existing test infrastructure (`withServer`, `send`, `client.connectWith`). Don't introduce new test utilities unless absolutely necessary.

**Find the right place in the test file.** Before writing, read the existing test file structure — its sections, naming conventions, and grouping patterns. Add new tests where they belong logically, not just at the end of the file. If there's an existing section for the category (e.g., a "handler errors" block), add the new test there. If the test opens a new category, place the new section near related existing sections. A well-organized test file is easier to maintain and helps future readers understand the coverage map.

For each test:

- **Name it descriptively** — the name describes the scenario: "SSE with delayed first event", "500 response has non-empty body", "bind to occupied port reports port number". Not "test1", not "fix bug #42".
- **Do not add comments referencing specific bugs or validation runs.** Tests should stand on their own — their name and assertions describe the expected behavior. Bug references belong in the `TEST_COVERAGE_PLAN.md` and `TEST_RESULTS.md` files, not in the test source code.
- **Assert behavior precisely** — check status codes, response bodies, headers, streaming content. Vague assertions leave room for bugs to hide.
- **Minimal setup** — the simplest test that exercises the behavior. Don't add routes, filters, or configuration irrelevant to what's being tested.
- **Independence** — each test works in isolation. No shared mutable state between tests, no execution order dependencies.

### What makes a test useful

Ask: "If someone introduced a bug in [component X], would this test catch it?" If no, the test isn't exercising enough.

**Not useful:**
- Tautologies — assert a hardcoded value equals itself
- Overly broad assertions — `assert(result != null)` when you could check the actual value
- Happy-path-only tests of simple operations unlikely to break
- Tests with no assertions

**Useful:**
- Exercise behavior at boundaries (empty input, maximum size, timeout)
- Verify error cases produce specific, useful responses (not just "some error")
- Check behavior consistency across platforms (same input → same output)
- Test timing-dependent behavior (streaming with delays, concurrent requests)

## Validating test quality

Before considering a test done, verify it's actually testing what you think:

- **Does it reproduce the actual scenario?** This matters specifically for **bug reproduction tests** — tests designed to fail because they reproduce a known bug. Go back to the demo source code and compare: does the test use the same handler API, the same stream construction pattern, the same timing characteristics? A test that uses `bodySseText` with a finite stream and 500ms delay does NOT reproduce a demo that uses `getSseJson` with an infinite `Stream.repeatPresent` and 10s delay. Strip away external dependencies (API calls, specific data models) but preserve the structural pattern.

### Bug reproduction tests vs coverage expansion tests

Tests from the coverage plan fall into two categories with different expectations:

**Bug reproduction tests** target a known bug. They are expected to **fail** — that's how they prove the bug exists. If a bug reproduction test passes:
1. First, check whether the test is actually exercising the same code path as the demo. Compare APIs, patterns, and timing. If the test oversimplifies the scenario, it may pass while the real bug still exists. Fix the test to match the demo more faithfully.
2. If the test faithfully reproduces the demo pattern and still passes, the bug may be in the demo itself — not in the framework. Go back to the demo source and look for issues (interaction with external APIs, data model mismatches, subtle usage errors). If it's a demo bug, fix the demo.

**Coverage expansion tests** cover previously untested scenarios that no demo exposed as broken. They are expected to **pass** — they exist to prevent future regressions and ensure cross-platform consistency. If a coverage expansion test fails, you've discovered a new bug, which is valuable — record it.
- **Could this test fail?** If you can't imagine a plausible code change that would break it, the test is too weak.
- **Does it assert the right thing?** A streaming test that only checks status code doesn't test streaming. An error test that only checks status doesn't test error format.
- **Is it minimal?** If you remove a line and the test still passes, that line wasn't contributing. If you remove a line and the test fails, every line matters.

## Running and recording

Run every new test on **all three platforms** (JVM, JS, Native). A test that only runs on one platform can hide platform-specific bugs — the whole point of shared tests is cross-platform consistency. Don't stop after JVM passes.

For each new test, record per platform:

- PASS, FAIL, or CRASH (test runner itself crashes, e.g., unhandled errors on JS/Native)
- If it fails, record the exact error output — this is the reproduction of the bug
- If it crashes, record why — a crash is itself a finding (the framework isn't catching errors properly on that platform)
- If it passes everywhere, it still has value as coverage, but reconsider whether it's actually testing the right thing
- Note any tests that pass everywhere but that you suspect might be too weak
- Note any tests you wanted to write but couldn't (e.g., scenarios that would break compilation for an entire platform)

A test that passes on JVM and fails on Native is a perfect outcome — it proves the inconsistency exists and pinpoints where. A test that fails on all three platforms is equally valuable — it proves the bug is framework-wide, not platform-specific.

## Output

- New shared tests in `kyo-http/shared/src/test/scala/`
- Test results written to `kyo-http/procedures/runs/<platform>-<N>/TEST_RESULTS.md`:
  - Which tests pass and fail, per platform
  - Exact error output for failing tests
  - Tests that seem too weak or that couldn't be written
  - Comments in failing tests linking back to the validation report

Failing tests are not technical debt — they are documentation of known behavioral gaps. They become the specification for future fixes.
