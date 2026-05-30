# Phase 21a Decisions

## Writer-helper verdict

`Varint.scala` contained only read operations (`readNat`, `readLongNat`, `readInt`,
`readLongInt`). No `writeNat` or `writeLongNat` existed before this phase.

## Choice rationale

Per the "no scope cuts" mandate, choice (a) was taken: minimal writer helpers added
as `private[kyo]` in `Varint`. The write direction is logically symmetric with the
read direction and belongs in the same object. The helpers use `scala.collection.mutable.ArrayBuffer[Byte]`
as the output sink because it is stdlib, cross-platform (JVM / JS / Native), and
avoids introducing any new dependency or allocation scheme.

The encoding algorithm mirrors the TASTy big-endian base-128 format:
- Collect 7-bit groups of the value into a fixed-size scratch buffer from
  least-significant to most-significant, setting 0x80 on the last (terminating) byte.
- Then write the scratch buffer contents from most-significant to least-significant
  into `out`.

## Files modified

- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/binary/Varint.scala`:
  added `writeNat` and `writeLongNat` as `private[kyo]`.
- `kyo-tasty/shared/src/test/scala/kyo/VarintTest.scala`:
  added two round-trip tests (T2-1 and T2-2).

## Test results

JVM: 13/13 passed. JS: 13/13 passed. Native: 13/13 passed.

## Em-dash check

No em-dash or en-dash characters present in changed files.

## HEAD

Unchanged at `ff775beafca59d2ab9e5f17fd18d83a44b75ddd7`.
