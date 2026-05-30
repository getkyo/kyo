# Phase 11 verify report

Status: FAIL (class-A dev-tag)

Run-id: phase-11-verify-1
HEAD: `7cd758d64` (Phase 10; impl uncommitted on dirty tree)
Baseline: `kyo-tasty/audit-fixes/phase-11-baseline.txt`

## Class-A gates (mechanical, commit-blocking)

- log-gated pass: **green**
  - JVM testOnly: `kyo-tasty/audit-fixes/runs/phase-11-flow-verify-testOnly-jvm-1.log`
    - `Tests: succeeded 18, failed 0, canceled 0, ignored 0, pending 0`
    - All 6 new `M8: ...` tests pass on JVM.
  - JS testOnly: `kyo-tasty/audit-fixes/runs/phase-11-flow-verify-testOnly-js-1.log`
    - 1 succeeded, 17 ignored (`jvmOnly` tag) — expected.
  - Native testOnly: `kyo-tasty/audit-fixes/runs/phase-11-flow-verify-testOnly-native-1.log`
    - 1 succeeded, 17 ignored (`jvmOnly` tag) — expected.
  - TastySymbolTest JVM: 3/3 succeeded (no regression).

- reward-hacking grep: 4 hits, 0 overridden — **all PRE-EXISTING** (not introduced by Phase 11 diff).
  - `kyo-tasty/audit-fixes/phase-10-audit.md` (Phase 10 artifact, prior phase).
  - `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala:177` (pre-existing `may be a placeholder symbol`).
  - `kyo-tasty/audit-fixes/phase-11-decisions.md:42` matches `\bskipping\b` inside "After skipping target_info and type_path" (prose describing parser flow). Not a deferral-of-work claim; benign.
  - Verified `git diff HEAD | grep '^+' | grep -E "for now|placeholder|skipping for now"` returns empty (no new reward-hacking strings in source diff).
  - Gate verdict: PASS (no NEW hits in Phase 11 diff).

- fp-discipline grep: 91 hits, 0 overridden — **all PRE-EXISTING** in untouched code.
  - Verified `git diff HEAD | grep '^+' | grep -E "null|asInstanceOf|isInstanceOf|Some\(|None|Right\(|Left\(|Try\{"` returns empty.
  - Gate verdict: PASS (no NEW hits in Phase 11 diff).

- llm-tells grep: 12 hits, 0 overridden — **all PRE-EXISTING** in `phase-10-audit.md`.
  - Verified `git diff HEAD | grep '^+' | grep -P "[\x{2014}\x{2013}]"` returns empty (no em-dash / en-dash on added lines).
  - Verified `git diff HEAD | grep '^+' | grep -iE "comprehensive|robust|seamless|leverage|delve"` returns empty.
  - Gate verdict: PASS (no NEW hits in Phase 11 diff).

- dev-tag grep: 3 hits, 0 overridden — **ALL NEW from Phase 11 source diff**. **FAIL.**
  - `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/classfile/ClassfileUnpickler.scala:276` `// Phase 11: MethodParameters attribute (method-level only; empty for fields)`
  - `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/classfile/ClassfileUnpickler.scala:465` `// Phase 11: additional attribute payloads`
  - `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/classfile/ClassfileUnpickler.scala:1735` `// Phase 11 attribute helpers`
  - Per FLOW-DESIGN §8: dev-process references in source comments must be tagged `// DEV:`. None of these have the prefix.
  - **Remediation**: prefix each with `// DEV:` OR remove the "Phase 11" wording (replace with description-only comments).

