# UI Validation Procedure

Validate web and JavaFX rendering of shared UIs — visual comparison, gap fixing, and interaction testing.

**IMPORTANT**: After context compaction, ALWAYS re-read this file before continuing work.

**Autonomy**: Do NOT stop to ask the user for approval between phases. Work through all phases continuously. Only stop if you encounter an error you truly cannot resolve.

**Full paths**: ALWAYS output full absolute paths for generated files so the user can click or copy-paste directly.

**Session directory**: All output files go into `kyo-ui/sessions/<session-name>/`. Throughout this procedure, `<session-dir>` refers to this directory.

## Rules

### Tool Usage

- **Use only Read, Edit, Write, and Glob tools** to work with files. NEVER use Bash to edit files.
- **Bash is only for sbt commands and the provided scripts.** Keep Bash calls simple — no `$()`, pipes, multi-line commands, `2>&1`, or `&`. To run a script in the background, use the Bash tool's `run_in_background` parameter — never shell backgrounding syntax.

### Generic-Only Fixes

All fixes MUST be generic backend behavior, NEVER specific to the UI being tested.

Before implementing any fix, ask: "Would this fix be correct for ANY UI built with the Style DSL, not just this one?"

**FORBIDDEN** — signs of UI-specific tuning:
- Hardcoding colors, sizes, or spacing values
- Blanket layout rules that assume content structure
- Adding behavior that HTML/CSS does NOT have by default
- Fixing a gap by adding a default that only makes THIS UI look right

**ALLOWED** — generic backend improvements:
- Correctly mapping a Style DSL prop to its JavaFX equivalent
- Fixing a rendering bug where a style prop is ignored or applied incorrectly
- Matching HTML/CSS default behavior
- Fixing node type conversions

**Test**: For each proposed fix, describe a DIFFERENT UI where the fix would also be correct. If you can't, it's UI-specific — don't do it.

**Platform differences are NOT bugs**: JavaFX and browsers have different default font rendering, text colors, anti-aliasing, and sub-pixel layout. Only fix gaps caused by incorrect Style prop handling.

### Gap Statuses

- **Fixed** — gap resolved, verified in screenshot
- **Pending** — not yet attempted
- **Investigating** — attempted but needs more work; document what was tried
- **Platform difference** — inherent rendering difference; the ONLY acceptable reason to not fix

Do NOT use "Won't fix" — layout semantic gaps and missing mappings are all fixable.

### Gap Severity

- **High**: Visually broken, layout wrong, missing content
- **Medium**: Noticeable difference at a glance
- **Low**: Subtle difference, only visible on close inspection

## Key Files

| File | Purpose |
|------|---------|
| `kyo-ui/shared/src/main/scala/demo/*.scala` | UI definitions (one per showcase) |
| `kyo-ui/jvm/src/main/scala/demo/JavaFxScreenshot.scala` | Takes UI name, produces both JFX and web screenshots |
| `kyo-ui/js/src/main/scala/demo/DemoApp.scala` | Web entry point, reads `window.location.hash` to pick UI |
| `kyo-ui/demo.html` | HTML shell that loads the JS bundle |
| `kyo-ui/shared/src/main/scala/kyo/Style.scala` | Style DSL (pure data, no rendering) |
| `kyo-ui/shared/src/main/scala/kyo/internal/CssStyleRenderer.scala` | Style → CSS string |
| `kyo-ui/shared/src/main/scala/kyo/internal/FxCssStyleRenderer.scala` | Style → FxCSS string |
| `kyo-ui/jvm/src/main/scala/kyo/JavaFxBackend.scala` | JavaFX backend (includes `applyStyleProps`) |
| `kyo-ui/jvm/src/main/scala/demo/JavaFxInteraction.scala` | JFX interaction helper (click, getText, fillText, etc.) |
| `kyo-ui/jvm/src/main/scala/demo/InteractiveSession.scala` | Interactive session — file-based command loop |

