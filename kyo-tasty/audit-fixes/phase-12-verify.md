# Phase 12 verify report

Status: FAIL (class-A fp-discipline + class-A reward-hacking)

Run-id: phase-12-verify-1
HEAD: `5c6f2b065` (Phase 11; impl uncommitted on dirty tree)
Baseline: `kyo-tasty/audit-fixes/phase-12-baseline.txt`

## Class-A gates (mechanical, commit-blocking)

- log-gated pass: **green**
  - JVM testOnly: `kyo-tasty/audit-fixes/runs/phase-12-flow-verify-testOnly-jvm-1.log`
    - `Tests: succeeded 9, failed 0, canceled 0, ignored 0, pending 0`
    - Test 8 (EXTref) and Test 9 (EXTMODCLASSref) both pass; Tests 1-7 still pass (no regression).
  - JS testOnly: `kyo-tasty/audit-fixes/runs/phase-12-flow-verify-testOnly-js-1.log`
    - `Tests: succeeded 2, failed 0, canceled 0, ignored 7, pending 0`
    - Test 8 + Test 9 run on JS (untagged); Tests 1-7 `jvmOnly`-tagged ignored as expected.
  - Native testOnly: `kyo-tasty/audit-fixes/runs/phase-12-flow-verify-testOnly-native-1.log`
    - `Tests: succeeded 2, failed 0, canceled 0, ignored 7, pending 0`
    - Same shape as JS.

- reward-hacking grep: 11 hits total, 0 overridden. **1 hit is NEW from Phase 12 diff: FAIL.**
  - NEW: `kyo-tasty/shared/src/test/scala/kyo/Scala2PickleTest.scala:252` — `// We use a simpler layout: chain via ownerRef pointing to a TERMname directly...` matches `scope-substitution` (`\bsimpler( equivalent)?\b`).
  - PRE-EXISTING (verified absent from `git diff HEAD | grep '^+'`):
    - `Scala2PickleReader.scala:347, 385, 386, 404` — "placeholder" in pre-existing scaladoc / comments around Type.Function and type-alias decoder.
    - `Scala2PickleTest.scala:176, 222, 225, 226, 233, 237` — "placeholder" inherited from Phase 11 / earlier Scala2Pickle work.
  - **Remediation**: rewrite the test-comment block at `Scala2PickleTest.scala:252` to drop "simpler"; describe the actual chosen layout without comparison framing. Acceptable replacement: `// Layout used: owner chain via ownerRef pointing to a TERMname directly (common for single-package owners).`

