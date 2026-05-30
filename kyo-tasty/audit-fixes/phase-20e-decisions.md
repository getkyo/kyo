# Phase 20e Decisions

## ZLIB header validation strategy

The inflate entry point validates the RFC 1950 envelope in this order:

1. Length guard: reject arrays shorter than 6 bytes immediately. The minimum valid
   ZLIB payload is 2 (CMF+FLG) + 1 (deflate empty-stream byte) + 4 (Adler) = 7 bytes,
   but the guard is set to 6 so that obviously truncated inputs fail before any field
   parsing. This matches the plan pseudocode.

2. Compression method check: `(CMF & 0x0F) != 8` rejects non-deflate streams. Method 8
   is the only defined value; all others are reserved (RFC 1950 §2.2).

3. Header checksum: `((CMF << 8) | FLG) % 31 != 0` rejects streams with a corrupt
   CMF/FLG pair. RFC 1950 requires the combined 16-bit word to be a multiple of 31.

4. Preset dictionary flag: `(FLG & 0x20) != 0` rejects streams that use a preset
   dictionary (FDICT bit). kyo-tasty only decodes TASTy Scala-attribute payloads, which
   never carry preset dictionaries, so this is safe to reject entirely.

Only after all four checks pass does the code construct the BitStream at bitOffset=16
(skipping the two validated header bytes) and enter the block-dispatch loop.

## Adler-32 algorithm and Long accumulator rationale

RFC 1950 defines Adler-32 with two 16-bit quantities `s1` and `s2` (called `a` and `b`
here) and a modulus of 65521. A naive implementation that uses 32-bit Int accumulators
can overflow before the modulo reduction: `s2` can reach up to 65520 * 65521 * N/2
before reduction, which overflows Int for any N larger than a few bytes.

Using Long accumulators and taking `% 65521` after each byte guarantees no overflow for
any input size. The final packed value `(b << 16) | a` fits in a Long since b < 65521
and a < 65521 after reduction, so the result is at most 65520 * 65536 + 65520 <
2^32 < Long.MaxValue.

The result is compared against `readU32BE(compressed, tail)` which also returns Long, so
the comparison is exact and sign-extension-free.

## readU32BE: big-endian Adler-32 trailer

ZLIB (RFC 1950 §2.2) stores the Adler-32 trailer as a big-endian 32-bit integer. Each
byte is masked with `& 0xffL` before shifting to prevent sign-extension from a negative
byte value from corrupting the upper bits of the result. The Long result holds the full
unsigned 32-bit value without truncation.

## JVM Deflater fixture capture for the round-trip test (Test 9)

Goal: exercise the `decodeDynamicHuffmanBlock` + `decodeCodeLengths` path end-to-end
(deferred from Phase 20d).

Initial attempt: compress `"the quick brown fox jumps over the lazy dog"` (43 bytes)
with `Deflater(DEFAULT_COMPRESSION)`. Actual BTYPE observed = 1 (fixed Huffman). The
JVM Deflater uses fixed Huffman for short inputs because the overhead of a dynamic
header exceeds the savings.

Second attempt: compress `"aaabbbcccdddeeefffggghhh".repeat(50)` (1200 bytes, highly
repetitive) with `Deflater(DEFAULT_COMPRESSION)`. Result: BTYPE=2 (dynamic Huffman),
42 compressed bytes. This input has a skewed character frequency distribution (each
letter appears 150 times in 8 distinct groups) which causes the Deflater to build a
custom Huffman tree with very short codes for the most frequent symbols.

Verification step: the captured 42-byte array was round-tripped through
`java.util.zip.InflaterInputStream` and confirmed to equal the original 1200-byte input
byte-for-byte before being hardcoded in the test.

In the Scala test the expected bytes are reconstructed via
`"aaabbbcccdddeeefffggghhh".repeat(50).getBytes("UTF-8")` rather than hardcoding 1200
bytes inline, keeping the test readable while remaining platform-agnostic. The ZLIB
fixture bytes themselves are hardcoded as a 42-element `Array[Byte]` literal.

Captured fixture bytes (42 bytes, CMF=0x78 FLG=0x9C BTYPE=2):
  0x78, 0x9c, 0xed, 0xc8, 0x31, 0x01, 0x00, 0x30, 0x0c, 0x02, 0x30, 0xad, 0x50, 0x68,
  0xf1, 0xaf, 0x60, 0x36, 0x76, 0x90, 0x33, 0x00, 0x48, 0xce, 0x8c, 0x24, 0xdb, 0xbb,
  0x7b, 0x77, 0x49, 0xd0, 0xef, 0xf7, 0xfb, 0x9f, 0xfd, 0x03, 0x07, 0x67, 0xd7, 0x28

Java capture code:
  byte[] input = "aaabbbcccdddeeefffggghhh".repeat(50).getBytes("UTF-8");
  ByteArrayOutputStream baos = new ByteArrayOutputStream();
  DeflaterOutputStream dos = new DeflaterOutputStream(baos, new Deflater(Deflater.DEFAULT_COMPRESSION));
  dos.write(input); dos.finish(); dos.close();
  byte[] compressed = baos.toByteArray(); // 42 bytes

## Adler-32 mismatch test byte construction (Test 8)

A minimal stored-block ZLIB envelope for an empty payload:
  byte[0] = 0x78 (CMF: CM=8, CINFO=7)
  byte[1] = 0x9C (FLG: FCHECK such that (0x78*256+0x9C)%31==0; FDICT=0)
  byte[2] = 0x01 (BFINAL=1 at bit0, BTYPE=00 at bits1-2, padding zeros at bits3-7)
  byte[3] = 0x00, byte[4] = 0x00  (LEN=0, little-endian)
  byte[5] = 0xFF, byte[6] = 0xFF  (NLEN=0xFFFF; LEN^NLEN=0xFFFF passes check)
  bytes[7..10] = Adler-32 of empty payload (a=1, b=0) = 0x00000001 big-endian

The correct trailer [0x00,0x00,0x00,0x01] is replaced with [0xFF,0xFF,0xFF,0xFF] to
trigger the mismatch path. inflate decodes the stored block successfully (empty output),
then computes adler32([]) = 1, reads expected = 0xFFFFFFFF, finds mismatch and throws.
