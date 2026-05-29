# Steering rules for the kyo-tasty audit-fix campaign

These constraints bind every agent dispatched in this campaign. Read on
every cycle.

## Scope integrity

- Every finding from every audit (correctness, completeness, clarity,
  potential bugs, test coverage, CONTRIBUTING.md adherence) MUST appear
  in the plan as a concrete phase deliverable.
- No scope cuts. No "out of scope". No "we can revisit". No "for now".
  If a finding cannot be addressed inside kyo-tasty, the phase records
  the cross-module dependency and the work continues.
- Do not drop, weaken, or substitute. Implement exactly per plan or
  escalate to the supervisor.

## Atomic phase sizing (HARD constraint for the restructure)

Empirical lesson from the first Phase 01 run: bundled phases make
ceremony expensive without buying any verification guarantee. The
restructure binds the planner to these rules:

- **Phase name format:** `<verb> <noun>`. Zero conjunctions. No
  "AND". No commas joining multiple actions. "Rewrite documentation"
  looks atomic but bundled 6+ unrelated changes; the rename, the
  vaporware-section deletion, the Goals split, the scaladoc
  expansion, the comment cleanup, and the locked-decision inversion
  each become their own phase.
- **One INV per phase.** Each phase's `produced_invariants` list
  contains exactly one INV. If a single conceptual change produces
  multiple INVs, that is fine, but multiple INVs from unrelated
  changes signal a bundle that must split.
- **One acceptance criterion per phase.** Verifiable by a single
  targeted test, grep, or compile check. "Multiple acceptances"
  signals a bundle.
- **LoC budget:** 50-200 lines of change per phase (src + tests +
  config). Oversize-justified should be RARE, reserved for genuine
  irreducible atomic units like an in-tree RFC 1951 inflate where
  the spec literally cannot split.
- **Multiple files OK only when supporting one conceptual change.**
  A rename that touches 5 files is one phase. Renaming + restructure
  + deletion across the same 5 files is 3 phases.
- **Canonical model: Phase 18a-e** (Tree decoder split by TASTy
  category). One parent finding (M1) decomposed into 5 atomic
  sub-phases. Apply the same pattern wherever a phase aggregates
  more than one conceptual unit.

When the restructured plan lands, every phase satisfies the above
or is explicitly justified.

## No priority, no human-time estimates

- Phases ordered solely by technical dependency. No "high priority" /
  "important" / "first" / "nice to have" / "if time permits".
- No human-time estimates ("weeks", "days"). Roadmap is dependency-DAG,
  not calendar.

## Performance non-regression

- The kyo-tasty cold-load and warm-cache paths were tuned heavily. The
  plan MUST NOT regress those paths.
- Specifically: do NOT wrap routine pure accessors (Symbol.fullName,
  Symbol.parents, etc.) in `Sync.Unsafe.defer` if that adds an
  allocation per call. The preferred fix per CONTRIBUTING.md §828
  option 1 is to propagate the proof: change the accessor signature to
  take `(using AllowUnsafe)`, return the raw value, no `Sync` wrapping.

## Side-effect tracking discipline

- All side effects must be tracked. Routine accessor bodies must NOT
  hide the proof via `import AllowUnsafe.embrace.danger`. Either the
  signature takes `(using AllowUnsafe)` (propagate the proof, §828
  option 1), or the work is bridged via `Sync.Unsafe.defer` (§833
  option 2).
- `import AllowUnsafe.embrace.danger` stays ONLY at the §839 case 3
  boundaries: classpath initialization (Phase A/B/C orchestration),
  unpickler decode passes, external runtime callbacks, app boundaries,
  test helpers.
- Do NOT introduce a two-tier `Symbol.Unsafe` / `Classpath.Unsafe`
  opaque-type companion. Per CONTRIBUTING.md §794-§812 that pattern is
  for concurrent types (Channel, Queue, Hub) with effectful operations
  on both tiers. Symbol and Classpath are data containers; the §828
  signature-propagation path is the correct fix.

## Code-quality rules from CONTRIBUTING.md and memory

- No em-dashes (`—` / `–`) anywhere in plan content, code, commit
  messages, or chat.
- No `asInstanceOf` unless inside an opaque-type boundary or kernel
  internal, with a justifying comment.
- No `null` in new code; existing protocol-driven null sentinels in
  unpicklers may stay.
- No `==` between types without `CanEqual`.
- No `var` for shared mutable state; use Atomic*.
- No semicolons chaining statements.
- No type aliases for effect rows.
- No backwards-compatibility shims.
- No default parameters on internal / private APIs.
- No new ScalaTest features (withFixture / FutureOutcome /
  TestCanceledException); stick to AsyncFreeSpec + assert/fail/cancel +
  `kyo.Test` helpers.
- Tests use the public API on LHS; internals only on RHS for
  verification.
- Test files placed by topic; never phase-coded names.
- Shared cross-platform tests live in `shared/`; never demoted to a
  platform folder to dodge infra cost.

## Process

- Agents NEVER `git add` or `git commit`. Supervisor commits between
  phases.
- Make changes one at a time per CLAUDE.md.
- Present plans and wait for approval before implementing.
- Create analysis files before changes.
- Every flow-* subskill (flow-explore, flow-design, flow-resolve-open,
  flow-invariants, flow-plan, flow-validate, flow-phase-prep,
  flow-phase-impl, flow-phase-pulse, flow-verify, flow-phase-audit,
  flow-sweep, flow-strip-dev, flow-resume) is invoked via the Skill
  tool. Never substitute a custom Agent for any of them. The class-A
  enforcement scripts only run against artifacts produced through the
  subskills.

## Test scenario discipline (from flow-plan.md rules)

- Test entries in the plan are numbered scenarios with Given, When,
  Then, Pins fields. Each `Then` states a concrete expected result.
  Hand-wave `Then` like "works" / "is correct" / "compiles" /
  "passes" / "ok" is rejected by
  flow-validate-test-substance.sh.
- Test file naming rule: PREFIX match against the source basename.
  `Tasty.scala` is covered by any test file whose name starts with
  `Tasty` (e.g., `TastyTest`, `TastySymbolTest`, `TastyTypeTest`).
- Preferred: 1:1 (`Foo.scala` ↔ `FooTest.scala`).
- Allowed exception: topic-split (`FooSymbolTest`, `FooTypeTest`)
  when decomposition is genuinely warranted by the source surface.
- Best to avoid: scenario-coded suffixes
  (`FooIdempotenceTest`, `FooOverflowTest`, `FooLeakTest`). These
  prefix-match so they are not forbidden, but prefer placing the
  scenario as a numbered entry inside the topic file. Reach for a
  scenario-suffixed file only when the scenario set is large enough
  to warrant its own file.
- New test files appear only for new source files
  (`### Files to produce`). Modified source files extend an existing
  prefix-matching test file.
- Test code is never written in the plan; the scenario substance is
  the contract impl reads against.
