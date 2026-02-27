# From Demo Results to Test Coverage

## Goal

Turn demo validation findings into shared tests that reproduce issues and increase coverage. The output is **tests, not fixes**. A failing test that reproduces a bug is the most valuable outcome of this procedure. Increasing coverage of under-tested scenarios is also a goal.

## Integrity Rules

**Do not reward-hack.** The point is to surface problems, not make them disappear.

- **Never modify or delete an existing test to make it pass.** If a test fails, that's signal. Leave it failing.
- **Never weaken an assertion** (e.g., removing a body check, loosening a status code match) to avoid a failure.
- **Never skip a test on a platform.** All demos should work on all platforms. A demo that doesn't work on one platform is a demo bug or a framework bug — either way, the test should exist and fail, not be skipped.
- **Never change framework code during this procedure.** The goal is to capture the current state of behavior in tests. Fixes come later, informed by the failing tests.
- **A failing test is a success.** It means you found a real gap. Mark it with a clear comment explaining what it reproduces and on which platform it fails, but do not make it pass.

## Process

Each step below produces a concrete artifact. Complete each step fully and write its output before moving to the next. Do not skip steps or combine them.

### Step 1: List all bugs from demo validation reports

Read every `DEMO_VALIDATION_<PLATFORM>.md` file. Extract every bug, failure, partial failure, and observation into a flat list. For each item, record:

- The exact symptom (what happened)
- The platform(s) it was observed on
- The demo it was found in
- Whether it's a demo bug (the demo code is wrong) or a code bug (the framework/library is wrong)

**Output:** A numbered list of all findings. Write this list to an analysis file before proceeding.

### Step 2: Categorize bugs by what they reveal about test coverage

Don't think about bugs individually — think about what **category of behavior** each bug exposes. A single demo bug usually points to a whole family of untested scenarios.

Example: "SSE returns 0 bytes on Native" is not just one bug. It reveals that **no test exercises streaming with delayed chunks**. The category is "streaming timing," and there are many scenarios within it: delayed first chunk, gaps between chunks, empty streams, infinite streams with delays.

Example: "WikiSearch returns 500 with empty body on Native" reveals that **no test checks the body content of a 500 response**. The existing test asserts `status == 500` but never looks at the body. The category is "unhandled error response format."

For each bug, ask:
- What category of framework behavior does this expose?
- What assumptions do the existing tests make that this bug violates?
- What other scenarios in this category are also untested?

**Output:** A list of categories, each with the bugs that belong to it and the assumptions they challenge. Append to the analysis file.

### Step 3: Audit existing shared tests against these categories

Read the shared tests (`kyo-http/shared/src/test/scala/`) and map what's covered:

- For each category from step 2, find every existing test that touches it
- Note what each test actually asserts — not just what it exercises. A test that sends a streaming response but only checks the status code doesn't actually test streaming.
- Identify the assumptions baked into existing tests: Are all streams finite? Are all chunks immediately available? Do error tests check response bodies or just status codes?

The gap is the difference between what the demos revealed and what the tests verify.

**Output:** For each category, a list of existing tests with what they assert, and the identified gaps. Append to the analysis file.

### Step 4: Design test scenarios

For each category, design specific test scenarios that would reproduce the bugs and cover the gaps. Each scenario is a one-line description of what the test does.

Prioritize:
1. **Direct reproduction** — a test that exactly mirrors the failing demo scenario
2. **Minimal reproduction** — strip away demo-specific details to isolate the framework behavior
3. **Variant scenarios** — other untested cases in the same category that could reveal additional issues

**Output:** A list of test scenarios grouped by category, with a note on which bug each reproduces (if any). Append to the analysis file. Present the plan and wait for approval before writing any test code.

### Step 5: Write the tests

After approval, add tests to the appropriate shared test files. Each test should use the existing test infrastructure (`withServer`, `send`, `client.connectWith`). Don't introduce new test utilities unless absolutely necessary.

For each test:

- **Name it descriptively** — the name should describe the scenario, not the bug (e.g., "SSE with delayed first event" not "fix native SSE bug")
- **Comment what it reproduces** — if the test targets a known bug, add a comment: `// Reproduces: SSE headers not flushed on Native (DEMO_VALIDATION_NATIVE.md)`
- **Assert behavior precisely** — check status codes, response bodies, headers, streaming content. Vague assertions defeat the purpose.
- **Don't over-engineer** — the test should be as simple as possible while still catching the issue

### Step 6: Run on all platforms and record results

Run the full test suite on each platform. For each new test:

- Record: PASS or FAIL, per platform
- If it fails, record the exact error — this is the reproduction of the bug
- If it passes everywhere, it still has value as coverage, but reconsider whether it's actually testing the right thing

A test that passes on JVM and fails on Native is a perfect outcome — it proves the behavioral inconsistency exists and pinpoints where.

### Step 7: Expand coverage beyond the bugs

The bugs pointed you to under-tested categories. Now think about what **else** in those categories is untested, even if no demo exposed it:

- If streaming timing is under-tested, also add: empty streams, streams that error mid-way, streams with backpressure
- If error response format is under-tested, also add: concurrent errors, errors during streaming, errors with non-serializable types
- If operational errors are under-tested, also add: port conflicts, shutdown behavior, connection limits

The demos are a sample. The test suite should cover the population.

Repeat steps 5 and 6 for the expanded tests.

## Demo bugs vs framework bugs

When categorizing bugs in step 2, distinguish between:

- **Framework bugs** — the kyo-http framework behaves incorrectly. These get reproduced as framework tests.
- **Demo bugs** — the demo source code is wrong (e.g., uses a broken API pattern, has a too-strict schema, missing error handling). These do NOT get framework tests. Instead, they are listed explicitly in the analysis file as **demo issues to fix**, with the specific file, line, and recommended fix. Demo bugs are fixed by changing the demo code, not by writing framework tests.

Example: if a demo uses `Stream.repeatPresent` + `Async.delay` and that pattern never produces data, that's a demo bug. The fix is to change the demo to use a working pattern. Writing a framework test for `Stream.repeatPresent` behavior belongs in kyo-prelude tests, not kyo-http tests.

## Output

The procedure produces:
1. **An analysis file** with the categorized bugs, audit of existing coverage, and planned test scenarios
2. **New shared tests** in `kyo-http/shared/src/test/scala/` — some passing, some failing
3. **A summary** of which tests fail on which platforms, with the exact errors
4. **Comments in failing tests** linking back to the demo validation reports
5. **A list of demo bugs to fix** — specific files, lines, and recommended changes for demo source code issues

Failing tests are not technical debt — they are documentation of known behavioral gaps. They become the specification for future fixes.