### Scripts

All scripts use `kyo-ui/sessions/.current` to track the active session. All output goes to `kyo-ui/sessions/<session-name>/`.

| Script | Purpose |
|--------|---------|
| `./kyo-ui/start-session.sh <name>` | Create session dir, start interactive session (run in background) |
| `./kyo-ui/wait-session.sh` | Poll for session READY status (60s timeout) |
| `./kyo-ui/ui-cmd.sh <command>` | Send a command to interactive session, wait for result |
| `./kyo-ui/stop-session.sh` | Kill all InteractiveSession processes (use for crash recovery) |
| `./kyo-ui/take-screenshots.sh [ui1 ui2 ...]` | Static screenshots into `<session>/static/` |

### Session Directory Structure

```
kyo-ui/sessions/<session-name>/
  cmd.txt / result.txt        # transient session communication
  session-name.txt            # session name (written by InteractiveSession)
  static/                     # Phase 2 static screenshots
    javafx-demo.png
    web-demo.png
  comparison.md               # Phase 3 visual comparison notes
  gap-report.md               # Phase 4 gap report
  qa-analysis.md              # Phase 5 QA reflection
  test-plan.md                # Phase 5.2 interaction test plan
  test-report.md              # Phase 5.4 interaction test report
  report.html                 # Phase 5.5 HTML report
  demo-initial-jfx.png        # Phase 5.3 interaction screenshots
  demo-initial-web.png
```

### Available UIs

The `JavaFxScreenshot` and `DemoApp` both have a registry of UIs. When adding a new UI, register it in **both** files plus `InteractiveSession.scala`.

## Task Tracking

At the start of this procedure, create a task list using `TaskCreate` to track progress. Mark tasks `in_progress` when starting, `completed` when done. This ensures no phase is skipped.

Create these tasks (descriptions below include key reminders — copy them verbatim):

1. **"QA reflection: plan testing approach"**
   Description: "Before touching any tool, think like a QA engineer. Read the UI source files under test. For each UI, ask: What can a user do here? What are the happy paths? What are the edge cases? What could break differently on JFX vs web? Write a brief QA analysis to `<session-dir>/qa-analysis.md` covering: (1) which UIs have the most interaction surface, (2) which features are most likely to diverge between backends, (3) what a thorough test of each UI looks like — not just 'click a button' but the full user journey. This analysis informs all later phases."

2. **"Phase 1-2: Section inventory + screenshots"**
   Description: "Read each UI source and produce section inventories. Then compile (`sbt 'kyo-ui/test'` + `sbt 'kyo-uiJS/fastLinkJS'`), take screenshots with `./kyo-ui/take-screenshots.sh`. Verify section count in screenshots matches inventory. REMINDER: Count sections in EACH image before proceeding."

3. **"Phase 3: Section-by-section visual comparison"**
   Description: "Compare ONE section at a time: (A) Read web screenshot — describe only this section, (B) Read JFX screenshot separately — describe only this section, (C) Write comparison to file, (D) Move to next. REMINDER: Never batch-evaluate. Never read both screenshots before writing notes. One section per read, period."

4. **"Phase 4: Visual gap report + fixes"**
   Description: "Write gap report to `<session-dir>/gap-report.md`. Classify each gap (style bug / missing mapping / platform difference / layout semantic gap) BEFORE fixing. REMINDER: All fixes must be generic — describe a DIFFERENT UI where the fix would also be correct. Max 3 attempts per gap. Web is the reference."

5. **"Phase 5.1-5.2: Start session + write test plan"**
   Description: "Start interactive session (`./kyo-ui/start-session.sh <session-name>` in background, `./kyo-ui/wait-session.sh`). Then write test plan to `<session-dir>/test-plan.md`. REMINDER: Enumerate EVERY interactive element in every UI. Every button, input, checkbox, select, toggle (on AND off), conditional (show AND hide), dynamic list (add AND remove). Include selectors, actions, and expected results. Do NOT start executing tests until the plan is written."

