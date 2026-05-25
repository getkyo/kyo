# Phase 13+14+15 Combined Prep

Three mechanical phases. No structural dependencies on each other; Phase 14 depends only on Phase 13 for sequencing, Phase 15 depends on Phase 14 for minorVersion bookkeeping (bump 1->2).

Source of truth: `execution-plan-v2.md` lines 559-666.

---

## Phase 13: G19 -- ReflectError.InconsistentClasspath UUID type

**Commit scope**: 1-line field type change + 1 test.

### File:line anchors

| Change | Location |
|--------|----------|
| `InconsistentClasspath` definition | `kyo-reflect/shared/src/main/scala/kyo/ReflectError.scala:11` |
| Test to add | `kyo-reflect/shared/src/test/scala/kyo/QueryApiTest.scala` (new test, append) |

### Current state

`ReflectError.scala:11`:
```scala
case InconsistentClasspath(file: String, expectedUuid: java.util.UUID, foundUuid: java.util.UUID)
```

Already correct -- `java.util.UUID` is already the type in the file. This means Phase 13 may already be done. Verify with:
```
grep -n "InconsistentClasspath" kyo-reflect/shared/src/main/scala/kyo/ReflectError.scala
```

If it already shows `java.util.UUID`, Phase 13 is complete except for the test.

### Verbatim signature (plan)

```scala
case InconsistentClasspath(file: String, expectedUuid: java.util.UUID, foundUuid: java.util.UUID)
```

### Test contract

```
QueryApiTest (new test):
  ReflectError.InconsistentClasspath("foo.tasty", UUID.randomUUID(), UUID.randomUUID())
  constructs without error and pattern-matches extracting file, expectedUuid, foundUuid.
```

Strict check: test must import `java.util.UUID`, call `.randomUUID()` for both fields, then pattern-match to confirm the `UUID` types round-trip. The test exists only to confirm the type compiles; no runtime I/O.

### Construction-site check

The plan says zero construction sites outside the test exist. Verify:
```
grep -rn "InconsistentClasspath" kyo-reflect/
```
Expected: only `ReflectError.scala` (definition) and the new test.

### Edge cases / anti-flakiness

None -- this is a pure compile-time type change + a compile test. No I/O, no concurrency.

### Concerns

None. The field was already `java.util.UUID` in the current file; the only real deliverable is the test.

---

## Phase 14: G15 -- Snapshot inputDigest fix

**Commit scope**: 2 files modified + 1 test added. `minorVersion` bumps 0 -> 1.

### File:line anchors

| Change | Location |
|--------|----------|
| `SnapshotWriter.serialize` signature | `SnapshotWriter.scala:59` |
| `SnapshotWriter.write` (caller of `serialize`) | `SnapshotWriter.scala:47` |
| `assembleSections` call inside `serialize` | `SnapshotWriter.scala:132` |
| `SnapshotFormat.minorVersion` | `SnapshotFormat.scala:58` |
| Test to add | `SnapshotRoundTripTest.scala` (append after existing tests) |

### Current state

`SnapshotWriter.serialize` is `private def serialize(cp: Classpath): Array[Byte]` -- no `digest` parameter. The `assembleSections` call at line 132 passes `digest = Array.empty[Byte]`, so the header's `inputDigest` field (bytes 16-23) is always zeros regardless of what `write` received.

`SnapshotFormat.minorVersion` is currently `0`.

### Required changes (verbatim from plan)

1. Change `serialize` to accept `digest: Array[Byte]` as a parameter.
2. Thread `digest` from `write` into `serialize`.
3. Thread `digest` from `serialize` into `assembleSections` (replacing `Array.empty[Byte]`).
4. Bump `SnapshotFormat.minorVersion` from `0` to `1`.

Resulting signatures:

```scala
// SnapshotWriter.scala
private def serialize(cp: Classpath, digest: Array[Byte]): Array[Byte]
```

`write` already receives `digest: Array[Byte]` as a parameter -- it just needs to pass it into `serialize`.

### Test contract

