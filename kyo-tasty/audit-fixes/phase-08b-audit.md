# Phase 08b Audit — PositionsUnpickler lineStarts overflow bound

Path: `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/reader/PositionsUnpickler.scala`
HEAD: `23fb0bed8`

## Verdicts

1. **Widening before check — PASS.** `val nextStart: Long = lineStarts(k).toLong + lineSizes(k).toLong + 1L` performs Long arithmetic via explicit `.toLong` on the left operand before the `+`, so JLS widening applies to the whole RHS. No Int intermediate exists; overflow cannot occur prior to the bounds check.

2. **Threshold check — PASS.** `if nextStart > Int.MaxValue` is the correct strict-greater inequality (Int.MaxValue itself remains a valid index for `Array[Int]`), executed *before* `lineStarts(k + 1) = nextStart.toInt`, so the truncating `.toInt` only runs in the safe path. The ArrayIndexOutOfBoundsException message contains the literal "exceeds Int.MaxValue" which the catch arm in `read` matches verbatim, threading the structured reason into `MalformedSection`.

3. **B9-3 line/col pin — PASS.** Closed-form `lineStarts(10) = 101*k + k*(k-1)/2 = 1010+45 = 1055` is correct for `lineSizes(i)=100+i` with the `+1` newline. start_delta=1055 reaching curStart=1055 lands on `lineStarts(10)` (0-based) giving line 11, col 1. Arithmetic is precise, not a tautology over the impl.

4. **Cross-platform — PASS.** Both source and tests live under `shared/`; no JVM-only API (`java.lang.System.arraycopy` is available on JS/Native). phase-08b-verify.md records JS/Native Test/compile green.

## NOTE for Phase 09 prep
B9-1 covers the first-add overflow boundary. A complementary test pinning `nextStart == Int.MaxValue` (just-under threshold) would seal the strict-`>` boundary — optional, not blocking.

## Overall
**READY.**
