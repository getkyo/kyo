# Phase 08b Decisions

## Finding addressed: B9

`PositionsUnpickler.scala` line 82: `lineStarts(k+1) = lineStarts(k) + lineSizes(k) + 1` could silently overflow Int on pathologically large source files, producing negative lineStart values that corrupt all subsequent line/column computations.

## Approach chosen: Long-widened check, throw ArrayIndexOutOfBoundsException

The cumulative addition is widened to Long before comparing against `Int.MaxValue`. If the result exceeds `Int.MaxValue`, an `ArrayIndexOutOfBoundsException` is thrown with a message containing `"exceeds Int.MaxValue"`. This piggybacks on the existing catch clause in `read`, which already intercepts `ArrayIndexOutOfBoundsException` to produce `TastyError.MalformedSection`.

The catch clause was updated to distinguish the overflow message from the generic truncation message: if the exception message contains `"exceeds Int.MaxValue"`, the reason is threaded through verbatim; otherwise the generic `"unexpected end of Positions section"` is used. This lets the B9-1 test assert the reason string directly.

No new exception type was introduced; the change is minimal and localized to the lineStarts construction loop and the catch clause.

## Files modified

- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/reader/PositionsUnpickler.scala`
  - `read`: catch clause updated to preserve overflow message.
  - `readSync`: lineStarts loop widened to Long with overflow check.

- `kyo-tasty/shared/src/test/scala/kyo/PositionsUnpicklerTest.scala`
  - 3 new tests appended (B9-1, B9-2, B9-3).

## Tests added

**B9-1**: `numLines=1`, `lineSizes=[Int.MaxValue]`. The cumulative nextStart = 2147483648 > Int.MaxValue. Asserts `Result.Failure(TastyError.MalformedSection("Positions", reason))` with `reason.contains("exceeds Int.MaxValue")`.

**B9-2**: `numLines=200`, `lineSizes[k]=100+k`. Asserts the read succeeds and the symbol at offset 0 is at line 1, col 1. Baseline correctness for the overflow-free path.

**B9-3**: Same 200-line layout, but the single Assoc entry has `start_delta=1055` (= `lineStarts(10)`). Asserts `pos.line == 11` and `pos.column == 1`, pinning the exact lineStarts(10) formula: `101*10 + 10*9/2 = 1055`.

## Verification

- JVM `testOnly kyo.PositionsUnpicklerTest`: 9/9 PASS (6 pre-existing + 3 new).
- JS `kyo-tastyJS/Test/fastLinkJS`: green.
- Native `kyo-tastyNative/Test/compile`: green.
- HEAD: `bb03b101f` unchanged.