```
SnapshotRoundTripTest (new test):
  Write a snapshot with digest Array[Byte](1, 2, 3, 4, 5, 6, 7, 8).
  Read the raw bytes from the in-memory FileSource.
  Assert bytes 16..23 (the inputDigest field at header offset 16) equal [1,2,3,4,5,6,7,8] (not zeros).
```

Byte offsets: header layout per `SnapshotFormat.scala` scaladoc:
- 0-3: magic "KRFL"
- 4-7: version M.m.0.0
- 8-15: flags (8 bytes)
- 16-23: inputDigest (8 bytes)

The test should read the raw bytes via the mock `FileSource` (the `InMemoryFileSource` pattern already used in `SnapshotRoundTripTest`), then assert `bytes(16) == 1`, ..., `bytes(23) == 8`.

### Edge cases / anti-flakiness

- Digest length must be exactly 8 bytes; `assembleSections` already guards `if digest.length >= 8` before copying.
- The test must use a classpath with at least one symbol (or an empty one) -- the format is the same either way. Using the mock in-memory classpath already established in `SnapshotRoundTripTest` is simplest.
- No I/O, no concurrency -- pure byte manipulation; zero flakiness risk.

### Concerns

- `minorVersion` 0 -> 1 changes the written bytes at offset 5. Existing `SnapshotRoundTripTest` tests that read back snapshots written in the same test run will still pass because they write and read the same version. The version-mismatch test (`SnapshotRoundTripTest` line 134) constructs its own bytes manually with a hardcoded version -- verify it doesn't break.

---

## Phase 15: G14 -- BODY_BYTES KRFL section

**Commit scope**: 2 files modified + 2 tests added. `minorVersion` bumps 1 -> 2.

**Prerequisite**: Phase 14 has run; `SnapshotFormat.minorVersion == 1` before this phase.

### File:line anchors

| Change | Location |
|--------|----------|
| `SnapshotWriter.serialize` (collect + write body bytes) | `SnapshotWriter.scala:99-132` |
| `SnapshotWriter.serializeSymbols` (write bodyStart/bodyEnd per symbol) | `SnapshotWriter.scala` (find serializeSymbols) |
| `SnapshotReader` (read BODY_BYTES, restore TastyOrigin) | `SnapshotReader.scala` (find SYMBOLS/BODY_BYTES read path) |
| `SnapshotFormat.minorVersion` | `SnapshotFormat.scala:58` |
| Tests to add | `SnapshotRoundTripTest.scala` (append 2 tests) |

### Current state

`SnapshotWriter.serialize` line 118-128: `bodyBytes` is `Array.empty[Byte]` and `sectionBODYBYTES` is written as an empty section. Symbol records do not include `bodyStart`/`bodyEnd` offsets. On the reader side, `TastyOrigin` is never restored from snapshots.

`Reflect.Symbol.TastyOrigin` fields: `bodyStart: Int`, `bodyEnd: Int`, `rawBytes: Array[Byte]`, `paramNames: Array[Reflect.Name]`, `typeParamCount: Int` (see `Reflect.scala:737`).

`SnapshotFormat.sectionBODYBYTES = "BODYBYTE"` already exists.

### Required changes (verbatim from plan)

In `SnapshotWriter.serialize`:
1. Collect body bytes: for each symbol with a `TastyOrigin`, concatenate `rawBytes(bodyStart..bodyEnd)` slices into a single `Array[Byte]`. Track each symbol's start offset in the concatenated array.
2. Write the concatenated bytes as the `BODY_BYTES` section (replacing the current `Array.empty`).
3. In each symbol's record in `serializeSymbols`, write `(bodyStart, bodyEnd)` relative to the symbol's slot in the `BODY_BYTES` array.

In `SnapshotReader`:
1. After loading `BODY_BYTES` section, create a single `Array[Byte]` backing store.
2. For each symbol with a TASTy origin, reconstruct `TastyOrigin(bodyStart, bodyEnd, bodyBytesArray, paramNames, typeParamCount)` where `bodyStart`/`bodyEnd` are the stored offsets into `BODY_BYTES`.