6. **"Phase 5.3: Execute interaction tests"**
   Description: "Execute each test plan row on BOTH platforms. Pattern: (1) act on JFX, (2) verify on JFX, (3) act on web, (4) verify on web, (5) IMMEDIATELY append result to `<session-dir>/test-results.md`. REMINDER: 'OK: clicked' means the click happened, NOT that the UI updated. You MUST check resulting state with jfx-text/web-text. Take screenshot after each UI's tests complete. CHECKPOINT: After completing each UI's tests, re-read `test-results.md` to verify all results were written. If a session crash causes context loss, the file has the ground truth."

7. **"Phase 5.4-5.5: Test report + HTML report"**
   Description: "Write test report to `<session-dir>/test-report.md` with per-test expected/actual/pass-fail for both platforms. Generate HTML report at `<session-dir>/report.html` with side-by-side screenshots, tabs per UI, status dots. REMINDER: Output FULL ABSOLUTE paths. Use var not const/let for Safari compat."

## Phase 1: Read the UI Source

Find the source file in `kyo-ui/shared/src/main/scala/demo/`. Read it and produce a **section inventory** — a numbered list of all visually distinct sections. For each section, note:
- Section name (from headings, card groupings, or structural role)
- What elements it contains (buttons, inputs, text, tables, lists)
- What Style props are exercised (colors, layout, spacing, borders, transforms)

This inventory drives Phase 3 (visual) and Phase 5 (interaction).

## Phase 2: Generate Screenshots

### Step 1: Compile and build JS

```
sbt 'kyo-ui/test'
sbt 'kyo-uiJS/fastLinkJS'
```

All tests must pass and JS must build before proceeding.

### Step 2: Take screenshots

```
./kyo-ui/take-screenshots.sh                      # all UIs
./kyo-ui/take-screenshots.sh demo layout reactive  # specific UIs
```

Each produces:
- `kyo-ui/sessions/<session>/static/javafx-<UI_NAME>.png`
- `kyo-ui/sessions/<session>/static/web-<UI_NAME>.png`

Uses a tall viewport (1280x4000) to capture full UI without scrolling. JavaFX can only initialize once per JVM — the script handles this.

## Phase 3: Section-by-Section Visual Comparison

**Key principle**: Read both images separately for EACH section. This prevents attention gaps from batch evaluation.

Process ONE section at a time with exactly 4 steps:

### Step A: Read web screenshot
- ONE Read tool call. Describe ONLY this section. Write 3-5 bullet points.

### Step B: Read JavaFX screenshot
- SEPARATE Read tool call. Describe ONLY this section. Write 3-5 bullet points.

### Step C: Compare and write to file
- Compare Step A and Step B notes. Append result to `<session-dir>/comparison.md`.

### Step D: Move to next section.

**Common mistakes to avoid:**
- ❌ Reading all sections at once from one screenshot
- ❌ Reading both screenshots before writing notes — write after EACH read
- ❌ Combining Step A and Step B into one message

After generating screenshots, count visible sections in each image and compare against the inventory. If sections are missing or truncated, investigate before proceeding.

**Dimensions to check per section:** Layout, spacing, colors, typography, borders, sizing, shadows, interactive elements.

## Phase 4: Visual Gap Report

Output: `<session-dir>/gap-report.md`

**Classify each gap BEFORE proposing a fix:**
- **Style bug**: Style DSL prop not applied or applied incorrectly
- **Missing mapping**: Style prop has no JavaFX equivalent
- **Platform difference**: Different default rendering (do NOT fix)
- **Layout semantic gap**: HTML/CSS layout model differs from JavaFX

**Web is the reference.** JavaFX should match web output.

### Format

```
# Gap Report

## Summary
<total gaps, fixable count, platform differences count>

## Gaps

| # | UI | Section | Dimension | Web (reference) | JavaFX (actual) | Severity | Classification | Status |
|---|-----|---------|-----------|-----------------|-----------------|----------|----------------|--------|
| 1 | demo | Header | Colors | Blue bg, white text | No bg color | High | Style bug | Fixed |
```

