# Phase 03b audit

Time: 2026-05-30T00:35:01Z
HEAD: a373a7fa6
Phase commit: a373a7fa6 ("kyo-tasty Phase 03b: validate Interner bytesEqual offset")
Plan cites: ./05-plan.md §Phase 03b (lines 907-977)
Design cites: ./02-design.md §INV-010; ./04-invariants.md:50

## Test count

| Leaf | Status | Notes |
|---|---|---|
| 1: bytesEqual rejects offset + length > bytes.length | PRESENT_STRICT | InternerTest.scala:226 (Case 1) |
| +1: negative offset rejected | OVER-DELIVERY | InternerTest.scala:236 (Case 2) |
| +2: negative length via hash collision, message asserted | OVER-DELIVERY | InternerTest.scala:248-269 (Case 3) |
| +3: valid zero-length intern succeeds | OVER-DELIVERY | InternerTest.scala:272 (Case 4) |

Plan declared 1 leaf; HEAD ships 4. Each new test pins a distinct B10 / INV-010 condition (overflow, negative offset, negative-length hash-collision into `bytesEqual` defense-in-depth, positive zero-length). Categorized as over-delivery, not under-coverage; Phase 03b verify already routed this as WARN.

## CONTRIBUTING.md violations

- None. No em-dash, no `AllowUnsafe`, no `Option` / `Maybe` confusion, no semicolons, no `asInstanceOf`, no default-params, no `Frame.internal`, no `java.util.concurrent` newly introduced (the `AtomicInteger` / `AtomicReferenceArray` already in Interner.scala are pre-existing and properly justified by the sharded intern table).

## Unsafe markers

- None added by this phase. `Interner.scala` does not use `AllowUnsafe`; access is via standard JDK atomics.

## Cross-platform consistency

- platforms checked: jvm, js, native
- Per-platform deltas: NONE. Phase 03b verify report records JVM 15/15, JS 15/15, Native 15/15 after the reordering fix. The top-of-`intern` explicit `throw new ArrayIndexOutOfBoundsException(...)` closes the JS UndefinedBehaviorError gap because no raw `bytes(offset+i)` access happens before the guard fires.

## Naming convention compliance

- Message uses `Interner.intern: ...` and `Interner.bytesEqual: ...` prefix, consistent with the Phase 03a `Varint`, `ByteView`, `NameUnpickler`, `SectionIndex` diagnostic-prefix convention.

## Steering deviation

- `git show --stat a373a7fa6`: `Interner.scala` (+10), `InternerTest.scala` (+61), plus `phase-03b-baseline.txt`, `phase-03b-decisions.md`, `phase-03b-verify.md`, `phase-03a-audit.md` (flow artifacts). Matches the plan's `files_modified` list. Phase 03a audit traveling under the Phase 03b commit is acceptable under the SLOT-B audit schedule and documented in the commit message.

## Anti-flakiness measures

- Test 3 (Case 3) explicitly pre-computes the FNV-1a seed hash (`0x011c9dc5`) and pre-seeds the empty-slice entry so the hash-collision path into `bytesEqual` is deterministic. No timing, no randomness. PASS.

## Architecture substitution check

- Design intent: bound `Interner` byte-slice access uniformly so the public `intern(bytes, offset, length)` rejects out-of-range input before any byte access, INV-010 honored on the Interner surface across JVM / JS / Native.
- HEAD reality: top-of-`intern` guard (lines 49-53) checks `offset < 0`, `length < 0`, `offset + length < 0` (Int overflow), `offset + length > bytes.length`, throws `ArrayIndexOutOfBoundsException` with a structured message. `bytesEqual` retains the identical guard (lines 131-135).
- Verdict: MATCH. The reordering matches the design intent better than the original plan text (which placed the guard only inside `bytesEqual`). The plan placement would not have closed the JS contract; HEAD's top-of-`intern` placement does.

## Documentation drift

- Scaladoc / README additions in this phase: NONE on `Interner.scala`; docstrings unchanged. Test file gains 4 comment blocks (Case 1-4) documenting which guard path each test exercises. No drift beyond plan intent.

## Class-C judgment

