# Phase 04c Decisions

## Finding addressed: B11

`parseCenRecordsAll` in `JarCentralDirectory.scala` previously set `pos = cenSize` (silently stopping) when the declared CEN record size exceeded the remaining bytes in the buffer. This violated INV-012 (no silently-dropped entries).

## Decision

Changed the truncated-record branch in `parseCenRecordsAll` to throw `java.io.IOException` with a message containing `"truncated CEN record at $pos: declared size $recordSize exceeds remaining ${cenSize - pos}"`.

The `IOException` contract is correct here: `parseCenRecordsAll` is a private utility called from `JarMappedReader.open`, which already declares `IOException` as its failure mode. Using `IOException` (not `TastyErrorWrapper`) matches the surrounding code in that method.

The other two parsing methods (`parseCenRecords` and `parseCenRecordsFull`) share the same silent-skip pattern but are called from the `list`/`listFull` public paths, which are outside the scope of B11. Those are addressed if a follow-on finding targets them.

## Test added

`P04c-T1`: Crafts a 100-byte CEN buffer containing one record with `nameLen = 1000`, making `recordSize = 1046 > 100`. Calls `parseCenRecordsAll` directly (accessible from package `kyo` since the method is `private[kyo]`). Asserts that `IOException` is thrown and its message contains `"truncated CEN record"`. Pins B11, INV-012.

## Files modified

- `kyo-tasty/jvm/src/main/scala/kyo/internal/tasty/query/JarCentralDirectory.scala` (`parseCenRecordsAll`)
- `kyo-tasty/jvm/src/test/scala/kyo/JarCentralDirectoryTest.scala` (added `P04c-T1`)

## Verification

- `kyo-tasty/Test/compile`: PASS
- `testOnly kyo.JarCentralDirectoryTest`: 14/14 passed
- HEAD: `879b88897` unchanged (no commit)

## Post-verify mechanical fixes (Phase 04c verify FAIL)

Four issues flagged by the verify gate were fixed without changing test semantics:

1. **Semicolons at original lines 593, 595** (now split): `cenBuf(0) = 0x50; cenBuf(1) = 0x4b; ...` was a single chained statement. Split into one assignment per line per the no-semicolons rule.

2. **Option/Some tokens at original lines 607, 609**: The `try { ... ; None } catch { case ex => Some(ex.getMessage) }` idiom was replaced with `intercept[java.io.IOException] { ... }` per the kyo.Test idiom. This removes `Option` and `Some` entirely and also removes the now-redundant `.isDefined` / `.get` assertions; the message-content assertion is preserved verbatim.

After fixes: 0 Option/Some tokens in P04c test, 0 semicolons in P04c test, 14/14 passed.