## Phase 4b: Fix Visual Gaps

1. **Max 3 fix attempts per gap per session.** Document what was tried if unresolved.
2. **All fixes must be generic.** Describe a DIFFERENT UI where the fix would also be correct.
3. **Verify after each fix batch.** Take fresh screenshot and confirm.
4. **Don't regress other UIs.** Spot-check at least one other UI after fixes.

## Phase 5: Interaction Testing

Validate that reactive behaviors work correctly on **both** JFX and web. Static screenshots cannot verify that buttons work, signals propagate, or conditional rendering toggles.

### 5.1: Start the Interactive Session

Run `./kyo-ui/start-session.sh <session-name>` using the Bash tool with `run_in_background: true`. Then run `./kyo-ui/wait-session.sh` (foreground) to poll until READY.

### 5.2: Generate a Test Plan

For EACH interactive UI, read the source file and enumerate **every interactive element**. Write the test plan to `<session-dir>/test-plan.md` BEFORE executing any tests.

For each element, specify:
- **Element**: what it is (button, input, checkbox, select, etc.) and its selector
- **Action**: what to do (click, fill "test text", check, select option)
- **Expected result**: what should change, stated as a verifiable assertion
- **Verify on**: BOTH platforms (always)

Example test plan entry:
```
### DemoUI

| # | Element | Selector | Action | Expected (JFX) | Expected (Web) |
|---|---------|----------|--------|-----------------|-----------------|
| 1 | Counter + button | `.counter-btn` idx 1 | click | `.counter-value` text = "1" | `.counter-value` text = "1" |
| 2 | Counter + button | `.counter-btn` idx 1 | click again | `.counter-value` text = "2" | `.counter-value` text = "2" |
| 3 | Counter - button | `.counter-btn` idx 0 | click | `.counter-value` text = "1" | `.counter-value` text = "1" |
| 4 | Todo input | `.input` idx 0 | fill "Buy milk" | input contains "Buy milk" | input contains "Buy milk" |
| 5 | Todo Add button | `.submit` | click | `.ul` contains "Buy milk" | `ul` contains "Buy milk" |
| 6 | Theme toggle | `.theme-toggle` | click | background changes | background changes |
```

**Coverage requirements** — the test plan MUST cover:
- Every button (click + verify result)
- Every input/textarea (fill + verify value captured)
- Every checkbox (toggle + verify state)
- Every select/dropdown (change selection + verify)
- Every toggle (on AND off, not just one direction)
- Every conditional element (show AND hide)
- Every dynamic list (add AND remove items)
- Multi-step sequences (e.g., fill form fields THEN submit)
- Edge cases where relevant (empty input submit, double-click, etc.)

### 5.3: Execute Tests

For each test plan row, execute the action on BOTH platforms and record the actual result.

**Execution pattern for each test:**
1. Execute action on JFX (e.g., `./kyo-ui/ui-cmd.sh jfx-click-nth .counter-btn 1`)
2. Verify result on JFX (e.g., `./kyo-ui/ui-cmd.sh jfx-text .counter-value` → assert "1")
3. Execute action on web (e.g., `./kyo-ui/ui-cmd.sh "web-js document.querySelectorAll('.counter-btn')[1].click()"`)
4. Verify result on web (e.g., `./kyo-ui/ui-cmd.sh web-text .counter-value` → assert "1")
5. **IMMEDIATELY** append result to `<session-dir>/test-results.md` using Edit/Write tool

**CRITICAL**: Do not skip verification. "OK: clicked" means the click happened, NOT that the UI updated correctly. You MUST check the resulting state.

**CRITICAL**: Write results to the file AFTER EACH TEST, not in batches. Session crashes lose all in-memory state. The file is the only durable record.

