# Phase 02d audit

Time: 2026-05-29T22:00:00Z
HEAD: c8fb91dd8
Phase commit: c8fb91dd8
Plan cites: ./05-plan.md §Phase 02d (yaml: 05-plan.yaml id="02d")
Design cites: ./02-design.md §Symbol.body Sync.Unsafe.defer bridge

## Test count
| Leaf | Status | Notes |
|---|---|---|
| 1: no `import AllowUnsafe.embrace.danger` in `Symbol.body` | PRESENT_STRICT | TastyTest.scala:198-218; uses indexWhere bounding `def body(using Frame)` → `end body`; scope correctly excludes `_bodyOnce` init lambda |
| 2: `Sync.Unsafe.defer` present in `Symbol.body` | PRESENT_STRICT | TastyTest.scala:220-239; same scoping |

Plan listed 2 tests in `TreeUnpicklerTest.scala`. Impl placed both in `TastyTest.scala`. Justification (phase-02d-decisions.md §4): `TreeUnpicklerTest.scala` does not exist; steering rule "new test files appear only for new source files" routes to the existing prefix-matching `TastyTest.scala`. Count preserved (2/2).

## CONTRIBUTING.md violations
- None. Inner `import AllowUnsafe.embrace.danger` removed; `Sync.Unsafe.defer` is the §828 option 2 bridge.

## Unsafe markers
- Tasty.scala:699: `Sync.Unsafe.defer:` — preceded by `// Unsafe: ClasspathRef.isAssigned and ClasspathRef.get() are unsafe-tier helpers; covered by Sync.Unsafe.defer which provides AllowUnsafe implicitly (Sync.scala:138-141).`
- Tasty.scala:707: `home.get().isClosed` — preceded by `// Unsafe: ClasspathRef.get() and Classpath.isClosed read AtomicRef state; covered by the enclosing Sync.Unsafe.defer.`
- Tasty.scala:713: `_bodyOnce.get()` — preceded by `// Unsafe: OnceCell.get() is an unsafe-tier helper; covered by the enclosing Sync.Unsafe.defer.`
- Tasty.scala:553: init lambda `import AllowUnsafe.embrace.danger` retained (§839 case 3 init path; out of scope for INV-002).

## Cross-platform consistency
- platforms checked: jvm, js, native
- Per-platform deltas: none. Only `shared/` sources touched. Phase commit message records JVM 8/8 PASS, JS/Native compile PASS.

## Naming convention compliance
- None violated. No new names introduced.

## Steering deviation
- `git diff --name-only c8fb91dd8~1 c8fb91dd8` source/test scope: Tasty.scala + TastyTest.scala. AUTHORIZED=2 matches.
- Test file location deviation from plan yaml (TastyTest.scala vs TreeUnpicklerTest.scala) explicitly justified in phase-02d-decisions.md §4. No drift.

## Anti-flakiness measures
- Source-text regex tests use line-bounded slice (`indexWhere(def body...)` to `indexWhere(end body)`) which is deterministic against the committed source. No timing/IO/JIT dependencies.

## Architecture substitution check
- Design intent: `Sync.Unsafe.defer { ... }` wrap per §828 option 2 (design.md cites §833; commit cites §828; same option-2 pattern).
- HEAD reality (Tasty.scala:698): `Sync.Unsafe.defer:` colon-syntax block enclosing the entire `TastyOrigin` branch including `home.isAssigned`, `home.get()`, `home.get().isClosed`, `_bodyOnce.get()`. Inner kyo-effect constructors (`Abort.fail`, `Sync.defer(t)`) remain inside the lifted suspension; this is type-correct because `Sync.Unsafe.defer` returns `A < (Sync & S)` and propagates the inner effect row.
- No `Sync.defer` substituted for `Sync.Unsafe.defer` (safe-tier confusion check: grep shows `Sync.Unsafe.defer:` at line 699, and only one safe `Sync.defer(t)` at line 733 which is the legitimate effect-row return for the decoded tree).
- No double-wrapping. Single defer at the branch entry.
- Verdict: **MATCH**.

