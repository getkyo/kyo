---
name: readme-critique
description: High-judgment whole-document review for shape, pedagogy, and design-rationale. Catches the catalog-vs-teaching, composition-vs-enumeration, running-domain-vs-ad-hoc failure modes that readme-verify's gates pass on. Runs between draft and verify.
argument-hint: <readme-path> <source-analysis-path> [exemplar-path]
---

# readme-critique

The verify gates check coverage (Gate 4), self-consistency (Gates 1-2), and skim-experience (Gate 3). They pass on READMEs that are internally consistent and complete-by-name but that read as catalogs rather than teaching. This skill is the gestalt judge that the verify gates cannot be.

`readme-critique` runs AFTER `readme-draft` (or after `readme-edit`) and BEFORE `readme-verify`. Its job: read the candidate top to bottom as a real reader would, and report along eight axes whether the README teaches or merely lists.

## Inputs

- `readme_path`: the candidate README.
- `source_analysis_path`: the analyzer's output (includes the section-cluster output and the running-domain seeds — this is the contract the draft was supposed to follow).
- `exemplar_path` (optional): a reference README to compare against (typically `git show origin/main:<module>/README.md` for kyo modules).

## The eight axes

For each axis, report `PASS` | `WEAK` | `FAIL` with line evidence.

### Axis 1: Opening hook size and shape

Count the lines of the first fenced ```scala``` block in the README (the opening hook, between the opening and closing triple-backticks). It must be ≤15 lines and demonstrate ONE compelling thing on ONE running value. Or: no opening code block at all (spine prose alone hooks the reader). Comprehensive multi-capability examples belong in a `## Putting it together` (or similar) section near the END of the README, after the cluster sections.

- **PASS**: ≤15-line opening block doing one verb on one value, OR no opening code block at all (spine paragraphs only).
- **WEAK**: 16-25 line opening block, OR a smaller block but with two unrelated capabilities crammed in.
- **FAIL**: 26+ line opening block, OR multi-capability "tour of the module" with `// section heading` comments dividing sub-blocks at the top.

Rationale: walls of code at the top get skimmed past or close the README entirely. A short hook gets the reader committed; the comprehensive composition example survives, but moves to a final section where motivated readers will find it.

### Axis 2: Running domain

Count distinct case-class declarations in the README, excluding sealed-trait variants. For a typical kyo module with a non-trivial domain (Schema, HTTP, flow), this should be at most 3 (one main value + one or two helpers).

- **PASS**: ≤ 3 distinct case classes; the main one appears in 5+ sections.
- **WEAK**: 4-6 distinct case classes; some clustering of reuse.
- **FAIL**: 7+ distinct case classes; every section introduces its own. Per-section ad-hoc domain.

For modules whose primary domain is genuinely diverse (e.g. a JSON parser README touching many shapes), this axis can be marked `n/a`. Justify the n/a.

### Axis 3: Section names — use vs API

Walk every `##` heading. For each, classify:

- **Use name** (gerund or noun phrase about what the reader is doing or the conceptual group): "Comparison and Mutation", "Construction", "Custom Formats", "Routes and handlers", "Cross-platform behavior".
- **API name** (singular noun matching a public name in the inventory): "Modify", "HttpRoute", "Json", "Compare", "Schema", "Builder".
- **API name with suffix**: "Modify: batched mutation", "HttpRoute: typed contracts". Still API-named.

Compute the ratio. PASS if ≥70% are use-named; WEAK if 40-69%; FAIL if <40%.

Exception: sections that are genuinely a single API with no cluster siblings (e.g. "Validation" when validation is one method) get a pass either way.

### Axis 4: Section openers — WHY vs WHAT

Sample 5 random `##` and `###` sections (or all of them if there are <10). For each, classify the first sentence:

- **WHY/WHEN/COMPARED-TO**: starts with the reader's situation, the relationship to prior content, or the design rationale. "When you want <X>...", "Both <A> and <B>...", "Unlike <prior thing>..."
- **WHAT**: starts with a backticked API name as the subject. "`X` provides <Y>", "`X` is a <thing>", "`X` lets you <Y>."
- **Mixed**: starts with prose explaining context but the second sentence reverts to "WHAT".

Compute ratio. PASS if ≥60% WHY; WEAK if 30-59%; FAIL if <30%.

### Axis 5: Cluster grouping

Read the `## Section clusters` block from the source-analysis output. For each cluster, check the README:

- Does the cluster's APIs appear together in one `##` section, with each API as a `###` sub-section?
- Or are the APIs split into separate `##` sections at the top level?

PASS if all clusters are grouped; WEAK if some are grouped some are split; FAIL if no clustering applied (one section per API).

### Axis 6: Cross-API teaching paragraphs

Read the source-analysis Step 6 cross-API callouts (entries with two file:line anchors) plus the cluster siblings. For each pair, check whether the README has an explicit "when to use which" paragraph somewhere.

Specific seam pairs that MUST be addressed when both APIs are documented:

- `Focus.set/update` vs `Modify` (one-shot vs batched)
- `Compare` vs `Changeset` (in-memory vs serializable)
- `Json.encode(value)` vs `transformedSchema.encode[Json](value)` (ambient summoning vs transformed instance — implicit-summoning gotcha)
- `withConfig(value)` vs `withConfig(f)` (replace vs stack)
- `body-only` vs `*Response` methods (auto-fail vs raw response)

Count cross-API paragraphs found ÷ cross-API paragraphs needed. PASS if ≥80%; WEAK if 40-79%; FAIL if <40%.

### Axis 7: Design rationale woven in

Sample 5 random `##` sections (or all if there are <10). For each, count paragraphs that include reasoning words: `because`, `unlike`, `compared to`, `the alternative`, `this means`, `so that`, `to avoid`, `the reason`. Sections that contain at least one such paragraph (excluding `> **Caution:**` callouts which don't count) pass.

Threshold: ≥50% of sampled sections contain a rationale paragraph. PASS, WEAK, FAIL.

### Axis 8: Reader's discovery path

Read ONLY the `##` headings of the README, in order. Do they tell a coherent story that a skim reader could form a mental model from?

- **PASS**: Headings form a curriculum arc — opening → first call → core capabilities (grouped) → advanced surfaces → operational concerns. A reader scanning only headings learns the design.
- **WEAK**: Headings are mostly in a reasonable order but some are mis-ordered or repetitive. A skim reader gets a partial model.
- **FAIL**: Headings are an API list. A skim reader sees an enumeration with no narrative arc.

This is a judgment axis. Explain the verdict.

### Axis 9: Chapter coverage vs exemplar

**Only runs when an exemplar is provided.** Mark `N/A` when no exemplar.

This axis catches the failure mode the v3 reviewer named: the critique passes on shape and pedagogy but doesn't notice when an entire chapter from the exemplar has gone missing in the candidate. The kyo-http migration-from-sttp/tapir chapter is the canonical case — exemplar has it, candidate doesn't, every other axis is fine.

Procedure:

1. List every `##` heading in the exemplar.
2. For each exemplar chapter, read its content briefly to identify the TOPIC (not the heading text — the underlying subject matter).
3. For each topic, search the candidate for coverage. Coverage exists when:
   - The candidate has a section whose content addresses the same topic (heading text may differ).
   - OR the topic is woven into other sections in the candidate's curriculum (e.g. exemplar has a separate `## Cookies` chapter, candidate covers cookies inline within `## Routes and handlers` — that's coverage).
   - OR the topic is genuinely OBSOLETE in the new README (the exemplar's chapter was about a deprecated surface that no longer exists in source; this requires source verification before claiming N/A).
4. Sections in exemplar with NO corresponding content in candidate: flag as `[CHAPTER-MISSING]` with the exemplar heading and one-paragraph summary of what's missing.

Verdict:

- **PASS**: every exemplar chapter has corresponding coverage in the candidate. The candidate may have ADDITIONAL chapters not in the exemplar (that's improvement, not a flag).
- **WEAK**: 1-2 `[CHAPTER-MISSING]` entries, AND each missing chapter is plausibly out-of-scope for the candidate's chosen audience (the supervisor decides).
- **FAIL**: 3+ chapters missing, OR 1-2 chapters missing where one is load-bearing (migration content, integration with another module, large worked example).

Output format for this axis:

```
Axis 9: PASS|WEAK|FAIL|N/A
- Exemplar chapter count: <N>
- Candidate chapter count: <M>
- Topics in exemplar with no coverage in candidate:
  - "<exemplar heading>" (lines A-B of exemplar): <one-sentence summary of what's missing>
  - ...
- Topics in candidate not in exemplar (informational, not a flag): <list>
```

Why this axis is separate from Axis 3 (heading names): Axis 3 asks "are the candidate's headings use-named?" Axis 9 asks "does the candidate's heading set COVER the exemplar's topic set?" A candidate can have perfect use-named headings (PASS on Axis 3) while still being missing a whole topic (FAIL on Axis 9). The kyo-http case is exactly this shape.

## Verdict

After all nine axes (Axis 9 counts as N/A when no exemplar):

- **PASS**: ≤1 axis at WEAK, 0 at FAIL. (N/A axes don't count toward either total.)
- **REWORK**: 2-4 axes WEAK or FAIL. Produce explicit directives for the drafter to apply.
- **REJECT**: 5+ axes WEAK or FAIL. The draft has fundamental shape issues; re-draft from analysis with the structural-discipline rules applied from the start.

Axis 9 FAIL is high-impact: a missing load-bearing chapter is almost always worth a REWORK on its own, even if the other axes pass cleanly.

## Output

```
## Axis 1: Composition opening
<PASS|WEAK|FAIL> -- <evidence with line numbers>

## Axis 2: Running domain
<verdict> -- <count of distinct case classes, list them with line numbers>

## Axis 3: Section names (use vs API)
<verdict> -- <ratio, list of API-named sections by line>

## Axis 4: Section openers (WHY vs WHAT)
<verdict> -- <ratio, list of WHAT-style openers by line>

## Axis 5: Cluster grouping
<verdict> -- <which clusters were grouped vs split>

## Axis 6: Cross-API teaching paragraphs
<verdict> -- <which seam pairs are covered vs missing>

## Axis 7: Design rationale
<verdict> -- <ratio of sections with rationale paragraphs>

## Axis 8: Reader's discovery path
<verdict> -- <one-paragraph judgment of the H2 narrative arc>

## Axis 9: Chapter coverage vs exemplar
<verdict|N/A> -- <list of [CHAPTER-MISSING] entries with exemplar headings and one-line summaries, or "all exemplar chapters covered">

## Verdict
<PASS | REWORK | REJECT>

## Directives for re-draft (if REWORK or REJECT)
<numbered list of structural changes the drafter must apply>
1. <specific change with target sections>
2. ...
```

## Execution

This sub-skill runs as a single Opus agent dispatched by the supervisor. You do NOT dispatch further sub-agents. All judgment happens in your own context:

1. Read the candidate README in full (use `Read` without offset if the file is under ~1500 lines; otherwise read in chunks).
2. Read the source-analysis output to get the cluster contract and the expected cross-API seams.
3. If an exemplar is provided, read it too. The exemplar informs but does not dictate the verdict (the candidate may legitimately exceed the exemplar's shape; the comparison is for axis calibration, not for matching).
4. Walk the eight axes IN ORDER. Each one produces a verdict + evidence.
5. Compute the overall verdict.
6. Emit the structured report.

You may use `Grep` for mechanical counts (case-class declarations, backticked-name section openers, reasoning-word frequency). The mechanical counts feed the judgment; the judgment is yours.

Do NOT modify the README. Do NOT propose code-block edits. Critique is structural; the drafter (re-dispatched by the supervisor with your directives) does the rewrite.

## When the exemplar is present

The exemplar serves four purposes:
- Calibration for "what good looks like" on Axes 1-2 (composition opening, running domain).
- A reference for Axis 5 (cluster grouping) — if the exemplar groups Compare/Modify/Changeset under "Comparison and Mutation" but the candidate split them, that's evidence of catalog drift.
- A reference for Axis 8 (discovery path) — if the exemplar's H2 narrative reads as a curriculum and the candidate's doesn't, that's strong signal.
- The authoritative chapter set for Axis 9 (chapter coverage). Every exemplar chapter must have corresponding coverage in the candidate, unless the supervisor accepts a CHAPTER-MISSING flag as out-of-scope.

The exemplar does NOT dictate length, individual examples, or specific phrasings. The candidate can be longer or shorter, use different running-domain values, and explain things differently. The exemplar shows that good shape IS POSSIBLE for this module's API surface; the candidate must show good shape, not match the exemplar.

If the candidate exceeds the exemplar (better composition opening, sharper cluster grouping, more cross-API teaching, ADDITIONAL chapters not in the exemplar), report PASS on the axes where the candidate is stronger. Axis 9 only flags MISSING chapters from the exemplar; extra chapters in the candidate are improvement.

## What this skill is NOT

- NOT a verifier. Doesn't compare README against source for coverage. That's Gate 4.
- NOT a script. The axes require judgment; mechanical signals support but do not determine the verdict.
- NOT an editor. Produces directives, not action lists.
- NOT a writer. Doesn't propose specific replacement text.