**CHECKPOINT**: After completing each UI's tests, re-read `test-results.md` to confirm all results are present before moving to the next UI.

Take a screenshot after each UI's tests are complete (not after each individual test).

#### test-results.md Structure

The file is a **QA report**, not a raw log. It should be structured as follows:

```markdown
# Interaction Test Results

## Summary
- UIs tested: X / Y
- Tests executed: X / Y
- JFX pass: X, JFX fail: X
- Web pass: X, Web fail: X
- Bugs found: X
- Edge cases discovered: X

## Per-UI Results

### <UI Name>

**Coverage**: What user journeys were tested (e.g., "full counter lifecycle", "add/delete/reorder items", "form fill + submit + disable toggle")

#### Test Results

| # | Test | JFX Expected | JFX Actual | JFX | Web Expected | Web Actual | Web | Notes |
|---|------|-------------|------------|-----|-------------|------------|-----|-------|

#### Edge Cases Discovered
- List what unexpected behaviors, selector issues, or platform quirks were found while testing this UI
- Include things that weren't in the test plan but were discovered during execution

#### Observations
- Any visual differences noticed during interaction (e.g., "button flickers on JFX", "focus ring missing on web")
- State management notes (e.g., "signal updates propagate correctly across nested components")

## Bugs Found

| # | UI | Description | Repro Steps | Affected Platform | Severity | Classification |
|---|-----|-------------|-------------|-------------------|----------|----------------|

Classification: `backend-bug` (JFX/web rendering), `harness-bug` (test infrastructure), `design-gap` (missing feature)

## Test Infrastructure Issues
- Document any issues with the test harness itself (command timeouts, selector mismatches, session crashes)
- Include workarounds used

## Untested
- List any tests from the plan that could not be executed, with reasons
```

**Key principles:**
- The report should be useful to someone who wasn't present during testing
- Every bug needs repro steps, not just a description
- Edge cases are valuable findings — document them prominently
- Distinguish backend bugs from test harness bugs
- "Untested" is better than silently skipping tests

#### Available Commands

| Command | Description |
|---------|-------------|
| `render <name>` | Render UI on both JFX and Browser (closes previous) |
| `jfx-click <selector>` | Click first matching JFX node |
| `jfx-click-nth <selector> <n>` | Click Nth matching JFX node (0-indexed) |
| `jfx-text <selector>` | Get text from JFX node |
| `jfx-fill <selector> <text>` | Fill text in JFX text input |
| `jfx-count <selector>` | Count matching JFX nodes |
| `jfx-exists <selector>` | Check if JFX node exists |
| `jfx-screenshot <path>` | Take JFX screenshot |
| `jfx-dump` | Dump JFX scene tree (shows all classes/selectors) |
| `web-click <selector>` | Click web element (CSS selector) |
| `web-text <selector>` | Get innerText from web element |
| `web-fill <selector> <text>` | Fill web input |
| `web-count <selector>` | Count web elements |
| `web-exists <selector>` | Check if web element exists |
| `web-screenshot <path>` | Take web screenshot |
| `web-js <code>` | Run JavaScript in browser (quote the full command) |
| `screenshot <basepath>` | Take both screenshots (appends -jfx.png / -web.png) |
| `stop` | Shut down the session |

#### Selector Tips

- **JFX**: Nodes get classes from element type (`.button`, `.input`, `.div`, `.h1`, `.h3`, `.section`). User classes from `.cls("foo")` work. Use `jfx-dump` to discover selectors. For nth element, use `jfx-click-nth`.
- **Web**: Standard CSS selectors. For nth-child clicking where `web-click` is insufficient, use `web-js`: `./kyo-ui/ui-cmd.sh "web-js document.querySelectorAll('button')[2].click()"`
- **Finding selectors**: If unsure what selector to use, run `jfx-dump` for JFX tree or `web-js document.querySelector('.foo').textContent` for web. Read the UI source to find `.cls()` and `.id()` calls.

### 5.4: Interaction Test Report