## INV-002 zero-allocation
- `Sync.Unsafe.defer` is `inline def defer` (Sync.scala:138-141, body `Effect.deferInline { f(using AllowUnsafe.embrace.danger) }`). Inline expansion plus inline argument means no closure allocation per call.
- Verdict: **PASS**. Zero per-call allocation regression at the `Symbol.body` boundary.

## _bodyOnce init lambda preservation
- HEAD Tasty.scala:553 retains `import AllowUnsafe.embrace.danger` inside the init lambda passed to `new OnceCell[Tree](() => ...)`. Confirmed via grep: 7 surviving `import AllowUnsafe.embrace.danger` sites in Tasty.scala (lines 392, 553, 753, 769, 941, 995, 1019); none inside the `Symbol.body` method (lines 692-738).
- Verdict: **PRESERVED**.

## Test integrity (regex scoped correctly)
- Both tests bound the search to `lines.slice(startIdx, endIdx + 1)` where `startIdx = indexWhere(_.contains("def body(using Frame): Tree < (Sync & Abort[TastyError])"))` and `endIdx = indexWhere(l => l.trim == "end body", startIdx + 1)`. The `def body` method starts at line 692 and ends at line 738. The `_bodyOnce val` definition at line 548 (with its init-lambda import at line 553) sits **above** `startIdx`, so the slice correctly excludes it.
- Counter-test sanity: if Phase 02e or later refactors the body block, `end body` must remain or tests will fail loudly (intended).
- Verdict: **CORRECTLY SCOPED**.

## Documentation drift
- Scaladoc additions: none on the public `body` signature. Inline `// Unsafe:` comments rewritten to reference `Sync.Unsafe.defer` rather than the removed `import danger` (mandatory documentation, not drift).
- Commit message accurate: cites §828 option 2, Sync.scala:138, all call sites correctly enumerated, _bodyOnce init lambda exception called out (§839 case 3).
- Decisions doc (phase-02d-decisions.md) consistent with HEAD: §1 wrap whole branch ✓, §2 single defer ✓, §3 `end if` placement ✓, §4 TastyTest.scala routing ✓, §5 two source-text scans ✓.
- Beyond plan intent: no.

## API surface integrity
- `def body(using Frame): Tree < (Sync & Abort[TastyError])` byte-identical between c8fb91dd8~1 and c8fb91dd8.
- Verdict: **UNCHANGED**.

## Diff containment
- `git show c8fb91dd8 --stat` source/test files: `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala` + `kyo-tasty/shared/src/test/scala/kyo/TastyTest.scala`. AUTHORIZED matches; SUPPORTING-CASCADE=0 (commit message confirms callers already in Sync context).
- Remaining commit files are audit-fixes/*.md (phase-02c-audit.md, phase-02d-{baseline,decisions,prep,verify}.md) — flow artifact churn, not code drift.
- Verdict: **CONTAINED**.

## Findings (categorized)
- BLOCKER: none.
- WARN: none.
- NOTE-1: Plan yaml lists `tests.files: [TreeUnpicklerTest.scala]` but impl routed to TastyTest.scala. Justified by decisions §4 and steering rule; queue a NOTE for end-of-project plan-yaml reconciliation (or accept the divergence as documented). Not actionable for Phase 02e.
- NOTE-2: Inner unsafe-call comments at HEAD lines 705-707 reference "the enclosing Sync.Unsafe.defer" twice in three lines; readable but slightly verbose. Cosmetic only.

## Routing
- BLOCKER findings: none. SLOT-A for Phase 02f is unblocked.
- WARN findings: none.
- NOTE findings: NOTE-1 → end-of-project plan-yaml reconciliation; NOTE-2 → end-of-project cleanup pass.

## Phase 02e prep input
- Phase 02e (Privatize Symbol.TastyOrigin.addrMap, INV-011) does not touch `Symbol.body`. No carry-forward concerns. The 7 remaining `import AllowUnsafe.embrace.danger` sites (lines 392, 553, 753, 769, 941, 995, 1019) are scoped to later A4 phases (02f+) per the plan; not Phase 02e's concern.

## Overall
**Ready for next phase.** Architecture matches §828 option 2 (verdict MATCH), zero-allocation invariant holds (Sync.Unsafe.defer is `inline`), init lambda preserved, tests correctly scoped, diff contained to authorized files, public signature unchanged.
