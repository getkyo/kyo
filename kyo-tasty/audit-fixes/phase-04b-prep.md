# Phase 04b Prep: Widen MappedByteView cursor to 64-bit

## Plan section (05-plan.md Phase 04b)

Files to modify:
- `kyo-tasty/jvm/src/main/scala/kyo/internal/tasty/binary/MappedByteView.scala` (lines 41-58)
- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/binary/ByteView.scala` (trait Long methods + Int wrappers)

New test file: `kyo-tasty/jvm/src/test/scala/kyo/MappedByteViewTest.scala` (2 tests).

Verification: `sbt 'kyo-tastyJVM/Test/compile' 'kyo-tastyJVM/testOnly kyo.MappedByteViewTest'`.

---

## MappedByteView API (JVM, current)

File: `kyo-tasty/jvm/src/main/scala/kyo/internal/tasty/binary/MappedByteView.scala`

```
line 35:  def peekByte(at: Int): Byte =
line 36:      checkOpen()
line 37:      buf.get(at)

line 46:  def readEnd(): Int =
line 47:      val len = Varint.readNat(this)
line 48:      cursor.toInt + len

line 53:  def goto(addr: Int): Unit =
line 54:      cursor = addr.toLong

line 56:  def remaining: Int = (end - cursor).toInt
line 58:  def position: Int = cursor.toInt
```

All four targeted signatures confirmed present with Int types. Fields `start`, `end`, `cursor` are already Long (lines 25-26, 30).

---

## Caller cascade

Caller files (shared, all consuming ByteView trait methods):
- `reader/NameUnpickler.scala` - heavy use: `view.position`, `view.readEnd()`, `view.goto(end)` (~30 call sites)
- `reader/AstUnpickler.scala` - heavy use: `view.position`, `view.readEnd()`, `view.peekByte(view.position)`, `view.goto(payloadEnd)` (~50 call sites); passes `view.position` and `view.readEnd()` directly into `Tasty.Symbol.TastyOrigin(bodyStart: Int, bodyEnd: Int, ...)` constructor
- `reader/TypeUnpickler.scala` - heavy use: `view.position`, `view.readEnd()`, `view.goto(end)` (~30 call sites)
- `reader/TreeUnpickler.scala` - heavy use: same pattern (~40 call sites)
- `reader/SectionIndex.scala` - uses `view.position`, `view.remaining`, `view.goto(offset + sectionLen)`
- `snapshot/SnapshotReader.scala` - uses `view.peekByte(0..5)` (literal Int args, header only), `view.peekByte(at)` and `view.peekByte(at + N)` where `at: Int` and `from: Int` are local Int params in private helpers

**Cascade concern: `TastyOrigin` constructor.**
`Tasty.Symbol.TastyOrigin` has `bodyStart: Int` and `bodyEnd: Int` (Tasty.scala lines 844-845). At least 8 call sites in AstUnpickler pass `view.position` and `view.readEnd()` directly as these args. Widening those methods to Long will produce a compile error at each site unless `TastyOrigin` is also widened or `.toInt` casts are inserted. The plan does NOT mention widening `TastyOrigin` -- this is a gap.

**SnapshotReader private helpers.**
`readInt32LEFromView(view: ByteView, at: Int)` and `copyViewRange(view: ByteView, from: Int, until: Int)` take Int params and pass them to `view.peekByte(at)`. After widening `peekByte` to `Long`, Int args auto-widen, so these compile cleanly with no changes needed. The literal calls `view.peekByte(0)` through `view.peekByte(5)` also auto-widen.

**Plan provides Int wrapper methods on ByteView.** Methods `positionInt`, `readEndInt`, etc. exist in the plan's AFTER snapshot. If callers that feed into `TastyOrigin(Int, Int, ...)` switch to `view.positionInt` / `view.readEndInt`, the cascade is contained without widening `TastyOrigin`. The impl agent must update those ~8 AstUnpickler call sites to use the Int wrappers.

---

## Concerns

1. **Test file is new, not extended.** The plan calls for a new `MappedByteViewTest.scala` in `kyo-tasty/jvm/src/test/scala/kyo/`. No existing file to extend; creation is correct.

2. **TastyOrigin bodyStart/bodyEnd are Int.** This is the main cascade risk. The plan's Int wrapper methods (`positionInt`, `readEndInt`) are the bridge; the impl agent must use them at the 8+ AstUnpickler sites that construct `TastyOrigin`. Without this, `kyo-tastyJVM/Test/compile` will fail.

3. **Scope is correctly JVM-only.** The JVM `MappedByteView` lives in `kyo-tasty/jvm/`. The Native `MappedByteView` at `kyo-tasty/native/src/main/scala/kyo/internal/tasty/binary/MappedByteView.scala` has the same Int signatures but is outside this phase's scope. The `ByteView.scala` trait change is shared and will affect the Native impl -- the Native `MappedByteView` will fail to compile unless it also overrides the widened abstract methods. The plan does not mention updating the Native impl; this is a second gap to flag.

4. **ByteView.Heap (inline class in ByteView.scala).** The shared `ByteView.Heap` inline class also implements the same methods with Int types (lines 70, 90, 94, 103, 106, 108). Widening the trait will require updating `ByteView.Heap` too. The plan acknowledges `ByteView.scala` is modified but the AFTER diff only shows trait-level changes. The impl agent must ensure `ByteView.Heap` overrides are widened or the wrapper pattern is applied consistently.

---

## Self-check verdict

READY WITH CAVEATS. The plan's core approach is sound. Three cascade gaps require impl-agent attention not explicitly called out in the plan:
1. AstUnpickler `TastyOrigin(view.position, ...)` sites must use `positionInt`/`readEndInt` wrappers.
2. `ByteView.Heap` inside ByteView.scala must be updated alongside the trait.
3. Native `MappedByteView` will break from the trait change; either the phase touches it (out of stated JVM-only scope) or the shared ByteView trait approach needs to be narrowed. The safest path is to widen only the JVM `MappedByteView` overrides to Long while keeping the ByteView trait Int-typed, and adding the Long methods as separate overloads or new names -- but that contradicts the plan. Flag for impl agent to confirm which approach avoids breaking Native compile.
