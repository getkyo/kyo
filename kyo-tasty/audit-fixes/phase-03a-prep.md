# Phase 03a Prep: Bound binary input primitives

Generated: 2026-05-29

## Objective

Every binary-input primitive rejects out-of-bounds reads with a structured
`TastyError.MalformedSection` rather than an uncaught AIOOBE. Produces INV-010.

Findings addressed: B1, B4, B7, C4.

---

## Unsafe Sites

### B4 — Varint.scala:42-51 (`readLongNat` shift past sign bit)

File: `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/binary/Varint.scala`

```scala
// lines 42-51 (BEFORE)
def readLongNat(view: ByteView): Long =
    var b = 0L
    var x = 0L
    while
        b = view.readByte() & 0xffL
        x = (x << 7) | (b & 0x7fL)
        (b & 0x80L) == 0L
    do ()
    end while
    x
```

No cap on continuation-byte count. With all-continuation bytes the loop
reads forever (or past the underlying array), and `x` shifts garbage into
the sign bit without ever throwing a structured error.

Symmetric site: `readNat` (lines 25-35) with identical structure, cap 5.

### B7 — ByteView.scala:94-95 (`subView` cursor+len overflow)

File: `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/binary/ByteView.scala`

```scala
// lines 94-95 (BEFORE)
override def subView(from: Int, until: Int): ByteView.Heap =
    new Heap(bytes, from, until)
```

No range validation. Negative `from`, `until > bytes.length`, or
`until < from` silently produce a mis-sized view that throws AIOOBE later.

### B1 — NameUnpickler.scala:74-80 (QUALIFIED, representative)

File: `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/reader/NameUnpickler.scala`

```scala
// lines 74-80 (BEFORE)
case TastyFormat.NameTags.QUALIFIED =>
    val end      = view.readEnd()
    val prefix   = view.readNat()
    val selector = view.readNat()
    view.goto(end)
    val s = buf(prefix).asString + "." + buf(selector).asString
    buf += internString(interner, s)
```

`buf(prefix)` and `buf(selector)` are unchecked. If either index is
out of range the throw is a raw `IndexOutOfBoundsException`, not
`TastyError.MalformedSection`. Same pattern recurs at EXPANDED,
EXPANDPREFIX, UNIQUE (separator + optional underlying), DEFAULTGETTER,
SUPERACCESSOR, INLINEACCESSOR, OBJECTCLASS, BODYRETAINER, SIGNED,
TARGETSIGNED (lines ~81-179).

### C4 — SectionIndex.scala:56 (`names(nameRef)` no bounds check)

File: `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/reader/SectionIndex.scala`

```scala
// lines 48-60 (BEFORE)
private def readSync(view: ByteView, names: Array[Tasty.Name]): SectionIndex =
    import AllowUnsafe.embrace.danger
    val builder = Map.newBuilder[String, (Int, Int)]
    while view.remaining > 0 do
        val nameRef    = view.readNat()
        val sectionLen = view.readNat()
        val offset     = view.position
        val name       = names(nameRef).asString   // line 56 — no bounds check
        builder += (name -> (offset, sectionLen))
        view.goto(offset + sectionLen)
    end while
    new SectionIndex(builder.result())
```

`names(nameRef)` throws raw AIOOBE when `nameRef >= names.length`.
`sectionLen` is also unchecked for negative values.

---

## TastyError Surface

`TastyError.MalformedSection` is confirmed present in
`kyo-tasty/shared/src/main/scala/kyo/TastyError.scala`:

```scala
case MalformedSection(name: String, reason: String)
```

Two-field: section name + free-form reason string. No `byteOffset` field.

### Scope concern: byteOffset

The plan's BEFORE/AFTER blocks introduce
`class MalformedVarintException(val byteOffset: Long, msg: String)` as an
intermediate exception that is caught by the surrounding decode catch and
re-wrapped as `TastyError.MalformedSection(name, reason)`. The `byteOffset`
field lives only on the internal exception type, not on the public ADT.
Adding `byteOffset` to `TastyError.MalformedSection` itself is Phase 14a
scope. Phase 03a must NOT touch the public ADT.

---

## Error Propagation Model

