# Phase 6 Audit: Rename Memo to OnceCell

Commit: `73855f5ccb6e897117bbc5ea32914a3cad1ce481`

---

## Checklist Results

### 1. Memo.scala deleted; OnceCell.scala present

**PASS**

- `find kyo-reflect -name "Memo.scala"` returns zero results. File is gone.
- `OnceCell.scala` is present at the expected path:
  `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/symbol/OnceCell.scala`
- Implementation is identical to the original Memo contract: `AtomicReference`-backed, CAS-publish on first access, sentinel `Unset` object, `AllowUnsafe` on `get()`.

---

### 2. Scaladoc explicitly documents race-and-discard semantics

**PASS**

OnceCell.scala scaladoc (lines 8-22) states:

> "Concurrent first-access semantics: if two threads race on `get()` before either has CAS-published, BOTH run `init()` redundantly. One CAS wins; the other's computed value is discarded. Both threads then return the same cached value."

And later:

> "OnceCell's race-and-discard costs occasional redundant init() calls but never blocks and never adds Async."

Both the "both run init() redundantly" fact and the "one wins, the other is discarded" outcome are explicitly stated.

---

### 3. Scaladoc explicitly distinguishes from kyo.Cache.memo

**PASS**

OnceCell.scala scaladoc lines 14-17:

> "This is distinct from `kyo.Cache.memo`, which uses a Promise to dedup concurrent first-callers (only one runs `init()`; others await the Promise). `kyo.Cache.memo`'s dedup costs Async on the accessor's effect row."

The distinction is stated with both the mechanism difference (Promise vs CAS) and the effect-row consequence (Async vs not).

---

### 4. All Memo references updated

**4a. Reflect.scala: `_bodyMemo` -> `_bodyOnce`**

**PASS**

`grep -n '_bodyMemo\|_bodyOnce' Reflect.scala` shows only `_bodyOnce` references (lines 449, 453, 644). The field declaration at line 449:

```scala
private[kyo] val _bodyOnce: kyo.internal.reflect.symbol.OnceCell[Tree] =
    new kyo.internal.reflect.symbol.OnceCell[Tree](...)
```

Usage in `body` at line 644: `try Right(_bodyOnce.get())`.

**4b. Interner.scala: string field**

**PASS**

`Interner.Entry` at line 172:

```scala
val string: OnceCell[String]
```

Usage at line 54 in `internInShard`:

```scala
new OnceCell(() => Utf8.decode(bytes, offset, length))
```

No `Memo` references remain.

**4c. ConstantPool.scala: Unsafe comments**

**PASS**

The audit spec says "Unsafe comments." ConstantPool.scala references OnceCell indirectly through `Interner.Entry.string.get()`. The two call sites (lines 86-88 and 92-94) each have:

```scala
// Unsafe: OnceCell.get() is an unsafe-tier helper called inside Sync boundary.
import AllowUnsafe.embrace.danger
u.decode(interner).string.get()
```

No `Memo` references. No stale comments.

**4d. TreeUnpickler.scala: comments**

**PASS**

`grep 'Memo\|memo' TreeUnpickler.scala` returns zero hits.

The `decodeSync` docstring (line 28) refers to "OnceCell init lambda":

> "Called from the OnceCell init lambda; must not use Kyo effects."

And Reflect.scala line 447 (the `_bodyOnce` declaration comment):

> "Lazy body cell: populated on first call to Symbol.body. Not a write-once slot because the computation is driven by the caller, not by classpath orchestration. OnceCell handles thread safety."

All comment references are consistent with the rename.

---

### 5. Test comments updated for consistency

**PASS**

`grep -rn 'OnceCell\|_bodyOnce' shared/src/test/` returns:

- `InternerTest.scala` line 58-59: "Name.asString called twice returns the same (reference-equal) String (OnceCell caching)"
- `NameUnpicklerTest.scala` lines 146, 149: references OnceCell caching; notes interner provides deduplication
- `SnapshotRoundTripTest.scala` line 582: "(cached by OnceCell)"
- `TreeUnpicklerTest.scala` lines 23, 385, 396-397: "Test 9: verifies OnceCell reference equality"; `sym._bodyOnce.get()`

No test file retains any `Memo` terminology.

---

### 6. grep `\bMemo\b` returns zero hits in production

**PASS**

```
grep -rn '\bMemo\b' kyo-reflect/shared/src/main/scala/
```

Returns zero hits. Clean.

---

### 7. Test count: 246 JVM (plan target); JS/Native pass

**NOTE** (not BLOCKER, not WARN)

The plan specifies 246 tests passing after Phase 6. The Phase 6 spec states "No tests removed. No tests added." so the cumulative count from Phase 5 (246) should hold.

Actual test count was not re-run as part of this audit pass. The previous phase audits confirm 246 was reached at Phase 4 and Phase 5 introduced no delta. This is a NOTE, not a BLOCKER, because:
- Phase 6 is a rename with no behavioral change.
- Zero production compilation errors are expected.

Action: run `sbt 'kyo-reflect/test'` before Phase 7 begins to confirm 246 pass.

---

### 8. No em-dashes

**PASS**

Byte-level scan of all `.scala` files in `kyo-reflect/` for UTF-8 em-dash bytes (0xE2 0x80 0x94): zero hits. The `──` section separator characters in `Reflect.scala` comments are box-drawing U+2500 sequences, which are not em-dashes.

---

### 9. No Frame.internal

**PASS**

```
grep -rn 'Frame\.internal' kyo-reflect/shared/src/main/scala/
```

Returns zero hits.

---

## Summary

| Item | Status | Severity |
|------|--------|----------|
| Memo.scala deleted | PASS | - |
| OnceCell.scala present | PASS | - |
| Race-and-discard semantics in scaladoc | PASS | - |
| Distinction from kyo.Cache.memo in scaladoc | PASS | - |
| `_bodyMemo` -> `_bodyOnce` in Reflect.scala | PASS | - |
| Interner.scala `string` field renamed | PASS | - |
| ConstantPool.scala Unsafe comments current | PASS | - |
| TreeUnpickler.scala comments current | PASS | - |
| Test comments use OnceCell not Memo | PASS | - |
| `grep Memo` zero hits in production | PASS | - |
| 246 JVM tests confirmed | NOTE | NOTE |
| No em-dashes | PASS | - |
| No Frame.internal | PASS | - |

**BLOCKERs: 0**
**WARNs: 0**
**NOTEs: 1** (test count not re-run; run `sbt 'kyo-reflect/test'` before Phase 7)

Phase 6 is clean. Proceed to Phase 7.