- fp-discipline grep: 35 hits total, 0 overridden. **5 hits are NEW from Phase 12 source diff: FAIL.**
  - NEW (HEAD's `Scala2PickleReader.scala` has ZERO `Some(` / `None` / `Option[` occurrences; verified by `git show HEAD:.../Scala2PickleReader.scala | grep -nE "Some\(|\\bNone\\b|Option\["` returns empty):
    - `Scala2PickleReader.scala:453` `some-constructor` + `none-token`: `val ownerRefOpt = if c.remaining > 0 then Some(c.readNat()) else None`
    - `Scala2PickleReader.scala:480` `some-constructor` + `none-token`: same shape in `decodeExtModClassRef`
    - `Scala2PickleReader.scala:505` `some-constructor`: `case Some(name) => name`
    - `Scala2PickleReader.scala:506` `none-token`: `case None =>`
    - `Scala2PickleReader.scala:513` `some-constructor` + `none-token`: same shape in `resolveExtFqn`
  - Per Rule 4 of fp-discipline catalog and `feedback_json_use_option` ("schema-derived case classes use Maybe", Kyo convention generally), code under `kyo/` (incl. `kyo/internal/`) MUST use `Maybe[T] = Absent` instead of `Option[T] = None`. Scope-glob `*/src/main/scala/kyo/*` matches this file.
  - **Remediation**: replace `Some(...)`/`None`/`case Some/case None` with `Maybe(...)`/`Maybe.Absent` and `Maybe.Present(_)`/`Maybe.Absent`. The decisions doc for Phase 12 makes no claim about this choice; this is not a flow-allow candidate.
  - PRE-EXISTING (NOT introduced by Phase 12; verified absent from diff):
    - 17 `bare-var` hits across PickleCursor / decoder loops.
    - 6 `unsafe-site` hits (`import AllowUnsafe.embrace.danger`) at lines 290, 375, 407, 459, 487, 539 (the file has a long-standing convention of in-body imports at SingleAssign.set boundaries).
    - 4 `null-literal` hits at lines 533, 558, 559, 564.
  - Phase 12 ALSO introduces 2 NEW `unsafe-site` hits at lines 459 and 487 (`import AllowUnsafe.embrace.danger` in `decodeExtRef` and `decodeExtModClassRef`). Per the file's established convention this matches the existing 6 unsafe-site sites; **but it remains an un-overridden class-A hit** under the catalog. Phase 11 verify (held-out class-B finding #2 "AllowUnsafe-in-body shortcut on the writer side") flagged the same convention as class-B; Phase 12 follows the same convention. Recommendation: add `// flow-allow: SingleAssign.set requires AllowUnsafe; embraced at fresh-symbol population boundary, matching the in-file convention used by decodeClassSym, decodeValSym, decodeTypeSym.` on the preceding line of each `import AllowUnsafe.embrace.danger` in the two new decoders.

- llm-tells grep: 9 hits, 0 overridden — **all PRE-EXISTING** in `kyo-tasty/audit-fixes/phase-11-audit.md` (em-dash in pre-existing audit artifact). Verified `git diff HEAD | grep -nP "[\x{2014}\x{2013}]"` returns empty (NO em-dash / en-dash on added lines). Verified `git diff HEAD | grep '^+' | grep -iE "comprehensive|robust|seamless|leverage|delve"` returns empty.
  - Gate verdict: PASS (no NEW hits in Phase 12 diff).

- dev-tag grep: 0 hits, 0 overridden. PASS. (Verified the Phase 10/11 lesson: NO `// Phase 12` annotations exist anywhere in the new code or tests. The decisions doc references "Phase 12" but that is artifact-side prose, not in source.)

- plan-diff (with baseline `phase-12-baseline.txt`):
  - Baseline snapshot is `?? kyo-tasty/audit-fixes/phase-12-baseline.txt` (1 line: the baseline file itself was already untracked).
  - Dirty source files (manual bucket classification):
    - `Scala2PickleReader.scala`: AUTHORIZED (in plan `files_modified` for Phase 12).
    - `Scala2PickleTest.scala`: AUTHORIZED (in plan `tests.files` for Phase 12).
  - Untracked: `phase-11-audit.md` (PRE-EXISTING artifact from Phase 11), `phase-12-baseline.txt` (PRE-EXISTING baseline), `phase-12-decisions.md` (Phase 12 dispatch artifact).
  - Bucket counts: AUTHORIZED=2, PRE-EXISTING/ARTIFACT=3, DRIFT-FROM-IMPL=0, MISSING=0.
  - Verdict: PASS.

- test-count: expected=2, actual=2 (Test 8 + Test 9 in `Scala2PickleTest.scala`). PASS.

- stowaway-commit: NONE. `git log 5c6f2b065..HEAD` returns empty (HEAD unchanged from Phase 11 commit). PASS.

- cross-platform compile + test (`platforms: [jvm, js, native]`):
  - JVM: `Test/compile` + `testOnly` green (9/9 pass). Log: `phase-12-flow-verify-testOnly-jvm-1.log`.
  - JS: `Test/compile` + `testOnly` green (2/2 pass, 7 jvmOnly-tagged ignored). Log: `phase-12-flow-verify-testOnly-js-1.log`.
  - Native: `Test/compile` + `testOnly` green (2/2 pass, 7 jvmOnly-tagged ignored). Log: `phase-12-flow-verify-testOnly-native-1.log`.
  - Verdict: PASS (all 3 platforms green).

- organization gate (Rule 8a/8b/8c): inherits the 43 pre-existing kyo-tasty violations recorded by prior phases. Phase 12 introduces ZERO new top-level types and ZERO new test files (only adds two tests to an existing test file). Not Phase-12-blocking.

## Held-out acceptance check (class-B, opus)

Held-out derived INDEPENDENTLY from `design/02-design.md` M9 semantics (NOT from the plan's two test leaves): "EXTref and EXTMODCLASSref entries must produce Symbol values whose `name.asString` reconstructs the dotted FQN by walking the ownerRef chain through the pickle's name + ref table, with the `$` suffix appended for module classes; the resulting Symbol must be queryable as `Unresolved` AND its `declaredType` must be a `Type.Named` pointing at the same symbol (so downstream Type-resolution can recognize it as an external reference, not just a name string)."

- Held-out 1: **FQN ownerRef chain.** Test 8 builds entries `[TERMname("com.example"), EXTref(nameRef=0), TERMname("Foo"), EXTref(nameRef=2, ownerRef=1)]` and asserts the resulting symbol's `name.asString == "com.example.Foo"`. This forces (a) `decodeExtRef` to read the ownerRef nat, (b) `resolveExtFqn` to walk into entry 1, (c) recursive read of entry 1 to pull nameTable[0] = "com.example", (d) `q + "." + symName` concatenation. The assertion is on the reconstructed dotted FQN, not just non-emptiness. PASS.
- Held-out 2: **Module-class `$` suffix.** Test 9 asserts `name.asString == "com.example.Foo$"`. `decodeExtModClassRef` appends `$` BEFORE FQN construction (line 471: `if rawName.endsWith("$") then rawName else rawName + "$"`), so the suffix ends up on the leaf, not the owner. The assertion uses literal `Foo$` (escaped as `Foo$$` in the s-interpolated error message), and the rawName comes from a TERMname containing exactly `"Foo"` (NOT `"Foo$"`), so the `else` branch fires. PASS.
- Held-out 3: **`declaredType = Type.Named(self)`.** Plan does NOT exercise this directly; held-out derived from design "produces `Type.Named`". Inspection of `Scala2PickleReader.scala:464-465` and `491-492`: `sym._declaredType.set(Tasty.Type.Named(sym))`. The self-reference matches the `decodeClassSym` / `decodeTypeSym` convention for Unresolved placeholders. No test asserts this, but the structure is in place and matches the plan's pseudocode. Class-B observation, NOT a blocker.

All three held-out checks PASS. No design-vs-impl gap detected on the M9 semantics.

## Class-B findings (opus judgment)

1. **Maybe-vs-Option violation is class-A in Kyo, NOT class-B.** Promoted above; recorded here for the supervisor's surface trail. Per `feedback_json_use_option` and the file scope-glob, `Option[T]` is wrong for `kyo/*`. Impl agent (and the decisions doc) made no choice statement on this; this is not a deliberate convention deviation but an inattentive use of stdlib Option. Fix: rewrite the two `ownerRefOpt` flow-paths to `Maybe`. The pattern is small:
   - `val ownerRefOpt: Maybe[Int] = if c.remaining > 0 then Maybe(c.readNat()) else Maybe.Absent`
   - `ownerRefOpt.map(resolveExtFqn(_, nameTable, entries)).filter(_.nonEmpty)` becomes `ownerRefOpt.map(resolveExtFqn(_, nameTable, entries)).filter(_.nonEmpty)` (Maybe has the same `.map` / `.filter` shape).
   - `ownerFqn.fold(symName)(q => q + "." + symName)` -> `ownerFqn.foldLeft(symName)(...)` or use the Maybe-equivalent fold method.
   - The `case Some(name) / case None` in `resolveExtFqn` becomes `case Present(name) / case Absent`.

2. **AllowUnsafe-in-body convention follows existing file pattern.** The two new `import AllowUnsafe.embrace.danger` statements (lines 459, 487) mirror the existing 6 sites in this file. Phase 11 verify flagged the same shape on the writer side as a class-B observation, not a blocker. For Phase 12, recommended remediation is to annotate each with `// flow-allow: SingleAssign.set requires AllowUnsafe; matches in-file convention.` per `feedback_no_unsafe` rule on per-site justification.

3. **`resolveExtFqn` recursion has no depth bound.** A malformed pickle whose EXTref entries form a cycle (entry A's ownerRef points to entry B whose ownerRef points back to A) will recurse until StackOverflowError. The current code passes only well-formed inputs in tests, but a corrupt-pickle robustness gap remains. Phase 09 added pool-entry validation but does not catch ref-cycles. Class-B observation, NOT a Phase-12 blocker; consider filing as a follow-up against M-robustness or addressing in the catchall corrupt-pickle phase.

4. **`null,` literal sentinels at lines 533, 564 in `makePickleSym` calls.** These are PRE-EXISTING (not introduced by Phase 12), but the new `decodeExtRef` / `decodeExtModClassRef` decoders DO call `makePickleSym` with the same shape, propagating the `null` sentinel convention. STEERING.md documents this as "accepted hot-path null sentinel" (see comments at lines 558-559). Phase 12 stays inside the existing convention; no new violation. PASS via existing project carve-out.

5. **`scope-substitution: simpler` in test prose.** Promoted above; recorded here as commit-blocker context. The comment block at `Scala2PickleTest.scala:252` describes a CHOSEN layout, not a deferred-shortcut one. The word "simpler" is descriptive of the test's pickle structure (single-name owner vs multi-level chain), not of impl scope-cutting. This is a false-positive on intent BUT the class-A regex does not discriminate; per FLOW the impl agent either rewrites the comment or adds `// flow-allow: descriptive of chosen test pickle layout, not scope-cut framing.` on the preceding line. Recommend rewriting (lower friction).

## Overrides

None present in the dirty tree. The Phase 12 impl agent did NOT add any `// flow-allow:` annotations. Per the override mechanism, the remediation either rewrites source to clear the gates OR adds the annotations with rationales.

Recommended override stubs (for impl agent / supervisor to apply if convention is judged correct):

```
# Scala2PickleReader.scala:459 (preceding line):
// flow-allow: SingleAssign.set requires AllowUnsafe; embraced at fresh-symbol population boundary, matches decodeClassSym / decodeValSym / decodeTypeSym convention.

# Scala2PickleReader.scala:487 (preceding line):
// flow-allow: same as decodeExtRef; SingleAssign.set boundary.
```

Maybe-vs-Option and `simpler` are NOT flow-allow candidates per `feedback_json_use_option` and per FLOW §5 (scope-substitution language has zero tolerance); those MUST be remediated in source.

## Exit code: 1

Class-A gates with un-overridden NEW hits:
- fp-discipline (some-constructor, none-token, option-token equivalents on 5 source lines)
- reward-hacking (scope-substitution on 1 test-comment line)
- (fp-discipline unsafe-site x2 NEW: override-eligible per Phase 11 precedent; current state un-overridden)

Returns the phase to impl for remediation:
1. Rewrite `Scala2PickleReader.scala` lines 453, 480, 505-506, 513 to use `Maybe` instead of `Option`/`Some`/`None`.
2. Rewrite `Scala2PickleTest.scala:252` to drop the word `simpler` (or add `// flow-allow:` if the intent is descriptive, but rewrite is cleaner).
3. Add `// flow-allow:` annotations on the two new `import AllowUnsafe.embrace.danger` lines (or refactor to lift the import to the file head, matching Phase 11 / writer-side conventions if those exist).

After remediation, re-run flow-verify. Tests must stay green on all three platforms.

NOT ready for commit. Remediation required.