- plan-diff (with baseline `phase-11-baseline.txt`):
  - Dirty source files: `Tasty.scala`, `ClassfileFormat.scala`, `ClassfileUnpickler.scala`, `JavaAnnotationUnpickler.scala`, `ClassfileReaderTest.scala`.
  - Plan `files_modified` lists ONLY `Tasty.scala` + `ClassfileUnpickler.scala`. The other 3 files are NOT in plan but ARE in the task's AUTHORIZED set ("authorized" overrides plan-yaml shape for this phase per the dispatch).
  - Plan `tests.files` lists `ClassfileUnpicklerTest.scala` + `TastySymbolTest.scala`. Impl placed all 6 new tests in `ClassfileReaderTest.scala` instead. Drift from plan; test placement-wise it matches the existing topic-based reader integration test (`feedback_test_placement`).
  - Untracked artifacts (`audit-fixes/phase-10-audit.md`, `phase-11-baseline.txt`, `phase-11-decisions.md`): PRE-EXISTING / authorized artifact files.
  - Bucket counts (manual classification): AUTHORIZED=5 (the 5 source/test files all match the task's AUTHORIZED set), PRE-EXISTING/ARTIFACT=3, DRIFT-FROM-IMPL=0.
  - Verdict: PASS.

- test-count: expected=6, actual=6 (six new `"M8: ..."` test cases in `ClassfileReaderTest.scala`). PASS.

- stowaway-commit: NONE. `git log 7cd758d64..HEAD` is empty; HEAD unchanged.

- cross-platform compile (`platforms: [jvm, js, native]`):
  - JVM: `Test/compile` clean (kyo-tasty/audit-fixes/runs/phase-11-flow-verify-compile-jvm-1.log).
  - JS: `kyo-tastyJS/Test/compile` clean (phase-11-flow-verify-compile-js-1.log + phase-11-flow-verify-compile-js-native-1.log).
  - Native: `kyo-tastyNative/Test/compile` clean (phase-11-flow-verify-compile-js-native-1.log).
  - Tests: JVM 18/18 pass; JS 1+17ignored; Native 1+17ignored.
  - Verdict: PASS (all 3 platforms green).

- organization gate: 43 pre-existing kyo-tasty violations (8c-orphan-test / 8c-missing-test). Phase 11 introduced ZERO new top-level types and ZERO new test files; the violations are inherited. Not Phase-11-blocking.

## Held-out acceptance check (class-B, opus)

Held-out derived from M8 / INV-008: "the 6 attributes must be parsed AND surfaced via `JavaMetadata` / `Symbol` accessors so downstream tooling can query them — not merely consumed-and-discarded."

- Held-out 1: PermittedSubclasses round-trip through the new `Symbol.permittedSubclasses` accessor (NOT just `JavaMetadata.*`). Test 16 reads `java/lang/constant/ClassDesc.class`, queries `result.classSymbol.permittedSubclasses`, asserts `isDefined` and `nonEmpty`. Exercises the `Symbol._permittedSubclasses` slot AND the new accessor. PASS.
- Held-out 2: Synthetic type-annotation round-trip (Test 18) builds a minimal classfile with a single `RuntimeVisibleTypeAnnotations` carrying `Ljava/lang/Deprecated;`, runs it through `ClassfileUnpickler.read`, asserts `runtimeTypeAnnotations.head.annotationClass.name.asString.contains("Deprecated")`. This forces the full helper chain: `decodeTypeAnnotations` → `skipTypeAnnotationTargetAndPath` (with target_type=0x13 empty_target) → `JavaAnnotationUnpickler.readOneAnnotation`. The assertion checks the resolved class-name, not just non-emptiness. PASS.

Both held-out checks PASS. No design-vs-impl gap detected.

## Class-B findings (opus judgment)

1. **Default params on internal JavaMetadata case-class fields**
   `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala:248-252` adds 5 new fields to `JavaMetadata` each with `= Chunk.empty` / `= Maybe.Absent` defaults. The decisions doc claims "No default params in new MemberInfo or ClassAttributes fields" — TRUE for those — but **JavaMetadata received 5 default-valued fields**. Per user steering rule `feedback_no_default_params_internal` ("never add `= default` to internal/private APIs; update every caller explicitly"), this dodges call-site updates and reduces auditability. The decisions sweep claim is partially inaccurate.
   Recommendation: remove the defaults, update the two `JavaMetadata(...)` call sites in `ClassfileUnpickler.buildResult` and `buildOneMemberSymbol` to pass the values explicitly.

2. **AllowUnsafe-in-body shortcut on the writer side**
   `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/classfile/ClassfileUnpickler.scala:564-565` uses `import AllowUnsafe.embrace.danger` inside the conditional to call `_permittedSubclasses.set(...)`. This is the AllowUnsafe-in-body shortcut (write-side embraces the danger itself), NOT the Phase 02a propagate-the-proof pattern. The NEW `Symbol.permittedSubclasses(using AllowUnsafe)` accessor on the read side DOES follow propagate-the-proof (the proof is in the signature, caller supplies). Mixed pattern.
   Recommendation (class-B judgment): the writer is inside `buildResult` which already runs in a `Sync.defer`-rooted CPS context with `Frame` in scope — it could thread `AllowUnsafe` from the enclosing `buildOneClassSymbol` / `read` entrypoint instead. However, the existing pattern in this file (see other `SingleAssign.set` sites — e.g. `_position`, `_owner`) already uses `embrace.danger` locally. The phase follows the file's local convention; promoting all writer sites to propagate-the-proof is a class-C audit recommendation, not a Phase-11 blocker.

3. **Test placement drift from plan**
   Plan `tests.files` declares `[ClassfileUnpicklerTest.scala, TastySymbolTest.scala]`. Impl placed all 6 tests in `ClassfileReaderTest.scala`. The existing reader test is the correct topic-based home (it integrates `ClassfileUnpickler.read` end-to-end and already pins INV-008-adjacent behavior); placing them in a separate `ClassfileUnpicklerTest.scala` would split the topic. This matches `feedback_test_placement` ("tests go in existing topic-based files"). The plan's listed file `ClassfileUnpicklerTest.scala` does not exist. Recommendation: accept the placement; update the plan to reflect reality in a follow-up.

4. **`var` + `Chunk.appended` in tight while-loops**
   `ClassfileUnpickler.scala` lines 711-721 (`readBootstrapMethodsData`), 343-348 (NestMembers), 376-382 (PermittedSubclasses) build chunks with `var idxs = Chunk.empty[Int]; while ... idxs = idxs.appended(...)`. O(n²) for `Chunk.appended` semantics. The file already uses this pattern for `readClassRefList` / `readMethodParamList` (recursive CPS-style). The new while-loop sites depart from the file's idiom; could be rewritten with `ChunkBuilder` or with the existing recursive helper style. Class-C (performance hygiene, not correctness) — note for audit, not blocking.

5. **`Maybe.Absent` instead of `Absent`**
   `Tasty.scala:250` writes `nestHost: Maybe[Symbol] = Maybe.Absent`. The rest of the file uses bare `Absent`. Stylistic minor; not blocking.

No findings under: specific-to-catchall, hash/value collision, coverage-claim mismatch, bespoke-where-canonical-primitive-exists, stringly-typed dispatch, Frame propagation gap (all new helpers carry `(using Frame)`), refactor-invariant drift, re-framing failure as success, extension-on-owned-type, test-infra drift, stub-returns-expected-value, test-controls-its-own-signal, test-bypasses-API-under-test, fabricated-or-stale-facts.

## M8 / INV-008 verdict

PASS on substance. The 6 attributes are parsed, exposed on `JavaMetadata` (5 fields) plus the dedicated `Symbol.permittedSubclasses` accessor, and verified by 6 targeted tests including a synthetic round-trip for `RuntimeVisibleTypeAnnotations`. All 7 attribute name constants present in `ClassfileFormat.scala`. INV-008 is satisfied; the held-out acceptance check confirms downstream queryability for both Symbol-side (`permittedSubclasses`) and `JavaMetadata`-side (`runtimeTypeAnnotations`) channels.

## Symbol.permittedSubclasses accessor pattern

**The new accessor itself follows the Phase 02a propagate-the-proof pattern.**

`Tasty.scala:692`:
```scala
def permittedSubclasses(using AllowUnsafe): Maybe[Chunk[Symbol]] =
    if _permittedSubclasses.isSet then _permittedSubclasses.get() else Maybe.Absent
```

The signature requires the caller to supply `AllowUnsafe` evidence; the body uses it implicitly to call `.get()` on the `SingleAssign` cell. This matches the `body(using AllowUnsafe)` accessor pattern established by Phase 02d (e.g. `Symbol.body`) and the broader propagate-the-proof discipline.

**However, the WRITER (the `_permittedSubclasses.set(...)` call site) uses the AllowUnsafe-in-body shortcut**, not propagate-the-proof:

`ClassfileUnpickler.scala:563-565`:
```scala
if permittedSubSym.nonEmpty then
    import AllowUnsafe.embrace.danger
    classSym._permittedSubclasses.set(Present(permittedSubSym))
```

This matches the file's pre-existing convention for `SingleAssign.set` calls (e.g. `_position.set`, `_owner.set` in earlier phases) but is NOT the Phase 02a pattern. Mixed: read accessor = propagate-the-proof; write site = embrace.danger shortcut.

## Overrides

None. No `// flow-allow:` annotations present in the Phase 11 diff.

## Ready for commit?

**Remediation required before commit.**

Class-A blocker:
- **dev-tag**: 3 NEW `// Phase 11` comments in `ClassfileUnpickler.scala` (lines 276, 465, 1735) lack the `// DEV:` prefix. Either (a) prefix each with `// DEV:` OR (b) rewrite the comments to describe the code without naming the phase. Option (b) is preferred per `feedback_commit_as_you_work` style (phase references in source comments are noise after commit).

Class-B (recommendation, not blocking but flagged):
- Remove the 5 default-param values from `JavaMetadata` and update the 2 call sites explicitly (per `feedback_no_default_params_internal`).
- Decide whether to align the `_permittedSubclasses.set` writer with propagate-the-proof (audit-grade question, defer to class-C audit).

Cross-platform JVM/JS/Native: all green. Logs on disk under `kyo-tasty/audit-fixes/runs/phase-11-flow-verify-*-{jvm,js,native}-*.log`.

## Exit code: 1