Output: `<session-dir>/test-report.md`

### Format

```
# Interaction Test Report

## Summary
- Total tests: X
- JFX pass: X, JFX fail: X
- Web pass: X, Web fail: X

## DemoUI

| # | Test | Action | JFX Expected | JFX Actual | JFX | Web Expected | Web Actual | Web |
|---|------|--------|-------------|------------|-----|-------------|------------|-----|
| 1 | Counter increment | click + btn | "1" | "1" | PASS | "1" | "1" | PASS |
| 2 | Todo add | fill + click Add | "Buy milk" in list | "Buy milkx" | PASS | "Buy milk" in list | "Buy milk\nx" | PASS |
| 3 | Form submit | fill all + submit | shows values | "Not submitted yet" | FAIL | shows values | "Submitted: Name=..." | PASS |

## Bugs Found

| # | UI | Description | Affected Platform | Severity |
|---|-----|-------------|-------------------|----------|
| 1 | FormUI | form.onSubmit not firing | JFX | High |
```

### 5.5: Generate HTML Report

Generate a visual HTML report at `<session-dir>/report.html` with:
- One tab per tested UI
- Side-by-side JFX vs Web screenshots for each state (initial + after interactions)
- Color-coded status dots (green=pass, yellow=minor, red=fail)
- Prev/Next navigation for multi-frame UIs
- Findings annotation at the bottom of each tab

**Report generation notes:**
- Use `var` instead of `const`/`let` and `function(){}` instead of arrow functions for Safari compatibility
- Use `max-height: calc(100vh - 180px); object-fit: contain; object-position: top` for images
- Hide Prev/Next controls for single-frame tabs

## Pitfalls

These are implementation details that have caused problems in past sessions:

1. **Tables**: GridPane vs HTML table model is complex. Check rows render horizontally, columns distribute evenly, colspan/rowspan works.
2. **Animated UIs**: Take screenshots at a deterministic point (after a known delay) rather than racing animations.
3. **Sync.defer for JFX side effects**: When calling JFx methods inside kyo for-comprehensions, wrap with `Sync.defer { ... }`. Bare `_ = ...` assignments don't compose correctly.
4. **JFX fill targets**: `jfx-fill .some-class "text"` requires the selector to match a TextField/TextArea directly, not a parent container. Use `jfx-dump` to find the right selector.
5. **Web page reload for render**: The `render` command changes the hash and reloads the page. After render, wait before sending commands. If web seems stale, the reload may not have completed.
6. **Session crash recovery**: If commands time out, the session may have crashed. Run `./kyo-ui/stop-session.sh` to kill it, then `./kyo-ui/start-session.sh <name>` (background) + `./kyo-ui/wait-session.sh` to restart. NEVER use raw `pkill` or `ps` — always use the scripts.
7. **Multiple sessions**: Run `./kyo-ui/stop-session.sh` before starting a new session. Multiple sessions conflict on the cmd.txt/result.txt files.
8. **Web selector mismatch**: JFX adds the HTML tag name as a CSS class (`.button`, `.input`, `.div`), but on web these are actual HTML tags without that class. For web, use tag selectors (`button`, `input`) or `web-js` with `document.querySelectorAll('button')[N].click()`. For JFX, use `.button`, `.input`, etc.
9. **Playwright hangs**: `web-fill`, `web-exists`, and `web-click` can hang indefinitely if the selector doesn't match (Playwright's waitForSelector has no timeout). This blocks the entire command loop. If any web command times out, assume the session is dead — stop and restart with the scripts. Prefer `web-js` for operations that might not find elements, since you can add null checks.
10. **web-js requires `return`**: Always prefix `web-js` expressions with `return`. Without it, Playwright's evaluate hangs waiting for a return value. Also wrap in try/catch to prevent null dereference from killing the session. Pattern: `web-js try { return document.querySelector('x').textContent } catch(e) { return 'ERROR: ' + e.message }`
