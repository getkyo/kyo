# Procedure 2: Plan Test Coverage

> **Before starting:** Read [0-READ_THIS_FIRST.md](0-READ_THIS_FIRST.md), especially the **Bash command rules**.

Turn validation findings into a concrete plan for reproducing bugs and increasing test coverage. This procedure takes the output of [Procedure 1: Validate Demos](1-VALIDATE_DEMOS.md) and produces a plan that [Procedure 3: Write Tests](3-WRITE_TESTS.md) will execute.

**Do not write test code during this procedure.** The output is a plan, not tests.

## Process

Each step produces a concrete section in the plan file. Complete each step fully and write its output before moving to the next.

### Step 1: List all findings

Read the `VALIDATION.md` from the run folder. Extract every bug, failure, partial failure, and observation into a flat list. For each item, record:

- The exact symptom
- The platform it was observed on
- The demo it was found in
- Whether it's a demo bug or a code bug

Demo bugs should be fixed directly (wrong demo code, platform-specific APIs in shared code, hardcoded ports). They don't need test coverage — they need demo fixes. List them explicitly in the plan file as **demo issues to fix**, with the specific file, line, and recommended fix. Example: if a demo uses `Stream.repeatPresent` + `Async.delay` and that pattern never produces data, that's a demo bug — the fix is to change the demo to use a working pattern, not to write a framework test.

Code bugs are the focus of this procedure.

**Output:** A numbered list of all findings, with demo bugs separated. Write this to the plan file before proceeding.

### Step 2: Categorize by what each bug reveals

Don't think about bugs individually — think about what **category of behavior** each bug exposes. A single bug usually points to a whole family of untested scenarios.

Example: "SSE returns 0 bytes on Native" reveals that no test exercises streaming with delayed chunks. The category is "streaming timing" and it contains many scenarios: delayed first chunk, gaps between chunks, empty streams, infinite streams with delays.

Example: "500 response has empty body on Native" reveals that no test checks the body content of a 500 response. The category is "error response format" — existing tests assert `status == 500` but never look at the body.

For each bug, ask:
- What category of framework behavior does this expose?
- What assumptions do existing tests make that this bug violates?
- What other scenarios in this category are also untested?

**Output:** Categories with their bugs and challenged assumptions. Append to the plan file.

### Step 3: Audit existing tests

Read the shared tests (`kyo-http/shared/src/test/scala/`) and map what's covered against each category:

- Find every existing test that touches the category
- Note what each test **actually asserts** — not just what it exercises. A test that sends a streaming response but only checks the status code doesn't test streaming.
- Identify assumptions baked into existing tests: Are all streams finite? Are all chunks immediately available? Do error tests check response bodies or just status codes?

The gap is the difference between what the validation revealed and what the tests verify.

**Output:** For each category, a list of existing tests with what they assert, and the identified gaps. Append to the plan file.

### Step 4: Design test scenarios for bug reproduction

For each category, list specific test scenarios that would reproduce the bugs found. Each scenario is a one-line description of what the test does and what it asserts.

**Compare each scenario against the actual demo code.** Read the demo source and identify the specific APIs, stream construction patterns, and configurations it uses. Then verify your test scenario exercises the same code path. A test that uses `bodySseText` with a finite `Stream.init` does NOT reproduce a demo that uses `getSseJson` with an infinite `Stream.repeatPresent`. **Write the comparison into the plan** — for each scenario, include:

- The exact demo code pattern (API, stream construction, handler type, filter chain)
- The proposed test code pattern
- Any differences between them and why each difference is acceptable (or not)

If the comparison reveals the test wouldn't exercise the same code path as the demo, redesign the test.

Prioritize:
1. **Direct reproduction** — mirrors the failing demo scenario exactly, using the same APIs and patterns
2. **Minimal reproduction** — strips away only what's truly irrelevant (external API calls, specific data models) while preserving the structural pattern (stream construction, handler API, timing characteristics)
3. **Variant scenarios** — other untested cases in the same category that could reveal additional issues

For each scenario, note which bug it reproduces.

**Output:** Test scenarios grouped by category, with demo code comparisons. Append to the plan file.

### Step 5: Expand coverage beyond the bugs

The bugs pointed to under-tested categories. Now think about what **else** in those categories is untested, even if no demo exposed it. The demos are a sample — the test suite should cover the population.

**Systematically enumerate dimensions.** Don't just brainstorm a few extra scenarios — build a matrix. The key dimensions are:

- **HTTP methods**: GET, POST, PUT, PATCH, DELETE, HEAD, OPTIONS (every feature should be tested across all applicable methods, not just the one where the bug was found)
- **Response types**: JSON, text, binary, SSE, NDJSON, byte stream, empty/204, multipart
- **Request types**: JSON, text, form, binary, multipart, streaming, empty
- **Error sources**: router (404, 405), handler (throw, Abort.fail), filter (rejection), framework (parse error, timeout)
- **Stream patterns**: finite, infinite, empty, delayed first emit, gaps between emits, error mid-stream
- **Filter combinations**: none, single filter, chained filters, filter + streaming, filter + error mapping

For each category from Step 2, create a grid of dimensions × existing coverage. Identify which cells have tests and which don't. Example: if HEAD Content-Length is wrong on GET endpoints, check whether HEAD is tested on POST, PUT, DELETE, streaming, empty-body, and error endpoints too.

The output should make it obvious what's covered and what isn't — not through prose, but through explicit matrices or tables with ✓/✗ marks.

Add untested combinations as additional scenarios, marked as coverage expansion (not bug reproduction).

**Output:** Coverage matrices and expansion scenarios. Append to the plan file.

### Step 6: Present for approval

The plan is complete. Present it and wait for approval before proceeding to [Procedure 3: Write Tests](3-WRITE_TESTS.md).

## Output

Written to `kyo-http/procedures/runs/<platform>-<N>/TEST_COVERAGE_PLAN.md`:

1. Numbered list of all code bugs (demo bugs noted separately)
2. Categories with the bugs that belong to each and the assumptions they challenge
3. Audit of existing tests per category — what they assert and where the gaps are
4. Bug reproduction scenarios grouped by category, with links to which bugs they reproduce
5. Coverage expansion scenarios grouped by category, exploring untested dimensions

This output is the input to [Procedure 3: Write Tests](3-WRITE_TESTS.md).
