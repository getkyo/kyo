# Phase 4 v2 In-Flight Review 1
**G24: Wire Symbol.companion via FQN lookup**
Pulse: 1 | Date: 2026-05-25

---

## Pattern Matrix

| Pattern | Verdict | Citation |
|---|---|---|
| Reflect.scala:276 stub replaced | CLEAN | Reflect.scala:286 -- full implementation, no stub body |
| companion FQN computation present (Class/Trait -> Foo$, Object -> strip $) | CLEAN | Reflect.scala:294-308 |
| isJava guard returns Absent | CLEAN | Reflect.scala:287 |
| Non-class/non-object kinds return Absent | CLEAN | Reflect.scala:310 `case _ => Kyo.lift(Maybe.Absent)` |
| Effect row aligned with lookupClass (Sync & Async & Abort) | CLEAN | Reflect.scala:286 matches Classpath.scala:83 |
| 4 new tests present | CLEAN | QueryApiTest.scala:679, 709, 739, 759 |
| Tests are strict (not assertion-tautology) | CLEAN | each checks kind and name, ClasspathClosed test rejects Success |
| No new asInstanceOf in production | CLEAN | grep clean |
| No Frame.internal | CLEAN | grep clean |
| No em-dashes in modified files | CLEAN | grep clean |
| No new null / var | FLAG (minor) | Reflect.scala:295 uses `owner != null` guard -- this is in pre-existing owner-chain traversal logic, not new to Phase 4 |
| No new AllowUnsafe without // Unsafe: | CLEAN | all AllowUnsafe sites at lines 60, 237, 259, 268, 277 carry `// Unsafe:` comments |

---

## Scope-Cutting Check (4 plan leaves)

| Test | Status | Notes |
|---|---|---|
| T1: case class Point companion returns Present(objectSym), kind == Object | PRESENT_STRICT | QueryApiTest.scala:679; checks kind AND name, rejects Absent and failures |
| T2: companion object's companion returns Present(classSym), kind == Class | PRESENT_STRICT | QueryApiTest.scala:709; reverse lookup, checks kind AND name |
| T3: plain class with no companion returns Absent | PRESENT_STRICT | QueryApiTest.scala:739; uses PlainClass fixture, rejects Present |
| T4: companion after classpath close returns ClasspathClosed | PRESENT_STRICT | QueryApiTest.scala:759; captures symbol outside Scope, then verifies ReflectError.ClasspathClosed |

All 4 leaves present and strict.

---

## Flags

**FLAG (informational, non-blocking): FQN construction for Class/Trait case (Reflect.scala:295)**

The implementation decomposes fullName as `ownerFqn + "." + name.asString + "$"` rather than the plan's simpler `sym.fullName.asString + "$"`. The two are equivalent for the normal case. However, the `ownerFqn` guard is:

```
if owner != null && (owner.owner ne owner) then owner.fullName.asString
else owner.name.asString
```

When `owner` is the root sentinel (owns itself), the else branch yields `owner.name.asString`, which is typically empty, producing `"." + simpleName + "$"`. This edge case (top-level class in the empty root package) is unlikely in practice but is a latent defect. The Object path at line 302 uses `fullName.asString` directly and strips "$", which is cleaner.

Recommendation: replace the Class/Trait ownerFqn decomposition with `fullName.asString + "$"` directly, mirroring the plan text and eliminating the edge case. Not a blocker for current tests.

**FLAG (informational): `home.isAssigned` silent Absent (Reflect.scala:288)**

When `home` is not assigned (symbol was not loaded via a classpath -- synthetic or root symbols), the method returns `Absent` silently rather than `ClasspathClosed`. This is correct behavior (no classpath to be closed), but Test 4 only covers the post-close case, not the never-assigned case. Not a plan requirement; noted for completeness.

---

## Overall Assessment

PROCEED. Implementation is functionally correct and all 4 plan tests are present and strict. The FQN decomposition flag at line 295 is a latent edge case worth a one-line cleanup before this phase closes, but does not block the current test suite.
