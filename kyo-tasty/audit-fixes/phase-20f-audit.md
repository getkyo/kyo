# Phase 20f Audit

## Summary

PASS. All seven dimensions are OK or NOTE. No BLOCKERs, no WARNs. The JS hook
is semantically correct, INV-017 and INV-024 are adequately supported, the three
platform implementations are structurally distinct, and the scaladoc no longer
references the old stub language.

---

## Findings

### 1. JS hook semantic correctness - OK

`Sync.defer` accepts `A < S` and returns `A < (Sync & S)`. The try/catch inside
produces either `Array[Byte]` (success path, lifted to `Array[Byte] < Nothing`) or
`Array[Byte] < Abort[TastyError]` (catch path via `Abort.fail`). The unification
gives `Array[Byte] < Abort[TastyError]`, so the full return type is
`Array[Byte] < (Sync & Abort[TastyError])`, which matches the trait signature.

`InflateException.byteOffset` is a `val Long` field on the exception class
(PortableInflate.scala line 6). The catch arm passes `ex.byteOffset` directly
into `TastyError.MalformedSection`. The field is always set at the throw site in
PortableInflate (every throw site provides an explicit Long offset). The byteOffset
propagation is correct.

No uncaught exception type can escape: PortableInflate only throws
`InflateException` (a `RuntimeException`); any other `Throwable` from a corrupt
JVM state would become a `Panic` via Sync's safepoint machinery, which is the
correct escalation path.

### 2. INV-017 byte parity claim - NOTE

INV-017 reads: "JS and Native InflateHook implementations produce byte-for-byte
parity with the JVM reference on valid RFC 1950 input." Phase 20f's direct
demonstration is the shared InflateHookTest running the same 17-byte "hello kyo"
envelope on all three platforms. That is a single fixed-Huffman fixture covering
one code path. However, Phase 20e's round-trip test (42-byte dynamic-Huffman,
1200-byte decompressed output, also platform-shared) provides a second fixture
that exercises the LZ77 + Adler-32 path across all three platforms. Together the
two fixtures cover fixed-Huffman, dynamic-Huffman, LZ77 back-references, and
Adler-32 verification. The corpus is thin relative to the full RFC 1951 space but
is proportionate to the scope of a hook-level parity claim. Real-classfile
end-to-end coverage is deferred to Phase 21e per the decisions doc.

NOTE: the INV text says "byte-for-byte parity with the JVM reference" but the
test does not do a three-way runtime comparison (run JVM + JS + Native in the
same test and assert identical bytes). Each platform independently asserts the
same expected byte sequence. The parity claim is therefore implicit. A future
INV-017 smoke test could make this explicit if required by a later phase.

### 3. INV-024 wiring - OK

The three implementations are structurally distinct:

- JVM: `java.util.zip.InflaterInputStream` wrapped in `Abort.run[Throwable]`
  (jvm/src/main/scala/.../InflateHook.scala).
- Native: `java.util.zip.InflaterInputStream` from scala-native javalib, with
  explicit `ZipException` and `IOException` catch arms inside `Sync.defer`
  (native/src/main/scala/.../InflateHook.scala).
- JS: `PortableInflate.inflate` (in-tree pure Scala) with an `InflateException`
  catch arm inside `Sync.defer` (js/src/main/scala/.../InflateHook.scala).

JVM and Native share the same underlying library (`java.util.zip`) but differ in
their catch structure; JS is entirely distinct. INV-024 is satisfied.

### 4. Test platform divergence broad-match - NOTE

The corruption test uses `_: TastyError` because the JVM hook emits
`TastyError.CorruptedFile` (via the `Throwable` catch in `Abort.run`) while the
JS/Native PortableInflate path emits `TastyError.MalformedSection`. This is
correct given the cross-platform divergence, which is already a tracked WARN from
the Phase 20a audit. The broad match is intentional and documented in the
decisions file. No action needed here; normalization is a Phase 21d concern.

### 5. Removed jvmOnly tag - OK

The "hello kyo" test asserts `bytes.sameElements(expectedBytes)` where
`expectedBytes` is a fixed constant. On each platform the test independently
decodes the same ZLIB fixture and compares against the same expected sequence.
All three platforms must produce identical bytes to pass. Parity is implicit (each
platform converges on the same reference constant) rather than explicit (a
cross-platform comparison within a single run). This is acceptable for a
hook-level test.

### 6. scaladoc updates - OK

The shared `InflateHook.scala` doc no longer says "Phase 20b-f" or "not yet
available on Scala.js". The updated text reads: "On JS, delegates to
PortableInflate, the pure-Scala RFC 1950 inflate (Phase 20f). All three platforms
are now functional." The method-level doc reads: "Returns the decompressed bytes
on all platforms (JVM, Native, JS)." No stale forward-reference language remains.

### 7. Code quality - OK

No em-dashes, semicolons, `asInstanceOf`, `Option`/`Some`/`None`,
`Either`/`Right`/`Left`, default parameters, or `return` keywords in any modified
file. The JS hook is 13 lines. Code quality is clean.

---

## Recommendations

- NOTE (INV-017 parity explicitness): Consider a future smoke test that runs all
  three platform hooks against the same input in a single parameterized test and
  asserts byte-level equality rather than independent reference-constant checks.
  Route: Phase 21e (alongside real-classfile fixture).
- NOTE (Native ZipException path): The Native hook catches `ZipException`
  separately from `IOException` and passes `byteOffset = 0L` for both. The
  PortableInflate path (JS) passes the actual `ex.byteOffset`. This minor
  asymmetry between Native and JS in the MalformedSection offset field is
  pre-existing from Phase 20a and is out of scope for Phase 20f. Route: Phase
  21d cleanup.
