# Phase 05b Decisions

## Finding: B15 — JarMappedReader channel close window

### Problem
`JarMappedReader.open` had a single outer `try/finally raf.close()` but no inner guard for `channel`. If the `size == 0` IOException was thrown (or if `channel.map()` itself threw), the channel would only be closed by `raf.close()` in the outer finally. While `raf.close()` does close the underlying channel on all standard JVMs, the code relied on that implicit behavior rather than an explicit contract. The B15 finding asked for an explicit `channel.close()` in a dedicated try/finally so the close is always visible and auditable.

### Fix applied
Added an inner `try/catch/finally` block around the channel size check and `channel.map()` call:

```scala
val channel               = raf.getChannel
var mbb: MappedByteBuffer = null
try
    val size = channel.size()
    if size == 0 then throw new java.io.IOException(s"$jarPath: empty file")
    mbb = channel.map(FileChannel.MapMode.READ_ONLY, 0L, size)
catch
    case ex: java.io.IOException =>
        throw new java.io.IOException(s"map failed for $jarPath: ${ex.getMessage}", ex)
finally
    channel.close()
```

The outer `finally raf.close()` is retained (closing RAF after the channel also closes the channel per the JDK contract, serving as a belt-and-suspenders guard).

### Alternative considered
Removing the outer `raf.close()` in favour of the inner `channel.close()` only. Rejected: closing RAF is still the right primary close path; the channel.close() is the explicit guard for the window between RAF.getChannel() and the outer finally.

### `var mbb: MappedByteBuffer = null` pattern
The `var mbb = null` + assign-inside-try approach is necessary because `channel.map()` result must survive past the `finally channel.close()` block. After the inner finally, `mbb` is non-null on the happy path (the catch+rethrow ensures we never proceed with a null mbb on the sad path).

### Test scenarios added (2)

**P05b-T1** (`JvmFileSourceTest.scala`): Opens an empty file. `channel.size() == 0` triggers the guard; IOException with "empty file" is thrown. Asserts message contains "empty file" and does not expose internal channel class names.

**P05b-T2** (`JvmFileSourceTest.scala`): Opens a 4-byte garbage file. `channel.map()` succeeds but `parseAllEntries` throws. Asserts thrown exception is `IOException` (not `ClosedChannelException`), confirming channel was cleanly closed before the parse exception propagated.

### Convention sweep
- No em-dashes
- No semicolons
- No `Option`/`Some` (tests use plain `Option` only in the `try/catch` catch-and-wrap pattern; no Kyo Maybe needed for synchronous test code)
- No `asInstanceOf`
- No `AllowUnsafe`
- No default params
