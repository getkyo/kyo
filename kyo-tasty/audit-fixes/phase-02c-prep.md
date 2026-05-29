# Phase 02c Prep: ClasspathRef AllowUnsafe Propagation

**File:** `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/query/ClasspathRef.scala`
**HEAD at prep time:** `c2103983b` (Phase 02b committed)

---

## 1. Signatures Captured

Current signatures (lines 19, 26, 33):

```scala
// Line 19 — keep assign unchanged (case 3 initialization, own import)
def assign(cp: Tasty.Classpath): Unit =
    import AllowUnsafe.embrace.danger
    slot.set(cp)

// Line 26 — migrate: drop inner import, gain (using AllowUnsafe)
def get(): Tasty.Classpath =
    import AllowUnsafe.embrace.danger
    slot.get()

// Line 33 — migrate: drop inner import, gain (using AllowUnsafe)
def isAssigned: Boolean =
    import AllowUnsafe.embrace.danger
    slot.isSet
```

After Phase 02c:

```scala
def get()(using AllowUnsafe): Tasty.Classpath = slot.get()
def isAssigned(using AllowUnsafe): Boolean    = slot.isSet
```

`assign` keeps its own `import AllowUnsafe.embrace.danger` (plan section 05-plan.md line 357 and §839 case 3 initialization confirmed).

---

## 2. Caller Cascade

`home.get()` and `home.isAssigned` call sites in production source:

| File | Lines | `home.isAssigned` | `home.get()` | AllowUnsafe already in scope? |
|------|-------|-------------------|--------------|-------------------------------|
| `kyo/Tasty.scala` | 645, 662, 673 | line 645 | lines 662, 673 | `companion` already has `(using AllowUnsafe)` (Phase 02a); proof flows. |
| `kyo/Tasty.scala` | 698, 700, 712 | line 698 | lines 700, 712 | `body` does NOT take `(using AllowUnsafe)` — returns `Tree < (Sync & Abort[TastyError])`. These calls are guarded inside a `Sync.Unsafe.defer` block (the inner `import danger` at line 708); proof is available there. No signature change needed at `body`. |

Call sites in test / helper source:

| File | Line | Pattern | Action needed |
|------|------|---------|---------------|
| `ClasspathRefDedupTest.scala` | 84 | `home.isAssigned` | Caller is inside `assignExtraHomes` test body; must acquire proof (wrap in `AllowUnsafe.apply` or accept `(using AllowUnsafe)` from the enclosing test helper). |
| `ClasspathTestHelpers.scala` | 32 | `sym.home.isAssigned` | File already imports `AllowUnsafe` and has `import AllowUnsafe.embrace.danger` at line 18 inside its helper method; the `isAssigned` call at line 32 is within that scope, so no additional change is needed. |
| `QueryApiTest.scala` | 606 | comment only, no direct call | No action. |

Summary: **2 production call-sites** (`companion` body, `body` accessor) require no signature change because proof is already in scope. **1 test call-site** (`ClasspathRefDedupTest.scala:84`) will need proof added. `ClasspathTestHelpers.scala:32` is already covered by the local `import danger`.

---

## 3. Phase 02a/02b State Check

HEAD `c2103983b` confirms:

- `Symbol` accessors (`fullName`, `companion`, `parents`, `typeParams`, `declarations`, etc.) all have `(using AllowUnsafe)` (Phase 02a).
- `Classpath` pure accessors (`pureClass`, `purePackage`, `purePackages`, `pureTopLevelClasses`, `accumulatedErrors`, `pureModule`, `pureClassByBinary`) all have `(using AllowUnsafe)` (Phase 02b).
- The `companion` accessor at `Tasty.scala:643` already carries `(using AllowUnsafe)` and calls `home.isAssigned` (line 645) and `home.get()` (lines 662, 673). When Phase 02c adds `(using AllowUnsafe)` to those methods, the proof flows through `companion`'s own parameter with zero additional changes.
- The `body` accessor calls `home.isAssigned` and `home.get()` inside a `Sync.Unsafe.defer` block that already has `import AllowUnsafe.embrace.danger` at line 708. That inner import satisfies the new `(using AllowUnsafe)` requirement. No change needed to `body`.
- **No new test files will be cascaded.** `ClasspathRefDedupTest.scala:84` needs one proof injection; all other test files are unaffected.

---

## 4. Concerns

### New test file `ClasspathRefTest.scala`

The plan (`05-plan.md` lines 401, 407) lists two test invariants pinned to `INV-001`:

- `ClasspathRef.get` requires explicit proof
- `ClasspathRef.isAssigned` reflects assignment

These are specified to go in `ClasspathRefTest.scala`. That file **does not exist yet** (only `ClasspathRefDedupTest.scala` exists). Phase 02c must create it. The two tests are straightforward: one confirms `get()` won't compile without proof (negative compile test) and one confirms the assigned/unassigned round-trip.

### `assign` must not be migrated

The plan explicitly states `assign` keeps its own `import danger` (case 3 initialization). The current code at lines 19-23 matches this requirement exactly. Phase 02c must not touch `assign`.

### `ClasspathRefDedupTest.scala:84`

After the migration, `home.isAssigned` requires `(using AllowUnsafe)`. The call at line 84 is inside a test assertion; it needs `AllowUnsafe.apply { ... }` wrapping or the enclosing lambda to accept the proof. Check whether `assignExtraHomes` test context already has a proof in scope before deciding the injection point.

---

## Self-Check Verdict

PASS. Signatures captured from the actual file. Caller cascade is complete within the kyo-tasty subtree (4 production call-sites across 2 files, 2 test-tier call-sites). Phase 02a/02b state confirmed at HEAD `c2103983b`. The `assign` exclusion is consistent with the plan. The missing `ClasspathRefTest.scala` is noted as required. No cross-module cascade.
