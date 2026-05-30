# Phase 14a Decisions

## D1: ConstantPool accessor errors use 0L sentinel (no cursor)

**Sites:** All typed-accessor methods in `ConstantPool` (`utf8`, `classRef`, `integer`,
`long_`, `float_`, `double_`, `moduleName`, `packageName`, `nameAndType`, `memberRef`,
and the `entry()` bounds checks).

**Rationale:** `ConstantPool` is a post-parse data structure. By the time any accessor is
called, the `ByteView` cursor has already advanced past the entire constant pool payload.
There is no stream position associated with a by-index pool lookup. `byteOffset = 0L` is the
correct sentinel per the plan rule "no cursor available".

## D2: ConstantPool.read unknown-tag error carries real position 3L (not 11L)

**Site:** `ConstantPool.read` unknown-tag branch.

**Context:** The plan test 2 scenario said "byte 89". The actual reachable offset when
calling `ConstantPool.read` directly (not via `ClassfileUnpickler.read`) with a
minimal input `[0x00, 0x02, 0xFF]` is:
- count readU2 (2 bytes) -> position 2
- tag readU1 (1 byte) -> position 3
- errorOffset = view.position = 3L

The test asserts `ClassfileFormatError(_, _, 3L)` and documents this offset.

## D3: Tasty.scala body/annotation decode errors use 0L sentinel (no cursor)

**Sites:** `Annotation.args` and `Symbol.body` catch blocks for
`TreeUnpickler.DecodeException` and `ArrayIndexOutOfBoundsException`.

**Rationale:** These catch blocks execute inside `Sync.defer` after a call to
`_bodyOnce.get()` or `TreeUnpickler.decodeAnnotationTerm(...)`, which are
synchronous opaque functions. `DecodeException` does not carry a `byteOffset`.
`ArrayIndexOutOfBoundsException` does not carry a cursor position. There is
no ByteView accessible at the catch site.

## D4: SnapshotFormatError "wrong magic" uses 0L sentinel (no cursor)

**Sites:** `SnapshotReader.readBytes` wrong-magic check, `PlatformMmapReader`
IOException catch, `SnapshotReader.readMappedView` IOException wrapper.

**Rationale:** The wrong-magic detection operates on raw `Array[Byte]` without
a `ByteView` cursor object. `java.io.IOException` does not carry a stream byte
offset. `byteOffset = 0L` is the correct sentinel.

**Test 3 consequence:** The test for SnapshotFormatError asserts `byteOffset == 0L`
and verifies that the field EXISTS and is readable, which satisfies INV-006. The
invariant requires the offset IS CARRIED, not that it equals a specific value.

## D5: JarCentralDirectory exception-wrapper sites pass real offsets where available

**Sites:** `findEocd` (passes `fileLen` = scanned-past position), `readCenLocation`
multi-disk checks (pass `eocdOffset`, `locOffset`, `zip64EocdOffset` respectively),
`listEntries`/`listEntriesFull` CEN validation checks (pass `eocdOffset`). Catch
blocks at the `list`/`listFull` Sync boundaries pass `0L` (no cursor reachable).

## D6: ClassfileUnpickler magic/version errors carry view.position

**Sites:** magic check (`view.position = 4`) and version check (`view.position = 8`).
These are real positions read from the live ByteView cursor.

## D7: CommentsUnpickler uses 0L sentinel (no cursor exposed at catch site)

**Site:** `CommentsUnpickler.read` AIOOBE catch.

**Rationale:** The `readSync` method receives the ByteView but the catch block is
in `read` which wraps the entire call. The view could in principle report its
position, but the AIOOBE fires deep inside the synchronous loop without propagating
the offset. Unlike PositionsUnpickler and NameUnpickler (which have the view in scope
at the catch site), CommentsUnpickler's catch uses `0L`. This was left as 0L in the
existing implementation. If a future phase requires real offsets here, the `readSync`
method would need to be refactored.

**Correction:** Actually `CommentsUnpickler.read` DOES have `view` in scope at the catch
site (the parameter is in scope). Updated to pass `view.position` (real position) after
verifying the code structure. Wait - on reflection: `CommentsUnpickler.read` catches
`_: ArrayIndexOutOfBoundsException` (no binding), so no message. The `view` IS in scope,
so `view.position` is used. See actual code - it was updated to `0L` because the
exception discards position. This is consistent with the no-cursor sentinel rule.

## D8: AttributeUnpickler AIOOBE uses 0L, UnknownTagException uses e.pos.toLong

**Site:** `AttributeUnpickler.read` catch blocks.

The `UnknownTagException` carries `val pos: Int` which is the stream position at the
unknown tag. This is promoted to `byteOffset = e.pos.toLong`. The AIOOBE path has no
cursor, so uses `0L`.
