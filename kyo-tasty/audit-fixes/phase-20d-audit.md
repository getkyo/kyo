# Phase 20d Audit

## Summary

PASS with one WARN and two NOTEs. All RFC 1951 tables are byte-for-byte correct,
the Huffman block dispatch is structurally sound, and both new tests pass the hand-
computed vectors. The WARN is for copyBack lacking a bounds check when dist exceeds
the output buffer size so far (undefined behavior if a corrupt stream is fed). No
BLOCKERs.

---

## Findings

### 1. Length-code table - OK

Full spot-checks against RFC 1951 Table 1:

- index 0 (sym 257): base=3, extra=0. Correct.
- index 8 (sym 265): base=11, extra=1. Correct.
- index 16 (sym 273): base=35, extra=3. Correct.
- index 28 (sym 285): base=258, extra=0. Correct.

All 29 entries in `lengthBase` and `lengthExtra` match the spec table exactly,
including the terminal entry (sym 285, base=258, extra=0).

### 2. Distance-code table - OK

Full spot-checks against RFC 1951 Table 2:

- index 4: base=5, extra=1. Correct.
- index 8: base=17, extra=3. Correct.
- index 16: base=257, extra=7. Correct.
- index 28: base=16385, extra=13. Correct.

All 30 entries in `distanceBase` and `distanceExtra` match the spec table exactly.

### 3. fixedLiteralLengths layout - OK

`Array.tabulate(288)` with `i < 144 -> 8`, `i < 256 -> 9`, `i < 280 -> 7`,
`else -> 8` produces exactly the four bands from RFC 1951 §3.2.6:

- 0..143 = 8 (144 entries)
- 144..255 = 9 (112 entries)
- 256..279 = 7 (24 entries)
- 280..287 = 8 (8 entries)

Total: 288. All boundaries correct.

### 4. fixedDistanceLengths - OK

`Array.fill(30)(5)`. Trivially correct per §3.2.6.

### 5. copyBack LZ77 - WARN

`out += out(start - dist + i)` where `start = out.length` before the loop. The
repeating-run behavior is correct: when dist=1 and len=5, the newly appended byte
is read back on subsequent iterations, producing the expected repeat (e.g.,
appending "AAAAA" from one base 'A'). The index formula is right for valid input.

The issue is the absence of a bounds check. If dist > out.length at call time,
`start - dist` is negative and `out(negative)` throws
`IndexOutOfBoundsException` rather than `InflateException`. A well-formed DEFLATE
stream from a conforming encoder cannot produce this case, but a corrupt or
adversarial stream can. The exception type leaks an internal buffer detail rather
than producing the codebase's own `InflateException` with a useful offset.

Recommend: add a guard at the top of `copyBack`:

```scala
if dist > out.length then
    throw new InflateException(s"copyBack dist=$dist > out.length=${out.length}", -1L)
```

This is non-blocking for Phase 20d because no encoder test exercises the corrupt
path, but it should be addressed before Phase 20e ZLIB round-trip testing begins
to avoid confusing error messages.

### 6. RLE handling - OK

All three branches in `decodeCodeLengths` verified against RFC 1951 §3.2.7:

- sym 16: `n = readBits(2) + 3` gives repeat count 3..6. Reads `arr(i-1)` as the
  value to repeat (RFC: "copy the previous code length 3-6 times"). Correct. (The
  spec prohibits sym 16 at position 0 so `i-1` is always valid for conforming input.)
- sym 17: `n = readBits(3) + 3` gives zero run 3..10. `arr` is initialized to 0,
  so simply advancing `i += n` leaves the correct zero values. Correct.
- sym 18 (else branch): `n = readBits(7) + 11` gives zero run 11..138. Same
  mechanism. Correct.

### 7. Code quality - NOTE (two items)

No em-dashes, no semicolons, no `asInstanceOf`, no `Option`/`Some`/`None`/`Either`,
no default parameters, no `return` keyword. `var` usage (loop counters `i`, `k`,
`done`) is all justified.

Two minor items:

a. `val _ = stream.alignToByte()` in `decodeStoredBlock` discards the return
   value explicitly. A plain `stream.alignToByte()` (statement form) would be
   cleaner and equally correct. Not a functional issue.

b. `private[kyo]` on `decodeStoredBlock`, `decodeFixedHuffmanBlock`,
   `decodeHuffmanBlock`, `copyBack`, `decodeDynamicHuffmanBlock`, and
   `decodeCodeLengths` is wider than necessary for the same reason noted in the
   Phase 20bc audit (test lives under `package kyo`). Already tracked in
   steering.md for Phase 21d.

---

## Recommendations

- WARN (correctness, corrupt input): Add a bounds check in `copyBack` to throw
  `InflateException` when `dist > out.length` instead of propagating a raw
  `IndexOutOfBoundsException`. Route: Phase 20e (before ZLIB round-trip tests).
- NOTE (style): Replace `val _ = stream.alignToByte()` with a bare
  `stream.alignToByte()` call in `decodeStoredBlock`. Route: Phase 21d doc sweep.
- NOTE (visibility): Already tracked in steering.md (Phase 20bc audit); applies
  equally to the six `private[kyo]` helpers added here. No new action needed.