The plan uses throw-on-detect at the primitive level (internal
`MalformedVarintException` and `ArrayIndexOutOfBoundsException` with
descriptive messages), relying on the enclosing decode pass to catch and
re-wrap as `Abort.fail(TastyError.MalformedSection(sectionName, ex.getMessage))`.

Tests for NameUnpickler and SectionIndex call the reader via `Abort.run`
and assert `Result.Failure(TastyError.MalformedSection(...))`. Tests for
Varint and ByteView assert that the raw internal exception fires (these
primitives sit below the `Abort` boundary).

---

## Test Files

| File | Status |
|------|--------|
| `kyo-tasty/shared/src/test/scala/kyo/ByteViewTest.scala` | exists |
| `kyo-tasty/shared/src/test/scala/kyo/NameUnpicklerTest.scala` | exists |
| `kyo-tasty/shared/src/test/scala/kyo/VarintTest.scala` | does NOT exist — create |
| `kyo-tasty/shared/src/test/scala/kyo/SectionIndexTest.scala` | does NOT exist — create |

### 8 tests to add (plan §03a)

1. `VarintTest`: `readNat` rejects more than 5-byte continuation (B4)
2. `VarintTest`: `readLongNat` rejects more than 10-byte continuation (B4)
3. `VarintTest`: `readLongNat` accepts exact 10-byte continuation (B4 boundary)
4. `ByteViewTest`: `subView` rejects negative `from` (B7)
5. `ByteViewTest`: `subView` rejects `until > length` (B7)
6. `NameUnpicklerTest`: QUALIFIED with out-of-range prefix yields `MalformedSection` (B1)
7. `SectionIndexTest`: nameRef out-of-range yields `MalformedSection` (C4)
8. `SectionIndexTest`: negative sectionLen yields `MalformedSection` (C4)

---

## Implementation Checklist

- [ ] `Varint.scala`: add `class MalformedVarintException` companion; add byte-count cap (5 for `readNat`, 10 for `readLongNat`); throw on overflow
- [ ] `ByteView.scala`: add range guard in `subView` before constructing `Heap`
- [ ] `NameUnpickler.scala`: add `buf` bounds check before every indexed read (all tagged name cases); throw descriptive AIOOBE
- [ ] `SectionIndex.scala`: add `nameRef < 0 || nameRef >= names.length` and `sectionLen < 0` guards; throw descriptive AIOOBE
- [ ] Create `VarintTest.scala` (tests 1-3 above)
- [ ] Add tests 4-5 to existing `ByteViewTest.scala`
- [ ] Add test 6 to existing `NameUnpicklerTest.scala`
- [ ] Create `SectionIndexTest.scala` (tests 7-8)
- [ ] Convention sweep: no em-dashes, no AllowUnsafe additions, no semicolons, no asInstanceOf

---

## Concerns

1. `ByteView.subView` AFTER block in plan throws `ArrayIndexOutOfBoundsException`
   with a descriptive message, not `MalformedVarintException`. The surrounding
   catch must handle both exception types and convert to `MalformedSection`.
   Confirm the catch site(s) cover `ArrayIndexOutOfBoundsException` before coding.

2. The plan's AFTER block for `SectionIndex.readSync` adds `(using AllowUnsafe)`
   to the signature, but the existing code already imports
   `AllowUnsafe.embrace.danger` without that parameter. Check whether the
   `using` parameter is needed or whether the existing import is sufficient.

3. `NameUnpickler.scala` has 11+ tagged cases each needing a guard. The plan
   says "same shape at all other indexed reads". Enumerate every `buf(...)` call
   across the full match before coding to avoid missing one.

---

## Verification Command

```
sbt 'kyo-tastyJVM/Test/compile' \
    'kyo-tastyJVM/testOnly kyo.VarintTest kyo.ByteViewTest kyo.NameUnpicklerTest kyo.SectionIndexTest'
```

JS and Native equivalents follow the same pattern.

---

## Self-check Verdict

PASS. All four unsafe sites captured from live source. `TastyError.MalformedSection`
confirmed as a two-field ADT with no `byteOffset`. Scope boundary (no ADT change)
confirmed. Two test files exist, two need creation. Three implementation concerns
noted that need verification before coding begins.
