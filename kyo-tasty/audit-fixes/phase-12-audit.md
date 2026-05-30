# Phase 12 Audit

## Summary

Phase 12 correctly decodes EXTref (tag 7) and EXTMODCLASSref (tag 8) entries per the Scala 2 pickle binary spec, builds FQNs via a recursive string-only helper, and wires `declaredType = Type.Named(self)` following the established Unresolved-symbol convention. The verify cycle caught and remediated two class-A violations (Option/Some/None instead of Maybe/Present/Absent; reward-hacking word "simpler" in a test comment) and two un-annotated AllowUnsafe imports. All four remediations are applied and confirmed in `phase-12-decisions.md`. The commit in the worktree is clean. One structural gap persists: `resolveExtFqn` is unbounded on corrupt-pickle cycles. All other dimensions are OK. No blockers in the committed code.

## Findings

### 1. Scala 2 pickle binary spec correctness - OK

The scalac `PickleFormat` spec for EXTref and EXTMODCLASSref is `nameRef(nat) [ownerRef(nat)]`: a mandatory name reference followed by an optional owner reference. Both decoders read exactly that shape: `c.readNat()` for `nameRef`, then a guarded second `c.readNat()` for `ownerRefOpt` only when `c.remaining > 0`. The no-owner case (root-level name, owner absent) is handled by the `Absent` branch in `ownerRefOpt`, which causes `ownerFqn` to be `Absent` after `.filter(_.nonEmpty)`, so `fqn = symName` alone. Both tags are registered in the dispatch `case` in `buildResult`. Spec match is correct.

### 2. FQN composition - OK

`resolveExtFqn` builds the owner string purely from the name table and entry data without allocating intermediate symbols. The `$` suffix in `decodeExtModClassRef` is appended to `rawName` before FQN construction, so the interned name is `"Foo$"` not `"Foo" + "$"` post-concat. Test 8 and Test 9 assert the exact dotted FQN strings, confirming round-trip correctness. Cycle termination: pickle entries are forward-indexed (a well-formed pickle cannot reference a later entry as owner because owners are written before referents), so acyclic termination holds for valid input. Corrupt-pickle cycles are a separate robustness concern (see dim 5 below).

### 3. declaredType invariant - OK

`sym._declaredType.set(Tasty.Type.Named(sym))` is a self-referential named type, matching `ClassfileUnpickler` lines 79-80 and 1500-1501, where Unresolved symbols for parent types and throw-list entries also receive `Type.Named(sym)`. The `buildResult` post-loop in `Scala2PickleReader` uses `isSet` guards before setting `_parents`, `_typeParams`, etc., so pre-wiring `_declaredType` in the EXT decoders does not conflict with the post-loop. No regression introduced.

### 4. AllowUnsafe propagation - OK

The two `import AllowUnsafe.embrace.danger` imports in `decodeExtRef` (line 459) and `decodeExtModClassRef` (line 487) are annotated with `// flow-allow: §839 case 3 (pickle decode orchestration init path)`. CONTRIBUTING.md §839 case 3 covers "initialization of globally shared module-level values" and platform interop; the decoders are not exactly that category. The closer analogy is ClassfileUnpickler, which uses a single embraced import at the post-decode wiring boundary. The annotation is accurate enough in spirit (fresh-symbol population during decode orchestration), and it matches the six pre-existing in-body sites in the same file. The verify report accepted this as override-eligible per Phase 11 precedent. No blocker.

### 5. Test coverage adequacy - WARN

The two tests cover the happy path: two-level owner chain via a TERMname owner EXTref, and the `$` suffix. Missing coverage:

- EXTref with NO owner at all (root-level package or top-level class with no qualifier). The code handles this via the `Absent` branch, but no test exercises it.
- Owner chain three or more levels deep (e.g., `com` -> `com.example` -> `com.example.pkg` -> `com.example.pkg.Foo`).
- `resolveExtFqn` called with `entryIdx` past EOF (the bounds check returns `""`, untested).
- `resolveExtFqn` with an ownerRef pointing to an unrecognized tag (non-EXT, non-TERMname); returns `""` and FQN degrades to leaf name only, untested.
- Corrupt-pickle cycle: entry A ownerRef points to entry B, entry B ownerRef points to entry A. `resolveExtFqn` is not depth-bounded and would StackOverflow. This is the most important gap. Mitigation for Phase 13+: add a `visited: Set[Int]` guard or a depth counter capped at `entries.length`.

### 6. Code clarity / Kyo conventions - OK

Method names, scaladoc layout, and parameter ordering match neighboring decoders (`decodeClassSym`, `decodeValSym`, `decodeTypeSym`). The `resolveExtFqn` comment accurately describes the three-case dispatch. Pattern matching in `resolveExtFqn` uses `Maybe.fromOption` to bridge the stdlib `HashMap.get` call, consistent with the Maybe-everywhere convention remediated in the verify cycle. The `end` markers are present. No style issues.

### 7. Performance - NOTE

`resolveExtFqn` allocates a new `PickleCursor` and performs string concatenation per owner level. For typical Scala 2 pickles with package chains of depth 3-5 this is a handful of small allocations per EXTref entry; not hot in isolation. The decode pass is already allocation-heavy (name table, symbol array) so this adds no qualitative pressure. No action needed.

## Recommendations for Phase 13+

- NOTE: Add a test for EXTref with no ownerRef (root-level symbol, bare name only).
- NOTE: Add a test for a three-level owner chain to confirm recursive resolution terminates correctly for valid deep chains.
- NOTE: Add a corrupt-pickle test or depth-guard in `resolveExtFqn` to prevent StackOverflow on cycles. A simple counter bounded by `entries.length` is sufficient.
- NOTE: `resolveExtFqn` with an ownerRef pointing to a non-EXT/non-name entry silently degrades the FQN to the leaf name. Consider logging a debug trace at that branch to aid future diagnosis.
