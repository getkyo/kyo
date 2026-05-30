# Phase 20d Decisions

## Table-encoding strategy: Array vs match

Decision: Array lookups for both length and distance tables.

Two alternatives were evaluated:

| Option | Pros | Cons |
|--------|------|------|
| `match` expressions (257 arms for length, 30 for distance) | Readable in isolation | JVM may not optimize as constant array; branch overhead |
| `Array` constants with index arithmetic | O(1) single array load; JVM JIT constant-folds | Slightly less self-documenting |

Arrays were chosen. `lengthBase`, `lengthExtra`, `distanceBase`, `distanceExtra` are all `private val Array[Int]` initialized once at object load time. The accessor helpers `lengthCode(sym)` and `distanceCode(sym)` each perform a single subtraction and two array accesses, which the JVM can inline and constant-fold. This matches the guidance in the phase instructions ("Use Array lookups (constants) rather than match expressions for performance").

## Fixed-Huffman test approach: hand-built bytes vs Deflater capture

Decision: hand-built bytes derived from first principles.

The canonical code for literal 65 ('A') under RFC 1951 fixed Huffman (§3.2.6):
- Literals 0-143 use 8-bit codes starting at 48 (decimal).
- Code for 65 = 48 + 65 = 113 = 0b01110001 (MSB-first canonical notation).
- DEFLATE bit streams are LSB-first within each byte.
- The HuffmanTree decoder accumulates `code = (code << 1) | readBit()`, so it reads the MSB of the canonical code first.
- Bit sequence for code 113 in stream order: [0,1,1,1,0,0,0,1].
- Packed into one byte (bit0 = LSB): 0b10001110 = 0x8E.
- EOB symbol 256 uses a 7-bit code = 0 (0b0000000); stream bits [0,0,0,0,0,0,0]; packed = 0x00.
- "AAA"+EOB = 31 bits, padded to 32 bits (4 bytes): [0x8E, 0x8E, 0x8E, 0x00].

The Python derivation was cross-checked by simulating the decoder exactly and confirming sym=65 on each of the three decodes and sym=256 on the fourth. Hand-built bytes are preferable over Deflater capture here because they are deterministic, cross-platform, and document the spec derivation inline in the test.

## Dynamic Huffman test deferral rationale

The plan notes that test 3 (dynamic Huffman, "the quick brown fox") depends on the ZLIB wrapper added in Phase 20e to call `PortableInflate.inflate`. Constructing a minimal but valid dynamic Huffman block header by hand requires:
- Encoding HLIT, HDIST, HCLEN counts
- Encoding HCLEN 3-bit code-length lengths in the `codeLengthOrder` permutation
- Decoding those to reconstruct the code-length tree
- Encoding all literal and distance code lengths through the code-length tree

This is roughly equivalent to implementing a mini-compressor, with many opportunities to introduce bit-packing bugs in the test itself that obscure whether `decodeDynamicHuffmanBlock` is correct. The ZLIB round-trip test in Phase 20e uses `java.util.zip.Deflater` (or equivalent) to produce a well-formed dynamic block, then asserts that `PortableInflate.inflate` produces the original bytes. That end-to-end test is both more reliable and more meaningful than a hand-built fixture that tests only the dynamic-decode half.

`decodeDynamicHuffmanBlock` and `decodeCodeLengths` are included in the production code in Phase 20d so Phase 20e can reference them without structural changes. The deferral is test-only.
