# Design C — Review & Corrections

Thorough API verification against Kyo's actual codebase. Issues found, simplifications identified.

---

## 1. API Mismatches

### 1a. `Promise.init` type params

**Design says**: `Promise.init[Span[Byte], Any]`, `Promise.init[Unit, Any]`

**Actual**: `Promise.init[E, A]` where E = value type, A = effect type. The naming in `init` is misleading (`E` is not error), but `Promise.init[Span[Byte], Any]` actually maps to `Promise[Span[Byte], Any]` — value=Span[Byte], effects=Any.

**Verdict**: Correct by accident. But confusing. Recommend using explicit names:
```scala
Promise.init[Unit, Any]       // readiness signal
```

---

### 1b. `stream.runFirst` does not exist

**Design says** (StreamReader.nextSpan):
```scala
src.runFirst.map {
    case Present(span) => span
    case Absent        => Abort.fail(...)
}
```

**Actual**: Stream has no `runFirst` method. The available options are:
- `stream.splitAt(1)` → `(Chunk[V], Stream[V, S]) < S` — pulls first element, returns rest as new Stream
- `Emit.runFirst` — low-level, returns `(Maybe[V], () => continuation)`

**Fix**: Use `splitAt(1)` and update the `src` reference:
```scala
private def nextSpan(using Frame): Span[Byte] < (Async & Abort[HttpException]) =
    if leftover.nonEmpty then
        Sync.defer { val s = leftover; leftover = Span.empty; s }
    else
        src.splitAt(1).map { (chunk, rest) =>
            src = rest
            if chunk.isEmpty then Abort.fail(HttpConnectionClosedException())
            else chunk(0)
        }
```

This works correctly: each `splitAt(1)` pulls one Span from the raw read stream and returns the continuation. For typical HTTP headers (~500 bytes, one 8KB OS read), `splitAt` is called once. Overhead is negligible.

---

### 1c. `Span.concat` signature mismatch

**Design says**: `Span.concat(chunks)` where `chunks: Chunk[Span[Byte]]`

**Actual**: `Span.concat[A: ClassTag](spans: Span[A]*): Span[A]` — takes varargs, not Chunk.

**Fix**: Either:
- `Span.concat(chunks.toSeq*)` — spread Chunk as varargs
- Add a helper (the design proposes one, which is fine as new code)

The proposed `Span.concat(spans: Chunk[Span[Byte]])` helper is a valid addition — it pre-sizes and copies. Keep the proposal but note it needs the Chunk-accepting overload added to kyo-data.

---

### 1d. `Stream.unfold` with mutable accumulator

**Design says** (readChunkedStream):
```scala
Stream.unfold(reader) { r =>
    r.readLine().run.map { ... Maybe((data, r)) }
}
```

**Issue**: `Stream.unfold` is designed for immutable state. Passing a mutable `StreamReader` as accumulator, returning the same instance each time, is semantically wrong — the state is in the mutation, not the accumulator.

**Fix**: Use `Stream { Loop.foreach { ... } }` directly — cleaner, no fake accumulator:
```scala
private def readChunkedStream(reader: StreamReader)(using Frame)
    : Stream[Span[Byte], Async & Abort[HttpException]] =
    Stream {
        Loop.foreach {
            reader.readLine().run.map { lineChunks =>
                val line = new String(Span.concat(lineChunks.toSeq*).toArrayUnsafe, Utf8)
                parseChunkHeader(line) match
                    case Result.Success(0) =>
                        reader.readLine().run.andThen(Loop.done(()))  // consume trailing CRLF
                    case Result.Success(size) =>
                        reader.readExact(size).run.map { dataChunks =>
                            reader.readLine().run.andThen {  // consume trailing CRLF
                                Emit.valueWith(Chunk(Span.concat(dataChunks.toSeq*)))(Loop.continue)
                            }
                        }
                    case _ => Loop.done(())  // malformed
            }
        }
    }
```

---

## 2. AllowUnsafe Elimination

### 2a. Poll loop can use safe Promise APIs

**Design says**: `import AllowUnsafe.embrace.danger` in poll loop for `p.completeDiscard(...)`.