1. **Reordering correctness (focus #1).** Confirmed by reading `Interner.scala:48-57`. The guard is the first executable statement of `intern`; `computeHash` is called only on line 54, strictly after the guard. PASS.

2. **JS / Native UBE closure (focus #2).** Confirmed. On JS, the `throw new ArrayIndexOutOfBoundsException(...)` on line 50 is an explicit construct-and-throw, not a raw array-index access, so Scala.js raises AIOOBE (not UndefinedBehaviorError) on all four B10 cases. Native exhibits the same behavior because the early throw avoids the FFI / SN array-bound semantics path. Verify ledger shows JVM 15/15, JS 15/15, Native 15/15. PASS.

3. **bytesEqual guard reachability (focus #3).** With the top-of-`intern` guard in place, every public call path validates before reaching `internInShard`, which is the only caller of `bytesEqual`. Therefore `bytesEqual`'s internal guard is structurally unreachable on the public surface. The decisions log (phase-03b-decisions.md:90-94) acknowledges this and frames it as "defense-in-depth for any future internal caller that bypasses `intern`". Since `bytesEqual` is `private def` (Interner.scala:130) there are no current internal callers other than `internInShard`, but the guard is cheap and correctly written. NOTE: the redundancy should be documented in `bytesEqual`'s body comment so future maintainers do not infer the guard is load-bearing (right now there is no comment explaining the guard is now redundant).

4. **InternerTest scenario authenticity (focus #4).** Cases 1, 2, and 4 are straightforward bounds tests, now serviced by the top-of-`intern` guard (no longer by `bytesEqual`). Case 3 is the non-trivial one: it pre-computes the FNV-1a seed (`0x811c9dc5 & 0x7fffffff = 0x011c9dc5`), interns the empty slice to plant an entry with that exact hash, then calls `intern(bytes, 0, -1)`. With `length = -1`, `computeHash` iterates zero times and returns the seed hash, triggering a slot-probe match against the planted entry, which calls `bytesEqual` and fires the internal guard with `length=-1` and `bytes.length=5` in the message. This genuinely exercises the `bytesEqual` defense-in-depth path. Test 3 is the only case that reaches `bytesEqual`'s guard; the others now fire the top-of-`intern` guard. The assertion checks the message contains `length=-1` and `bytes.length=5`, which both prefixes (`Interner.intern:` and `Interner.bytesEqual:`) satisfy, so Case 3 is robust to either path firing. PASS.

5. **INV-010 cumulative coverage (focus #5).** Phase 03a established structural bounds on `Varint` (continuation cap, `MalformedVarintException`), `ByteView.subView` (from/until rejection), `NameUnpickler` (indexed lookup bounds), `SectionIndex.readSync` (nameRef / sectionLen). Phase 03b adds `Interner.intern` (4-clause guard). The five surfaces named by INV-010 are now all guarded. The INV-010 literal text says "structured `TastyError.MalformedSection` rather than uncaught exception"; the actual implementations throw `MalformedVarintException` (Phase 03a Varint), raw AIOOBE (Phase 03a ByteView / NameUnpickler / SectionIndex), and explicit-message AIOOBE (Phase 03b Interner). The phase-03b verify report flags this as documentation drift (INV-010 wording vs. realized contract). Since the surrounding `TastyError.MalformedSection` wrapping happens at the decode-loop catch site, not at the primitive throw site, the spirit of INV-010 holds: every primitive surfaces a structured exception (with a diagnostic message) rather than silently corrupting state. The 04-invariants.md text should be updated to acknowledge the per-primitive exception types; this is a NOTE for the end-of-project cleanup, already routed in phase-03a audit.

## Findings (categorized)

- BLOCKER: none.
- WARN: none. (Test count over-delivery already routed by phase-03b verify; not a class-C judgment failure.)
- NOTE 1: `Interner.scala:130-135` — add a one-line comment noting `bytesEqual`'s internal guard is now unreachable via the public `intern` surface, kept only as defense-in-depth for hypothetical future internal callers. Prevents a future maintainer from inferring it is load-bearing and accidentally relying on it.
- NOTE 2: 04-invariants.md INV-010 wording still says "structured `TastyError.MalformedSection`" but the realized contract is "structured per-primitive exception with diagnostic message, wrapped to `MalformedSection` by the decode-loop catch". Reconcile in end-of-project cleanup (consistent with the Phase 03a audit NOTE already on record).

## Routing

- BLOCKER findings: none. SLOT-A launch of Phase 04a (INV-012) is unblocked.
- WARN findings: none.
- NOTE 1: TaskCreate for end-of-project cleanup — add `bytesEqual` defense-in-depth comment.
- NOTE 2: TaskCreate for end-of-project cleanup — reconcile INV-010 wording (already queued from Phase 03a).

## NOTE for Phase 04a prep

Phase 04a (INV-012, `kyo-tasty/audit-fixes/05-plan.md:5242` context area) inherits a clean Interner surface. No carryover. Phase 04a prep should NOT re-investigate INV-010 — it is closed on the binary-input + Interner surfaces. Phase 04a prep MAY assume `Interner.intern` is fully bounds-checked at entry, which simplifies any caller-side reasoning Phase 04a needs to do about `Interner.Entry` lifecycle.

## Overall verdict

PASS — ready for Phase 04a SLOT-A. Two informational NOTEs queued for end-of-project cleanup; neither blocks downstream phases. The reordering fix is correct, the cross-platform contract is uniform, the 4 InternerTest scenarios genuinely exercise the new contract (Case 3 is the standout, structurally reaching the `bytesEqual` defense-in-depth path via deliberate hash collision), and the INV-010 cumulative surface (Varint, ByteView, NameUnpickler, SectionIndex, Interner) is now structurally guarded.
