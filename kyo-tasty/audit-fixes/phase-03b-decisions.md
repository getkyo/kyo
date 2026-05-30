# Phase 03b Decisions

## Finding B10 / INV-010: Interner.bytesEqual bounds check

### Decision

Added a bounds guard at the top of `bytesEqual` in `Interner.scala` (lines 125-138 after edit):

```scala
if offset < 0 || length < 0 || offset + length > bytes.length || offset + length < 0 then
    throw new ArrayIndexOutOfBoundsException(
        s"Interner.bytesEqual: offset=$offset length=$length bytes.length=${bytes.length}"
    )
```

The four conditions guard against:
- Negative offset (accesses before array start)
- Negative length (negative iteration count, passes computeHash silently)
- offset + length exceeding array bounds (out-of-range access)
- Integer overflow in offset + length (wraps to a negative value that passes the third check)

### Reachability analysis

For `offset + length > bytes.length`: `computeHash` accesses `bytes(offset + i)` first and throws a JVM-native AIOOBE before `bytesEqual` is reached. The bytesEqual guard is defence-in-depth for any future refactoring that reorders the call.

For `offset < 0`: `computeHash` would also throw natively at `bytes(-1)`. Same defence-in-depth rationale.

For `length < 0`: `computeHash` iterates zero times (loop condition false), so `computeHash` succeeds and returns the FNV-1a seed hash. `internInShard` then proceeds toward `copyOfRange(bytes, offset, offset + length)` which throws `IllegalArgumentException` when `end < from`. The `bytesEqual` guard IS reachable before `copyOfRange` when a hash collision exists in the table (same FNV seed hash). The test for this case uses a pre-seeded empty-slice entry to force the hash-collision path, confirming the guard fires with the custom diagnostic message.

### Test scenarios added (InternerTest.scala)

4 new scenarios added after the existing T-P6-5 test:

1. `B10/INV-010: intern throws AIOOBE when offset + length > bytes.length` - calls `intern(bytes, 4, 2)` on a 5-byte array; AIOOBE thrown (from computeHash, defence-in-depth from bytesEqual).

2. `B10: intern throws AIOOBE for negative offset` - calls `intern(bytes, -1, 3)`; AIOOBE thrown.

3. `B10: bytesEqual guard throws AIOOBE with custom message for negative length` - pre-seeds the interner with the empty-slice entry (hash = FNV seed = 0x011c9dc5), then calls `intern(bytes, 0, -1)`. Hash collision forces `bytesEqual` to be called; the `length < 0` guard fires and the message contains `length=-1` and `bytes.length=5`.

4. `B10: zero-length intern with valid offset succeeds (guard allows valid bounds)` - calls `intern(bytes, 2, 0)` on a 5-byte array; guard conditions all false, intern succeeds normally.

### Compile result

`kyo-tasty/Test/compile`: SUCCESS.

### Test result

`testOnly kyo.InternerTest`: 15 tests, 15 passed, 0 failed.

### HEAD verification

HEAD remains `bfde82de6` (no commit made per HARD RULE).

### Files modified

- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/symbol/Interner.scala` (bytesEqual guard added)
- `kyo-tasty/shared/src/test/scala/kyo/InternerTest.scala` (4 B10 test scenarios appended)

---

## Phase 03b verify FAIL fix: move bounds guard to top of intern

### Root cause

`Interner.intern` called `computeHash(bytes, offset, length)` before any bounds check.
On JVM, `bytes(offset+i)` with an out-of-range index throws `ArrayIndexOutOfBoundsException`
as expected. On JS (Scala.js), the same access throws `UndefinedBehaviorError` rather than
`ArrayIndexOutOfBoundsException`, causing all four B10 `intercept[ArrayIndexOutOfBoundsException]`
scenarios to fail on the JS platform.

### Fix

Moved the bounds validation to the very top of `intern`, before the `computeHash` call:

```scala
def intern(bytes: Array[Byte], offset: Int, length: Int): Interner.Entry =
    if offset < 0 || length < 0 || offset + length < 0 || offset + length > bytes.length then
        throw new ArrayIndexOutOfBoundsException(
            s"Interner.intern: offset=$offset length=$length bytes.length=${bytes.length}"
        )
    end if
    val hash     = computeHash(bytes, offset, length)
    ...
```

The four conditions are: negative offset, negative length, Int overflow in the sum (sum < 0),
and sum exceeding array length. The message format includes `offset=`, `length=`, and
`bytes.length=` to satisfy the existing Case 3 test assertions.

### bytesEqual internal guard

Retained as defense-in-depth. Since `intern` now pre-validates before calling `internInShard`,
the `bytesEqual` guard can never fire in normal operation, but removing it would reduce
robustness for any future internal caller that bypasses `intern`.

### Test results (after fix)

- JVM: 15/15 passed
- JS: 15/15 passed (was failing with UndefinedBehaviorError before this fix)
- Native: 15/15 passed