**Key finding**: Promise has safe completion methods:
```scala
// For Promise[Unit, Any]:
def completeUnitDiscard(using Frame): Unit < Sync

// For Promise[A, S] (no Abort in S):
def completeDiscard(v: Result[Nothing, A < S])(using Frame): Unit < Sync
```

Since the poll loop runs as a Kyo fiber (`Fiber.init(loop.pollLoop())`), it has Frame context. **No AllowUnsafe needed.**

**Fix**: Store safe `Promise[Unit, Any]` in the ConcurrentHashMap, complete with `p.completeUnitDiscard`:

```scala
private val pendingReads  = new ConcurrentHashMap[Int, Promise[Unit, Any]]()
private val pendingWrites = new ConcurrentHashMap[Int, Promise[Unit, Any]]()
private val pendingAccept = new ConcurrentHashMap[Int, Promise[Unit, Any]]()
```

### 2b. Separate readiness from I/O

The poll loop should only signal readiness — actual reads happen in the requesting fiber:

```scala
def awaitRead(fd: Int, bufSize: Int)(using Frame): Span[Byte] < Async =
    Promise.init[Unit, Any].map { p =>
        Sync.defer(pendingReads.put(fd, p))
        Sync.defer(kqueueRegister(efd, fd, EVFILT_READ))
        p.get.andThen {
            // Back in requesting fiber — do the actual read here
            Sync.defer(doRead(fd, bufSize))
        }
    }
```

Benefits:
- Poll loop is simpler (just completes Unit promises)
- No data passing through ConcurrentHashMap (just readiness signals)
- I/O happens in the fiber that needs it
- `pendingReads` stores `Promise[Unit, Any]` instead of `(Promise.Unsafe[Span[Byte], Any], Int)`

### 2c. Poll loop event dispatch without AllowUnsafe

Since `completeUnitDiscard` returns `Unit < Sync` (effectful), can't use a `while` loop. Use `Kyo.foreach`:

```scala
def pollLoop()(using Frame): Unit < Async =
    Loop.foreach {
        Sync.defer {
            Zone {
                val outFds    = alloc[CInt](64)
                val outFilter = alloc[CInt](64)
                val n         = kqueueWait(efd, outFds, outFilter, 64) // @blocking
                val events    = new Array[(Int, Int)](math.max(0, n))
                var i = 0
                while i < n do
                    events(i) = (outFds(i), outFilter(i))
                    i += 1
                events
            }
        }.map { events =>
            Kyo.foreach(events.toSeq) { case (fd, filter) =>
                val p =
                    if filter == EVFILT_READ then
                        val r = pendingReads.remove(fd)
                        if r != null then r
                        else pendingAccept.remove(fd)
                    else if filter == EVFILT_WRITE then
                        pendingWrites.remove(fd)
                    else null
                if p != null then p.completeUnitDiscard
                else Kyo.unit
            }
        }.andThen(Loop.continue)
    }
```

**Result**: Zero AllowUnsafe in the entire native transport. JS callback boundary is the only place it's needed (unavoidable — Node.js callbacks run outside Kyo).

---

## 3. Simplifications Using Kyo Features

### 3a. `remaining` — use `Stream.init` + `concat` instead of manual Emit

**Design says**:
```scala
def remaining(using Frame): Stream[Span[Byte], Async] =
    val lo = leftover; leftover = Span.empty
    if lo.nonEmpty then Stream(Emit.valueWith(Chunk(lo))(src.emit))
    else src
```

**Simpler**: Use Stream's built-in `concat`:
```scala
def remaining(using Frame): Stream[Span[Byte], Async] =
    val lo = leftover; leftover = Span.empty
    if lo.nonEmpty then Stream.init(Seq(lo)).concat(src)
    else src
```

`Stream.init(Seq(lo))` creates a single-element stream. `.concat(src)` appends the rest. No manual `Emit.valueWith` / `src.emit` needed.

### 3b. TLS write — use `foreachChunk` more naturally

**Design says**:
```scala
case Present(session) => session.encrypt(data).foreachChunk { chunk =>
    Loop.indexed { i =>
        if i >= chunk.size then Loop.done(())
        else writeSpan(chunk(i)).andThen(Loop.continue)
    }
}
```

