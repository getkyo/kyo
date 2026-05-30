# Phase 20e Audit

## Summary

PASS. All seven dimensions are OK or NOTE. No BLOCKERs, no WARNs. The ZLIB
envelope dispatch is correct per RFC 1950, the Adler-32 implementation is
verified byte-for-byte, the readU32BE trailer read is sign-extension-safe, and
the round-trip fixture Adler-32 checksum matches the computed value exactly.

---

## Findings

### 1. CMF/FLG validation - OK

All four checks land correctly:

- CM mask: `(cmf & 0x0f) != 8` isolates the low nibble per RFC 1950 S2.2. Correct.
- Header checksum: `((cmf << 8) | flg) % 31 != 0`. For the test fixture
  (CMF=0x78, FLG=0x9C): (0x78*256 + 0x9C) = 30876; 30876 % 31 = 0. Passes.
  A byte-flipped header would have a non-zero remainder and correctly fail.
- FDICT bit: `(flg & 0x20) != 0`. Bit 5 of FLG is the preset-dictionary flag.
  The mask and sense are correct.
- BitStream constructed at bitOffset=16 (skipping both header bytes). Correct.

The short-input test (Test 7) uses a 4-byte array, which is below the guard of 6,
and correctly produces "ZLIB input too short (< 6 bytes)". No corrupt-CMF or
FDICT-set test is present, but the logic paths are simple enough that the existing
header-checksum test (which exercises the full validation chain) provides adequate
coverage for this scope.

### 2. Adler-32 algorithm - OK

Implementation: a = 1, b = 0; per byte: a = (a + (byte & 0xff)) % 65521;
b = (b + a) % 65521; result = (b << 16) | a.

- Initial values correct (RFC 1950 S8.2: s1 starts at 1, s2 at 0).
- Modulus 65521 is the largest prime less than 2^16, per spec.
- Modulo applied per byte (plan spec). No overflow risk with Long accumulators.
- adler32([]) = (0 << 16) | 1 = 1. Correct (RFC 1950 S8.2 example).
- adler32("aaabbbcccdddeeefffggghhh" * 50) computed independently = 0x0767d728.
  Fixture trailer bytes [-4:] = [0x07, 0x67, 0xd7, 0x28]. readU32BE = 0x0767d728.
  Exact match confirmed.

### 3. readU32BE correctness - OK

Each byte masked with `& 0xffL` before shifting. This prevents sign-extension: a
byte value of 0xFF reads as -1 (signed byte in Scala/JVM); without the mask,
`(-1).toLong << 24` = 0xFFFFFFFF00000000L, corrupting the result. With the mask
it becomes 255L << 24 = 0xFF000000L. The four-byte big-endian assembly is correct.
The Long return type carries the full unsigned 32-bit value without truncation.

### 4. Block-type 3 handling - OK

`case 3 => throw new InflateException("reserved DEFLATE block type 3", stream.byteOffset)`

readBits(2) returns values 0..3 only; the match is exhaustive. The InflateException
carries the current stream byte offset. No test exercises this path, which is
acceptable: it is a single-line defensive throw with no branching logic to
independently verify.

### 5. Round-trip test fixture validity - OK

Fixture: 42 bytes, BTYPE field at byte[2] = 0xed. In LSB-first DEFLATE bit order:
bit 0 = BFINAL = 1 (last block); bits 1-2 = BTYPE = 0b10 = 2 (dynamic Huffman).
Confirmed: the fixture exercises decodeDynamicHuffmanBlock, fulfilling the Phase
20d deferral.

Compression ratio: 42 bytes compressed from 1200 bytes = 28.6x. Plausible for a
24-character repeating cycle with highly skewed byte frequencies. The decisions doc
records that the initial "quick brown fox" input produced BTYPE=1 (fixed Huffman),
so the agent correctly iterated to a longer repetitive input to force BTYPE=2.

Adler-32 trailer cross-check: independently computed adler32 of the expected
decompressed output matches the last 4 bytes of the fixture exactly (0x0767d728).
The fixture is self-consistent.

### 6. Plan-pseudocode rewrite - OK

The inflate function uses direct throw statements inside the function body with no
Either, Right, Left, Sync.defer, or Abort.fail. This matches the no-Either pattern
used throughout (Phase 20a pattern). The InflateException extends RuntimeException
and is thrown directly. Consistent with all prior phases.

### 7. Code quality - OK

No em-dashes, semicolons, asInstanceOf, Option/Some/None/Either, default parameters,
or return keywords found in the implementation. The two helpers (adler32, readU32BE)
are `private`, appropriately scoped. inflate itself is `def` (package-public), which
is the correct visibility for the public entry point. No new `private[kyo]` widening
was introduced in this phase (the new methods are either `def` or `private`).

---

## Recommendations

- NOTE (test coverage): No test exercises a CMF with CM != 8 or a stream with FDICT
  set. The code paths are trivial single-line throws, so this is low priority.
  Route: Phase 21d cleanup.
- NOTE (tail.toLong in InflateException): `tail` from alignToByte() is already an
  Int; `tail.toLong` in the mismatch exception is correct but the call site uses
  `tail` (Int) directly in readU32BE without a cast (also correct, since readU32BE
  takes Int). No functional issue, just a minor style note.
