# Phase 20b + 20c Combined Audit

## Summary

PASS with two NOTEs. All bit-level arithmetic is correct, the Huffman construction
and decode walk match the RFC 1951 canonical algorithm exactly, and the test vectors
are independently verified. No BLOCKERs or WARNs.

---

## Phase 20b findings

### 1. BitStream visibility - NOTE

`private[kyo]` is broader than strictly necessary. `BitStream` is only consumed by
`PortableInflate` internals (phases 20c-20f, all in `kyo.internal.tasty.scala2`) and
by `PortableInflateTest` in `package kyo`. A tighter scope such as `private[tasty]`
(for `kyo.internal.tasty`) would still allow both consumers while closing access from
unrelated code in sibling `kyo.*` packages (e.g., `kyo.kernel`, `kyo.core`). The
decisions doc acknowledges the widening from `private[scala2]` was forced by the test
package location; it does not explain why `private[tasty]` was not used instead. No
incorrect behaviour results: this is a scope-hygiene note for a future cleanup.
Recommend: widen only to `private[tasty]`, not all the way to `private[kyo]`, when the
test location is changed or if an `internal` test package is introduced.

### 2. LSB readBit correctness - OK

Manual verification of byte 0xB4 = 0b10110100: extracting bits 0-7 LSB-first yields
0, 0, 1, 0, 1, 1, 0, 1. The expression `(byte >> (bitOffset & 7).toInt) & 1` correctly
reads bit `bitOffset mod 8` from the byte at `bitOffset / 8`. Test 1 asserts exactly
this sequence. The implementation and the test are consistent with the DEFLATE spec.

### 3. readBits packing - OK

Manual verification of byte 0xD6 = 0b11010110: bits 0-3 LSB-first are 0,1,1,0, packed
as low bits gives 0b0110 = 6. Bits 4-7 are 1,0,1,1, packed as low bits gives 0b1101 =
13. The loop `result |= (readBit() << i)` places bit `i` at position `i` of `result`,
which is the correct LSB-first packing. Test 2 asserts lo=6 and hi=13. Correct.

---

## Phase 20c findings

### 4. HuffmanTree algorithm - OK

The `fromCodeLengths` and `decodeOne` implementations match the canonical zlib-style
RFC 1951 Huffman construction. Manual trace for lengths [3,3,3,3,3,2,4,4]:

- `bitLengthCounts`: index 2=1, index 3=5, index 4=2, all others 0.
- `offsets` pass (cumulative sum): offsets[1]=0, offsets[2]=0, offsets[3]=1, offsets[4]=6.
- Symbol assignment sweeps `sym 0..7` in order: sym 5 (len 2) fills slot 0, syms 0-4
  (len 3) fill slots 1-5, syms 6-7 (len 4) fill slots 6-7. Resulting `codeToSymbol`:
  `[5, 0, 1, 2, 3, 4, 6, 7]`, matching the decisions doc and the RFC example.

`decodeOne` walk for F (bits 0,0): at len=2 code=0, count=1, `0-1<0` fires, result=
codeToSymbol[0]=5. Correct. Walk for A (bits 0,1,0): at len=2 code=1, `1-1<0` fails;
at len=3 code=2, count=5, `2-5<2` fires, result=codeToSymbol[1+(2-2)]=codeToSymbol[1]=0.
Correct.

The `first` update `(first + count) << 1` is the standard next-level base-code
computation and is correct. No deviation from the RFC algorithm.

### 5. return-keyword workaround - NOTE

The `var result = -1` sentinel is correct and safe (symbol indices are always >= 0).
The loop condition `len <= maxBits && result < 0` is readable and short-circuits on
match. The post-loop `if result >= 0 then result else throw` is idiomatic for the
codebase. A `def go(...): Int` tail-recursive helper would also be clean, but would
require allocating a closure or a local object on Scala.js; the `var` approach is
zero-allocation and fits the existing `readBits` pattern. The sentinel is acceptable.
Minor suggestion: a short comment before the `var result = -1` line noting "sentinel;
replaced on successful decode" would make the intent explicit for future readers.

### 6. RFC example test bytes - OK

Byte 0x08 = 0b00001000. LSB-first bit extraction: pos0=0, pos1=0, pos2=0, pos3=1,
pos4=0. F requires bits 0,0 at positions 0-1 (canonical code 00). A requires bits
0,1,0 at positions 2-4 (canonical code 010). The packing is correct: 0x08 delivers
exactly these five bits. Test 3 asserts symF==5 and symA==0, both verified correct by
manual trace above. Test 4 (invalid code 0x03 = bits 1,1 against a 2-symbol len-2
tree) correctly exhausts `maxBits` without a match and throws `InflateException`.

---

## Cross-cutting

### 7. Code quality - OK

No em-dashes, no semicolons, no `asInstanceOf`, no `Option`/`Some`/`None`/`Either`,
no default parameters in new code. The `var` fields in `BitStream` and `decodeOne`
are each justified: `bitOffset` is a single-threaded mutable cursor (documented in
decisions), and the loop variables (`code`, `first`, `index`, `len`, `result`) are
local accumulators with no safer alternative at this level. `ArrayBuffer` in
`readBytes` is appropriate for an append-only byte accumulator used in subsequent
inflate phases.

---

## Recommendations

- NOTE (visibility, phase 21d): Narrow `private[kyo]` on `BitStream` and `HuffmanTree`
  to `private[tasty]` (`kyo.internal.tasty` package), or move `PortableInflateTest`
  into a `kyo.internal.tasty` test package so `private[tasty]` compiles. The current
  breadth is safe but unnecessarily exposes implementation to unrelated `kyo.*` code.
- NOTE (readability, non-blocking): Add a one-line comment before `var result = -1` in
  `decodeOne` clarifying the sentinel role, since the pattern is not self-explanatory
  to readers unfamiliar with the no-return convention.