**Simpler**: Use `foreach` directly (iterates elements, not chunks):
```scala
case Present(session) =>
    session.encrypt(data).foreach(writeSpan)
```

`stream.foreach` already handles Chunk iteration internally.

### 3c. listen connections stream — avoid explicit `Chunk(c)` wrapping

**Design says**:
```scala
Emit.valueWith(Chunk(c))(Loop.continue)
```

This is correct since Stream's Emit type is `Emit[Chunk[V]]`. But the wrapping in `Chunk(...)` at every emit site is verbose. This is inherent to Stream's design (chunked emission) and can't be avoided without a helper. Could add:

```scala
private def emitOne[V](v: V)(cont: => Any < Any)(using Tag[Emit[Chunk[V]]], Frame) =
    Emit.valueWith(Chunk(v))(cont)
```

But this is minor — not worth the indirection.

### 3d. `readExact` — could use `stream.take` on the raw stream

For Content-Length bodies, instead of `StreamReader.readExact(n)` which counts bytes across Spans, we could:
1. Have the raw byte stream deliver individual bytes (too slow, original problem)
2. Keep `readExact` as-is (correct approach for byte-granularity counting across OS-read-granularity spans)

`readExact` as designed is the right approach. No change needed.

---

## 4. TransportListener TODO

The design has a TODO on line 33:
```
// TODO why can't the port and host be in Connection?
```

**Answer**: `listen` returns the bound port/host **before** any connection is accepted. When `port = 0`, the OS assigns a port — callers need it immediately to tell clients where to connect. `Connection` only exists after `tcpAccept`, which is too late.

**Resolution**: Remove the TODO, add a brief comment explaining this:
```scala
/** TransportListener carries the actual bound port/host.
  * Separate from Connection because the bound port is known at listen time,
  * before any connection is accepted (needed when port = 0).
  */
```

---

## 5. Correctness Concerns

### 5a. `StreamReader.src` mutation in `splitAt` pattern

After switching from `runFirst` to `splitAt(1)`, `nextSpan` mutates `src`:
```scala
src.splitAt(1).map { (chunk, rest) =>
    src = rest  // mutation inside .map
    ...
}
```

This mutation inside `.map` is safe because:
- Protocol parsing is sequential (one fiber reads at a time)
- `splitAt` completes before the next `nextSpan` call
- The Stream returned by `splitAt` captures the continuation correctly

### 5b. `readUntil` stream consumed before `readBody`

The design's flow: `reader.readUntil(CRLF_CRLF).run` → `reader.readBody(...)`. Between these calls, `leftover` holds bytes after `\r\n\r\n`, and `src` points to the unread portion. This is correct as long as `readUntil`'s stream is fully consumed (`.run`) before calling `readBody`. The types enforce this: `.run` returns `Chunk[Span[Byte]] < S`, not a Stream.

### 5c. `readChunkedStream` inner stream consumption

Inside the chunked stream loop, `reader.readLine().run` and `reader.readExact(n).run` fully consume their sub-streams before the next iteration. Sequential consumption is guaranteed by the Loop's effect sequencing. Correct.

---

## 6. Summary of Changes Needed

| # | Issue | Severity | Fix |
|---|-------|----------|-----|
| 1 | `stream.runFirst` doesn't exist | **Broken** | Use `splitAt(1)` |
| 2 | `Span.concat(Chunk[...])` doesn't exist | **Broken** | Add overload or use `.toSeq*` |
| 3 | AllowUnsafe in poll loop | **Policy** | Use safe `completeUnitDiscard` |
| 4 | `Stream.unfold` with mutable accumulator | **Code smell** | Use `Stream { Loop.foreach }` |
| 5 | Poll loop does I/O in dispatch thread | **Design** | Separate readiness from I/O |
| 6 | `remaining` uses manual Emit | **Simplify** | Use `Stream.init(...).concat(src)` |
| 7 | TLS write uses `Loop.indexed` over chunk | **Simplify** | Use `foreach(writeSpan)` |
| 8 | TransportListener TODO | **Docs** | Answer and remove TODO |