Bump `SnapshotFormat.minorVersion` from `1` to `2`.

### Test contracts

**Test 1 (body round-trip)**:
```
SnapshotRoundTripTest (new):
  Load a classpath from fixture TASTy (PlainClass.tasty or similar).
  Write snapshot.
  Reload from snapshot (via SnapshotReader or Reflect.openCached).
  Find a method symbol from the snapshot-loaded classpath.
  Call sym.body.
  Assert result is not Abort.fail(ReflectError.NotImplemented(...)).
  (i.e., body bytes survived the round-trip and decode succeeds.)
```

**Test 2 (empty BODY_BYTES)**:
```
SnapshotRoundTripTest (new):
  Build a classpath from classfile-only inputs (no TASTy -- e.g., mock FileSource with one fake classfile-origin symbol).
  Write snapshot.
  Read raw bytes.
  Assert the BODY_BYTES section has length 0 in the section index.
  Assert reading back succeeds without error.
```

### Edge cases / anti-flakiness

- `TastyOrigin.rawBytes` may be shared across multiple symbols from the same TASTy file. The writer must copy only `bodyStart..bodyEnd` per symbol into the concatenated buffer, not the full `rawBytes`.
- Offset arithmetic: `bodyStart` and `bodyEnd` are absolute into `rawBytes`. After concatenation, stored offsets must be relative to the symbol's slot start in `BODY_BYTES`, not into the original `rawBytes`.
- Zero-symbol TASTy files: if no TASTy-origin symbols exist, `BODY_BYTES` is empty; reader must handle empty section gracefully (no index out of bounds on reconstruction).
- Test 1 requires an actual fixture TASTy file. `PlainClass.tasty` is already used in `ReadsDerivationTest`; use the same path via `TestResourceLoader` or `Embedded.plainClassTasty`. The classpath must be opened through `Reflect.open` so `ClasspathOrchestrator` wires TASTy symbols with populated `TastyOrigin`.
- Test 1 calls `sym.body` which triggers `AstUnpickler` tree decode from the round-tripped bytes -- verify `AstUnpickler` is available in the test classpath (it's in `shared/` so it is).
- `minorVersion` 1 -> 2 written by Phase 15. The `SnapshotRoundTripTest` major-version mismatch test uses a hardcoded byte `2` at version offset -- check that it uses the constant from `SnapshotFormat.majorVersion`, not a literal, to avoid breakage.

### Concerns

- The `SnapshotReader` must distinguish between `TastyOrigin` symbols (have body offsets) and `JavaOrigin` symbols (no body offsets). The SYMBOLS record format must encode this. Check whether the existing `serializeSymbols` already writes an `originTag` byte per symbol; if not, add one (e.g., `0` = JavaOrigin, `1` = TastyOrigin).
- If the existing SYMBOLS record format has a fixed record size, adding `bodyStart`/`bodyEnd` fields may break the existing record-stride calculation in `SnapshotReader`. Read the current `serializeSymbols` and `readSymbols` implementations carefully before adding fields; coordinate the stride change.
- Test 1's `sym.body` call needs a method symbol (classes/traits may not have decodable bodies in the current tree unpickler). Choose a symbol with an actual method body in the fixture, or accept that any symbol with a non-empty `bodyStart != bodyEnd` is sufficient to prove round-trip.

---

## Cross-phase notes

- Phases 13/14/15 each have exactly 1, 1, 2 new tests respectively (total 4).
- minorVersion progression: 0 (current) -> 1 (Phase 14) -> 2 (Phase 15). Do not skip Phase 14 when implementing Phase 15.
- Verification commands per plan:
  - Phase 13: `sbt 'project kyo-reflect; testOnly kyo.QueryApiTest'`
  - Phase 14: `sbt 'project kyo-reflect; testOnly kyo.SnapshotRoundTripTest'`
  - Phase 15: `sbt 'project kyo-reflect; testOnly kyo.SnapshotRoundTripTest'`
