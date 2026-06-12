---
name: readme-verify
description: Three-gate verification for a finalized README: companion script structural check, chunk-by-chunk forward-reference walk, and skim-reader persona walkthrough with a line-grounded spine test.
argument-hint: <readme-path> <source-analysis-path>
---

# readme-verify

Final gate. The README is either a fresh draft from `readme-draft` or the post-edit output from `readme-edit`. This skill does not modify the README; it verifies and reports.

Five gates run in this order. If a gate fails, report the failure and stop. Downstream gates run only when upstream gates pass cleanly.

Gate 4 (source-coverage) and Gate 5 (catalog detection) are the load-bearing gates. Gates 1-3 check self-consistency, skim experience, and structural shape; they pass even when the README is missing entire chapters of public surface (Gate 4 catches that) or reads as a catalog rather than teaching (Gate 5 catches that). Gate 5 is the mechanical sibling of `readme-critique`: it surfaces signals; the critique skill makes the gestalt judgment.

## Gate 0: sbt doctest (MANDATORY, hard gate, runs first)

This gate is non-negotiable. EVERY fenced ```scala block in the README must compile via the project's sbt-kyo-doctest plugin. The script-level checks below are NOT a substitute. A README that the structural-script declares clean can still contain fabricated API names, hallucinated type syntax, dead `val` placeholders, or `.copy` calls on `private[kyo]` constructors. Only the compiler catches those.

Run:

```
sbt '<module-sbt-name>/doctest' 2>&1 | tee /tmp/doctest-verify.log
```

For kyo-data that is `sbt 'kyo-dataJVM/doctest'`. For kyo-http that is `sbt 'kyo-httpJVM/doctest'`. Use the project name `git grep -nE "^lazy val ..kyo-<name>." build.sbt` resolves to ; do not invent the sbt module name.

Parse the log's last summary line:

```
[info] doctest: total=N compiled=N cacheHits=N failures=N
```

Pass conditions (ALL must hold):

- sbt exit code == 0.
- `failures=0` in the summary.
- The string `doctest: validation failed` does NOT appear anywhere in the log.

Fail conditions (ANY triggers BLOCK):

- sbt exit code != 0.
- `failures=` value > 0.
- The phrase `doctest: validation failed` appears.
- Doctest reports "skipped: <module> not wired to kyo-doctest" AND the module SHOULD be wired (kyo-* modules under the kyo monorepo are wired by default unless explicitly disabled via `.disablePlugins(KyoDoctestPlugin)`; if the verify agent encounters "skipped" for such a module, that is a build-config bug, not a pass).

The verify report MUST echo the summary line literally and the exit code. Do NOT declare PASS without these two pieces of evidence in the report.

If sbt is unavailable in the agent's environment (rare), the agent MUST explicitly report `Gate 0: BLOCKED — sbt not available, cannot validate doctest` and exit 1. The supervisor then runs doctest manually before re-dispatch. NEVER silently skip Gate 0.

### "Where applicable" is NOT an escape hatch

Earlier versions of this skill said "run with-doctest where applicable." That language was a footgun: it let agents declare PASS when they couldn't be bothered to figure out the sbt project name, or when the structural script reported "doctest cache: skipped." Both shipped broken READMEs. The rule now: every README that ships into a sbt-kyo-doctest-wired build MUST pass `sbt '<module>/doctest'` before verify PASS. The only exceptions:

- The module is in `.disablePlugins(KyoDoctestPlugin)` in build.sbt (verifiable via `grep`).
- The README has zero fenced ```scala blocks (verifiable via `grep -c '^```scala' <readme>` returning 0).

In either case, document the exception in the verify report.

## Gate 1: companion script

Run:

```
bash .claude/skills/readme/readme-check.sh <readme-path> --chunks --callouts --intro-order
```

The script's `--with-doctest` flag is a CHEAP pre-check (e.g. counts blocks, scans for obvious syntax errors). It is NOT a substitute for Gate 0's full `sbt doctest` run. Run both; Gate 0 is the load-bearing one.

```
bash .claude/skills/readme/readme-check.sh <readme-path> --with-doctest
```

Report any STRONG findings. STRONG findings are blocking. WEAK findings are advisory; report but do not block. INFO findings are noise; do not report unless asked.

Specific STRONG patterns the script flags (your verify report must echo each that fires):

- Em-dashes anywhere in the file.
- Audience-disclaimer phrases ("built for", "if you're new", "this README assumes").
- Marketing copy ("blazing", "elegant", "powerful", "zero-cost").
- Banned opening shapes ("Why not X?", "This README will...").
- Section duplication (the same heading text appearing twice).
- Code blocks with broken or absent triple-backtick fences.

## Gate 2: chunk-by-chunk forward-reference walk (Stage 2a)

Split the README into chunks at every `##` heading. Walk chunks in order. For each chunk, ask:

1. **Does this chunk reference a concept that has not yet been introduced?** If yes, that is a forward reference. Forward references are not always bugs (a passing mention of a later section is fine), but a chunk that ASSUMES familiarity with a not-yet-introduced concept is a bug. Report each instance with: `[FWD-REF] chunk: <heading>, references: <concept>, introduced-at: <later heading or "never">`.
2. **Does this chunk's code example use a name that does not appear in the API inventory?** If yes, the example either uses a fabricated name or the inventory is incomplete. Cross-check against the source-analysis output's inventory. Report: `[FABRICATED] chunk: <heading>, name: <name>, inventory: <miss | present>`.
3. **Is this chunk's example self-contained (compiles standalone or with prior-chunk imports)?** If no, report: `[NON-COMPILE] chunk: <heading>, missing: <imports | type | method>`.

Walk EVERY chunk. Do not skip "obvious" chunks. The whole point of the walk is sequential: a forward reference is only detectable by walking in order.

Use `Read` with explicit offsets to fetch one chunk at a time. Do not load the whole README into your scratchpad and skim; the discipline is sequential.

## Gate 3: skim-reader persona walkthrough (Stage 2b)

Simulate a skim reader: someone evaluating whether to adopt this module. They read the title, the first paragraph, then jump to headings. They do NOT read every word.

For each of these checkpoints, write a one-sentence answer based ONLY on what a skim reader would see:

1. **Title check**: what does the title tell me? (Should be: "kyo-X. Module name only.")
2. **First-paragraph check**: after reading paragraph 1, what is the mental model? (Should be: a 1-2-sentence summary that matches the spine candidate's `[use-case-first|type-first]` tag.)
3. **First-example check**: scanning down to the first code block, what does it show me how to do? (Should be: the typical primary use case.)
4. **Heading scan**: reading only the `##` headings top to bottom, can I locate where to read about <each inventory entry>? (Should be: yes, headings name the surfaces.)
5. **Callout visibility check**: are non-intuitive surfaces marked inline? Scan for `> **Note:**`, `> **Caution:**`, `> **Unlike**` paragraphs. (Should be: callouts are present at the introduction points the source-analysis output identified.)

If a checkpoint answer is "I can't tell" or "it would take re-reading," report `[SKIM-FAIL] checkpoint: <N>, observation: <what's missing>`.

### Line-grounded spine test (sub-step of Gate 3)

The most common failure mode an agent invents during verify: claiming the README contains a spine paragraph that matches the source-only spine candidate, when in fact the README opens with a flat capability list or marketing copy.

Defense: the verify agent must quote, by line number, the actual sentence(s) it identifies as the spine. Cite as: `Spine at lines A-B: "<verbatim text>"`. Then state whether that quoted text matches the spine candidate.

If the verify agent cannot quote a spine, the README does not have one. Report `[NO-SPINE]` with the first 5 lines of the README as evidence.

This single line-grounded test catches "fluently invented spine that isn't there" in the verify summary.

## Gate 4: source-coverage check (load-bearing)

The other three gates check the README against itself. Gate 4 checks the README against source. This is the gate that catches the dominant failure mode: a complete-looking, internally-coherent README that is missing entire chapters of public surface.

The procedure:

1. **Enumerate public surface in source.** Locate the module's main source directory (`<module>/shared/src/main/scala/kyo/` and any platform-specific public dirs). Run:

   ```
   grep -nE '^( {2,4})?(inline |transparent inline )?(def |given |val |object |extension |trait |class |sealed |type )' <each-public-file>
   ```

   Filter out: files under `internal/` packages, names marked `private` or `private[kyo]`, names starting with `_`, generated-code patterns (`SchemaXXXTuple`, `SummonInline`). What remains is the public-surface name set.

2. **Cross-reference against the README.** For each public name in the set, grep the README. A name "appears" if:
   - The literal name is in a code block, prose, or heading.
   - OR the name's parent type is documented and the name is a standard accessor of that type (e.g. `HttpResponse.ok` counts as covered when "HttpResponse" has a dedicated section and `.ok` is one of several constructors).
   - OR the name is a primitive given (`given Schema[Int]`) covered by a built-in-types table or list.

3. **Cross-reference against the source-analysis output's inventory.** For each public name NOT in the README:
   - **In inventory but not in README**: drafter omission. Report `[DRAFT-OMISSION] <name> at <source:line> (in inventory entry: <entry>)`.
   - **Not in inventory and not in README**: analyzer slot-drop. Report `[INVENTORY-GAP] <name> at <source:line>`.

4. **Cross-API callout coverage.** Read each two-anchored callout from the source-analysis output (entries with `<file-A>:<line-A> + <file-B>:<line-B>` format). For each, grep the README for an inline callout that covers the seam. If absent, report `[CALLOUT-MISSING] <seam summary>`.

Findings:

- More than 5 `[INVENTORY-GAP]` entries: re-dispatch `readme-analyze-source` with the orphan names called out; do NOT just write the names into the README. The analyzer should re-run its mechanical pass.
- 1-5 `[DRAFT-OMISSION]` entries: dispatch `readme-edit` with `[ADD-CALLOUT]` or new `##` sections per entry.
- More than 5 `[DRAFT-OMISSION]` entries: severe drafter drift. Switch to `readme-draft` for a full rewrite using the same source-analysis.
- Any `[CALLOUT-MISSING]` entry: dispatch `readme-edit` with `[ADD-CALLOUT]` entries.

## Gate 5: catalog-detection signal (mechanical)

This gate is a cheap mechanical check that surfaces whether the README is suspect of catalog-shape failures. It does NOT make the gestalt judgment; that's `readme-critique`'s job. Gate 5's purpose: if the signal trips, the supervisor knows to route to `readme-critique` before declaring the README done.

Three signals, all mechanical:

1. **API-named sections.** Count `##` headings that match a public-name from the inventory (singular noun like "Modify", "Compare", "HttpRoute", optionally with `:` suffix). Compute ratio: API-named / total `##` headings. SUSPECT if ≥40%.

2. **Distinct case-class introductions.** Count `^case class ` lines across all code blocks in the README, excluding sealed-trait variants. SUSPECT if ≥7 for a typical kyo module. The expected value is 1-3 (the running domain plus one or two helpers).

3. **`##` section count vs cluster count.** Count `##` headings (excluding leading admin sections like Installation, Cross-platform, Exceptions) vs the count of `## Section clusters` in the source-analysis output. SUSPECT if `##-count > 1.5 × cluster-count`.

If any signal trips SUSPECT, emit a `[CATALOG-SUSPECT]` line in the Gate 5 report with the specific signal(s) and counts. The supervisor will route to `readme-critique` for the gestalt judgment.

## Output

A single report with six sections (Gate 0 first):

```
## Gate 0: sbt doctest (MANDATORY)
sbt command: <full command>
sbt exit code: <0 | non-zero>
doctest summary: total=N compiled=N cacheHits=N failures=N
<PASS | BLOCK with the relevant compiler errors quoted>

## Gate 1: script check
<STRONG findings with line numbers, or "PASS">

## Gate 2: forward-reference walk
<FWD-REF / FABRICATED / NON-COMPILE entries, or "PASS">

## Gate 3: skim-reader walkthrough
<SKIM-FAIL / NO-SPINE entries, or "PASS">

## Gate 4: source-coverage check
<INVENTORY-GAP / DRAFT-OMISSION / CALLOUT-MISSING entries, or "PASS">

## Gate 5: catalog-detection signal
<CATALOG-SUSPECT entries with counts, or "PASS">

## Summary
<PASS | BLOCK>
<one paragraph: if BLOCK, what readme-edit (or readme-draft for severe drift, or readme-analyze-source for repeated inventory gaps, or readme-critique for catalog-suspect) must address>
```

Gate 0 fail = automatic BLOCK; Gates 1-5 still run for completeness but the overall verdict is BLOCK. The supervisor reads Gate 0 first.

## Execution

This sub-skill runs as a single Opus agent dispatched by the supervisor. You do NOT dispatch further sub-agents. All verification happens in your own context:

1. **Run `sbt '<module>/doctest'` (Gate 0)**. This is the hard gate. If it fails, the entire verify verdict is BLOCK regardless of what Gates 1-5 say. Still run Gates 1-5 so the supervisor has the full picture for routing, but the verdict line in `## Summary` is BLOCK.
2. Run the script (Gate 1).
3. Walk chunks sequentially using `Read` with explicit offsets (Gate 2).
4. Perform the skim-reader checkpoints with line-grounded spine quotes (Gate 3).
5. Run the source-coverage grep against the README (Gate 4). Read the source-analysis output (passed in `$ARGUMENTS`) to power the `[INVENTORY-GAP]` vs `[DRAFT-OMISSION]` distinction.
6. Run the catalog-detection signal (Gate 5): count API-named sections, distinct case-class declarations, and `##` headings vs clusters. Emit `[CATALOG-SUSPECT]` if any signal trips.
7. Emit the structured report.

Do not run Gates 2 and 3 in parallel even though they appear independent. Gate 2 builds the section index that Gate 3's heading scan checkpoint reuses; running them sequentially in one context avoids re-reading the README twice. Gate 4 can run after Gate 1 (it doesn't depend on Gates 2 or 3) but in practice run them in order so a single STRONG script finding short-circuits the rest.

Verification is a high-judgment call: never report PASS if any gate has unresolved findings. The supervisor will use the report to decide whether to re-dispatch `readme-edit` (for small fixes), `readme-draft` (for severe spine drift or many drafter omissions), or `readme-analyze-source` (for repeated inventory gaps the analyzer must re-do).

**Gate 0 failure routing**: Doctest failures usually map to either fabricated APIs (handled by `readme-edit` per-block) or stale source assumptions (the source has changed since the analyzer ran, requires `readme-analyze-source` re-dispatch + redraft). The supervisor reads the compiler errors quoted in Gate 0's BLOCK report and decides which to re-dispatch.
