# kyo-offheap

`Memory[A]` is a typed handle to an off-heap segment whose lifetime is governed by an `Arena`. You enter an `Arena.run` block, call `Memory.init[A](size)` to allocate, and read or write with `get`, `set`, `fill`, `fold`, `view`, or `copy`. Every operation is tracked in the effect row as `Arena`, so the type system enforces that allocation, access, and release all stay inside the same scope. When `Arena.run` exits, normally or on error, every segment allocated inside it is freed.

Primitive element types (`Byte`, `Short`, `Int`, `Long`, `Float`, `Double`) are supported through a `Layout[A]` type class that pins down per-element byte size and the foreign-memory accessors. The API is inlined for performance and exposes a parallel `Memory.Unsafe[A]` surface (no `Arena` effect, requires `AllowUnsafe`) for hot loops. Allocation deliberately has no unsafe variant because arena tracking is required for the cleanup guarantee. The same source compiles on JVM (Java's `java.lang.foreign`) and Scala Native (a `malloc`/`free`-backed shim).

The pattern is always the same: enter `Arena.run`, allocate with `Memory.init`, read and write by element index, exit. The segment is freed when the block exits:

```scala
import kyo.*

val total: Int < Sync =
    Arena.run {
        for
            mem <- Memory.init[Int](4)
            _   <- mem.set(0, 10)
            _   <- mem.set(1, 20)
            _   <- mem.set(2, 30)
            _   <- mem.set(3, 40)
            s   <- mem.fold(0)(_ + _)
        yield s
    }
// 100
```

Outside `Arena.run`, the result is a plain `Int < Sync`. The segment was freed when the block exited.

## Allocating in an arena

Every off-heap operation lives inside `Arena.run`. The arena is the lifetime; the `Arena` effect in the row is the type-level witness that you are inside one.

### `Arena.run`: opens and closes the scope

`Arena.run` opens a fresh shared `java.lang.foreign.Arena`, runs the body, and frees every segment allocated against it when the body completes. The discharge removes `Arena` from the effect row:

```scala
import kyo.*

// Inside: requires Arena. Outside: just Sync.
val first: Int < Sync =
    Arena.run {
        Memory.init[Int](4).map { mem =>
            mem.set(0, 99).andThen(mem.get(0))
        }
    }
```

> **Caution:** A `Memory[A]` value can syntactically escape its `Arena.run` (a `val` capture, or returning the segment from the block). Using it after the arena closes is a runtime panic, not a type error. Treat `Memory[A]` as scope-local; do not hand it to callers that outlive the surrounding `Arena.run`.

> **Note:** `Arena.run` uses a shared arena, so segments allocated inside it can be touched from any fiber or thread within the scope. The close itself runs on the fiber exiting the scope.

### `Memory.init`: allocates a typed segment

`init[A](size)` allocates `size` elements of type `A`. The result is `Memory[A] < Arena`, lazy until the surrounding effect is run:

```scala
import kyo.*

val sized: Long < Sync =
    Arena.run {
        Memory.init[Int](128).map(_.size)
    }
```

The element count, not the byte count, is `128`. See `size` under "Reading and writing elements" for the distinction.

### `Memory.initWith`: allocates and consumes in one expression

When the segment does not need to outlive a single expression, `initWith` saves a `for`-binding:

```scala
import kyo.*

val firstSlot: Int < Sync =
    Arena.run {
        Memory.initWith[Int](16) { mem =>
            mem.set(0, 7).andThen(mem.get(0))
        }
    }
```

Both forms route through the same allocation path; pick the one that reads better at the call site.

## Reading and writing elements

Once you have a `Memory[A]`, this is the working surface. All access is by element index. Bulk operations (`fill`, `fold`, `findIndex`, `exists`) walk the full segment.

### `get` and `set`: per-element typed access

Indices are element offsets, not byte offsets. The element type `A` flows through the result:

```scala
import kyo.*

val pair: (Int, Int) < Sync =
    Arena.run {
        for
            mem <- Memory.init[Int](4)
            _   <- mem.set(0, 10)
            _   <- mem.set(3, 40)
            a   <- mem.get(0)
            d   <- mem.get(3)
        yield (a, d)
    }
```

A freshly initialized segment reads as zero in every slot. Setting an out-of-range index raises an `IndexOutOfBoundsException` from the underlying `MemorySegment`.

### `fill`: bulk write

`fill(value)` writes `value` into every slot of the segment:

```scala
import kyo.*

val filled: Int < Sync =
    Arena.run {
        for
            mem <- Memory.init[Int](100)
            _   <- mem.fill(42)
            v   <- mem.get(99)
        yield v
    }
```

### `fold`: aggregation

`fold(zero)(f)` performs a left-fold over every element:

```scala
import kyo.*

val sum: Int < Sync =
    Arena.run {
        for
            mem <- Memory.init[Int](3)
            _   <- mem.set(0, 1)
            _   <- mem.set(1, 2)
            _   <- mem.set(2, 3)
            s   <- mem.fold(0)(_ + _)
        yield s
    }
```

### `findIndex` and `exists`: predicate search

`findIndex` returns a `Maybe[Int]`; `exists` is the boolean shorthand:

```scala
import kyo.*

val located: (Maybe[Int], Boolean) < Sync =
    Arena.run {
        for
            mem    <- Memory.init[Int](3)
            _      <- mem.set(0, 1)
            _      <- mem.set(1, 2)
            _      <- mem.set(2, 3)
            idx    <- mem.findIndex(_ == 2)
            missing <- mem.findIndex(_ == 99)
            any     <- mem.exists(_ >= 3)
        yield (idx, any)
    }
// idx     = Present(1)
// missing = Absent
// any     = true
```

### `size`: element count

`size` returns the number of elements, not bytes:

```scala
import kyo.*

val count: Long < Sync =
    Arena.run {
        Memory.initWith[Int](32)(mem => mem.size)
    }
// 32, not 128
```

> **Note:** `size` is `byteSize / Layout[A].size`. If you expect it to mirror `MemorySegment.byteSize`, multiply by the per-element width of `A`.

## Slicing and copying

Three operations move data between segments or carve out sub-regions. They differ in whether they allocate.

### `view`: zero-copy sub-segment

`view(from, len)` returns a `Memory[A]` over the same underlying allocation, starting `from` elements in and `len` elements long. Writes through the view are visible in the parent and vice versa:

```scala
import kyo.*

val shared: (Int, Int) < Sync =
    Arena.run {
        for
            mem    <- Memory.init[Int](8)
            _      <- mem.fill(1)
            window <- mem.view(2, 4)
            _      <- window.set(0, 99)
            // Visible through both handles
            viaParent <- mem.get(2)
            viaView   <- window.get(0)
        yield (viaParent, viaView)
    }
// (99, 99)
```

`view` does not allocate; the sub-segment's lifetime is the same as the parent's, which is the enclosing arena.

### `copy`: new allocation in the current arena

`copy(from, len)` allocates a fresh segment in the current arena and copies `len` elements into it. Writes to the copy do not touch the source:

```scala
import kyo.*

val divergent: (Int, Int) < Sync =
    Arena.run {
        for
            src    <- Memory.init[Int](4)
            _      <- src.fill(1)
            dup    <- src.copy(0, 4)
            _      <- dup.set(0, 99)
            srcVal <- src.get(0)
            dupVal <- dup.get(0)
        yield (srcVal, dupVal)
    }
// (1, 99)
```

> **Note:** The copy belongs to the enclosing `Arena.run`, not to the source's logical sub-region. The copy is freed when the surrounding arena closes, regardless of when the source is logically done with.

`view` shares storage and never allocates. `copy` allocates a new segment. Use `view` when you want a window over existing data; use `copy` when you need an independent buffer to mutate.

### `copyTo`: copy into a pre-existing target

`copyTo(target, srcPos, targetPos, len)` writes `len` elements from `self` (starting at `srcPos`) into `target` (starting at `targetPos`). The target must already exist:

```scala
import kyo.*

val staged: Byte < Sync =
    Arena.run {
        for
            source  <- Memory.init[Byte](4)
            _       <- source.set(0, 0xCA.toByte)
            _       <- source.set(1, 0xFE.toByte)
            decoded <- Memory.init[Byte](16)
            _       <- source.copyTo(decoded, 0, 8, 2)
            head    <- decoded.get(8)
        yield head
    }
// 0xCA
```

This is the operation you want when staging bytes through a pre-sized output buffer (decoders, framing layers, FFI bridges).

## Supported element types

`Memory[A]` is parametric in `A`, but only types with a `Layout[A]` instance can be allocated. The companion ships instances for the six JVM primitives.

### `Layout[A]`: the type-class contract

`Layout[A]` is sealed and abstract. It specifies the per-element byte size and the unsafe accessors that read and write a value at a byte offset:

```scala
import kyo.*

// Allocation is constrained by `Layout`, picked up implicitly:
val ints   = Arena.run(Memory.init[Int](4).map(_.size))    // OK
val longs  = Arena.run(Memory.init[Long](4).map(_.size))   // OK
val floats = Arena.run(Memory.init[Float](4).map(_.size))  // OK
```

### Shipped instances

A `given Layout[A]` ships for each of:

| Type    | Per-element bytes |
| ------- | ----------------- |
| `Byte`  | 1                 |
| `Short` | 2                 |
| `Int`   | 4                 |
| `Long`  | 8                 |
| `Float` | 4                 |
| `Double` | 8                |

Working with `Float` or `Double` is exactly the same shape as with `Int`:

```scala
import kyo.*

val sum: Double < Sync =
    Arena.run {
        Memory.initWith[Double](3) { workspace =>
            for
                _ <- workspace.set(0, 1.5)
                _ <- workspace.set(1, 2.25)
                _ <- workspace.set(2, 0.125)
                s <- workspace.fold(0.0)(_ + _)
            yield s
        }
    }
// 3.875
```

> **Note:** No `Layout` derivation ships for composite types. Structs and case classes are not supported out of the box; if you need them, define your own `Layout[A]` and read/write each field through the primitive instances at the field's byte offset.

## Dropping to unsafe

When the per-element effect overhead matters (tight loops, parsers, FFI marshaling), call `.unsafe` to surface a `Memory.Unsafe[A]`. The unsafe twin offers the same shape as the safe API minus allocation, returns raw values instead of `_ < Arena`, and requires an `AllowUnsafe` capability.

### `unsafe` and `safe`: round-trip conversion

`memory.unsafe` returns a `Memory.Unsafe[A]` over the same `MemorySegment`. `unsafe.safe` lifts back to `Memory[A]`. Both are zero-cost view conversions:

```scala
import kyo.*
import kyo.Memory.Unsafe

val total: Int < Sync =
    Arena.run {
        for
            mem <- Memory.init[Int](3)
            sum <- Sync.Unsafe.defer {
                val raw: Unsafe[Int] = mem.unsafe
                raw.set(0, 10)
                raw.set(1, 20)
                raw.set(2, 30)
                raw.fold(0)(_ + _)
            }
        yield sum
    }
// 60
```

The inner block runs without per-call `Arena` lifts: `get`, `set`, `fill`, `fold`, `findIndex`, `exists`, `view`, `copyTo`, and `size` all return their raw result type directly.

> **Caution:** `.unsafe` does not change the underlying segment's lifetime. The segment is still owned by the enclosing arena and is freed when that arena closes. Escaping `Memory.Unsafe[A]` past `Arena.run` is the same hazard as escaping `Memory[A]`.

### What's available on `Memory.Unsafe[A]`

`get`, `set`, `fill`, `fold`, `findIndex`, `exists`, `view`, `copyTo`, and `size` exist on the unsafe API with no `Arena` effect. They require `(using AllowUnsafe)`, which `Sync.Unsafe.defer` (or any block already inside an unsafe scope) supplies:

```scala
import kyo.*

val firstHigh: Maybe[Int] < Sync =
    Arena.run {
        for
            mem <- Memory.init[Int](6)
            _   <- mem.set(0, 1)
            _   <- mem.set(1, 5)
            _   <- mem.set(2, 12)
            _   <- mem.set(3, 7)
            _   <- mem.set(4, 18)
            _   <- mem.set(5, 3)
            idx <- Sync.Unsafe.defer {
                mem.unsafe.findIndex(_ > 10)
            }
        yield idx
    }
// Present(2)
```

### `Unsafe.copy` keeps the `Arena` effect

`copy` allocates, so its unsafe twin still carries `Arena` in the result type:

```scala
import kyo.*

val cloned: Int < Sync =
    Arena.run {
        for
            mem  <- Memory.init[Int](4)
            _    <- mem.set(0, 77)
            // Unsafe.copy still returns _ < Arena because it allocates.
            dup  <- mem.unsafe.copy(0, 4)
            head <- mem.set(0, 0).andThen(dup.safe.get(0))
        yield head
    }
// 77
```

`init` and `initWith` are deliberately absent from `Memory.Unsafe`. Every allocation must go through the safe API so the arena can track it for cleanup; `copy` is the one allocating unsafe method, and it keeps the `Arena` row to preserve that invariant.

## A two-buffer pipeline

The pieces compose. Here is a source/destination pipeline that fills a buffer of bytes, walks it under the unsafe API to find a sentinel, copies the prefix up to that sentinel into an output buffer, and reports what was staged:

```scala
import kyo.*

val staged: (Int, Byte) < Sync =
    Arena.run {
        for
            source <- Memory.init[Byte](16)
            _      <- source.fill(0x20.toByte)
            _      <- source.set(5, 0x0A.toByte)
            // Unsafe inner loop: locate the newline.
            cut <- Sync.Unsafe.defer {
                source.unsafe.findIndex(_ == 0x0A.toByte)
            }
            target <- Memory.init[Byte](32)
            len     = cut.getOrElse(0)
            _      <- source.copyTo(target, 0, 0, len)
            head   <- target.get(0)
        yield (len, head)
    }
// (5, 0x20)
```

Both buffers and the unsafe view all live and die with the one `Arena.run` they were allocated in.

## Putting it together

The operations compose. One arena, one buffer of `Int` sensor readings, a few point writes, a fold over all slots, a threshold search, and a zero-copy view over the tail:

```scala
import kyo.*

val program: Int < Sync =
    Arena.run {
        for
            readings <- Memory.init[Int](8)
            // Bulk write: every slot becomes 0
            _        <- readings.fill(0)
            // Typed point writes at element indices
            _        <- readings.set(0, 12)
            _        <- readings.set(1, 17)
            _        <- readings.set(2, 23)
            // Aggregate across every slot
            sum      <- readings.fold(0)(_ + _)
            // First index whose value crosses a threshold
            crossed  <- readings.findIndex(_ >= 20)
            // Zero-copy sub-segment over the last 3 slots
            tail     <- readings.view(5, 3)
            tailLen   = tail.size
        yield sum + crossed.getOrElse(-1) + tailLen.toInt
    }
```

Every value (`readings`, `tail`) is allocated against the same arena and freed when the block exits.

## Cross-platform behavior

`kyo-offheap` compiles for JVM and Scala Native from the same source under `shared/`. There is no JS target; off-heap memory is not part of the JS runtime model.

- **JVM** uses `java.lang.foreign` directly. `Memory[A]` is an opaque alias for `java.lang.foreign.MemorySegment`; `Arena.run` opens a shared `java.lang.foreign.Arena`. Requires a JVM that exposes the foreign-function and memory API.
- **Scala Native** uses a minimal `Arena`/`MemorySegment` shim under `kyo-offheap/native/` that wraps `malloc`/`free`. `Arena.close` calls `free` on each tracked segment; allocating against an already-closed arena throws `IllegalStateException`.

> **Caution:** On Scala Native, reads and writes against a segment whose arena has already closed are undefined behavior (segfault or silently corrupt data), not a managed panic. The cleanup invariant of `Arena.run` exists precisely to prevent this; do not let `Memory[A]` escape the scope.

The public API (`Memory`, `Memory.init`, `Memory.initWith`, the extension methods, `Memory.Unsafe`, and the `Layout` instances) is identical on both platforms.
