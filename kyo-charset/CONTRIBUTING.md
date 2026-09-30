# Contributing to kyo-charset

Module-specific guide for kyo-charset. Read the repository-root [CONTRIBUTING.md](../CONTRIBUTING.md) first: it carries the conventions, naming rules, type vocabulary and test patterns that apply across all of Kyo. This document records only what is specific to kyo-charset: the one invariant every change must keep, where the data comes from and how it becomes code, the shape of a decoder, where protocol label policy stops, and how to add an encoding.

## What kyo-charset is

kyo-charset decodes bytes in a named character encoding into a `String`, producing the same characters on the JVM, Scala.js, Scala Native and Wasm. Its public surface is one type in package `kyo.charset`, reached with `import kyo.charset.Charset` (charset decoding is uncommon use, so it stays out of `import kyo.*`, the way kyo-net and kyo-crypto keep their packages): the enum `Charset` with `decode` and `decodeStrict`, its companion's `resolve`, `byName` and `sniff`, and the failure value `Charset.Malformed`. Everything else is `kyo.internal.charset`: the decoders, the generated index tables and label table, and the `Decoders` registry. The module depends on kyo-data alone and has no effect dependency; every operation is a pure function.

## The invariant: WHATWG-exact

`Charset.decode` produces, for every input, exactly the characters the [WHATWG Encoding Standard](https://encoding.spec.whatwg.org/)'s decoder for that encoding produces, and `Charset.resolve` answers exactly what the standard's label table answers. This is what makes the module usable as a browser's peer: a body that renders one way in Chrome renders the same way here, replacement characters included. It is enforced two ways:

- **The tables are the standard's files, byte for byte.** `data/whatwg-encoding/` holds `encodings.json` and every `index-*.txt` as published, with a `MANIFEST` listing each file's SHA-256, source URL and licence. `project/VendoredFiles.scala` verifies every file against the manifest before `project/CharsetTablesGen.scala` generates anything, and fails the build on a mismatch, a missing file or an unlisted one. A table is never edited by hand; a change to the standard is a new copy of its file and a new hash.
- **The tests read the same files independently.** The test generator embeds each raw index file as text, `IndexTableFixtures.entries` parses it with code that shares nothing with the generator, and the decoder tests compare every pointer of every table, and every byte pair of the multi-byte encodings, against that reading. The web-platform-tests decoder cases (`shared/src/test/vectors/wpt-encoding/`) and the utf8tests corpus (`shared/src/test/vectors/utf8tests/`) pin the algorithms' error handling.
- **The JDK is a third reference, on the JVM.** `CharsetJdkDifferentialTest` (`jvm/src/test`) decodes every valid sequence of every charset, every single byte and a seeded set of generated strings with both the module and the JDK's nearest charset, and every input on which they differ is a row of `jdk-25-differences.tsv` with both outputs and a reason (a C1 control the JDK replaces, a table entry the two sources disagree on, UTF-8's maximal-subpart rule). An unlisted difference fails the test on any JDK; a listed one that no longer occurs fails it on the JDK version the list was made on. `CharsetJdkDifferences.main` regenerates the list; do that, and read the diff, when the build moves to another JDK version or a decoder's error path changes.

The four encodings the standard does not define (UTF-7, UTF-32, ISO-2022-KR, HZ-GB-2312) follow their own specifications, cited in each decoder's scaladoc, with the same replacement discipline: one U+FFFD per ill-formed unit, at the point the specification's grammar fails.

## Data to code

Each index is emitted as ASCII string literals: four lowercase hex digits per pointer, `+` and six digits above U+FFFF, `~` for an unmapped pointer, in pointer order; the gb18030 ranges index as twelve-digit pairs. `IndexTable.dense` and `IndexTable.ranges` decode the literals into `Array[Int]` the first time a table's object loads, and every table is a `lazy val`, so a program that decodes only windows-1252 never materializes gb18030's 23,940 entries.

Two numbers are load-bearing. A literal stays under 60,000 bytes because the JVM caps a string constant at 65,535 bytes of modified UTF-8, and each chunk is its own `val` because the compiler folds `"a" + "b"` into one constant, which would exceed the cap again. An array literal is not an alternative: it compiles into one method, which a few thousand entries overflow at 64 KB.

The tables are about 300 KB of constants in the artifact. That is why this is a module and not part of kyo-data: kyo-data is on every dependency path, and the tables would land in every Scala.js bundle. A dependency on kyo-charset is a deliberate choice by a module that decodes legacy text.

## The shape of a decoder

A decoder is a `Decoder` subclass with `charset` and `run(bytes, out)`. `run` reads the whole input once and writes into a `Decoder.Output`, calling `out.replacement(i)` where the algorithm emits U+FFFD, with `i` the decoder's read position at that point. `Output` records the first such offset; `decode` ignores it and `decodeStrict` turns it into `Charset.Malformed(charset, offset)`. This is why the strict mode costs no second implementation: the replacement mode and the fatal mode are the same algorithm, and the offset reported is the position at which the decoder found the sequence ill-formed, at or after the sequence's first byte, or the input's size for a sequence the end cuts short. The `decodeStrict` tests in `CharsetTest` pin those offsets; keep them exact when touching a decoder's error path.

A decoder holds no state between calls. A stateful encoding (ISO-2022-JP, ISO-2022-KR, HZ-GB-2312, UTF-7) keeps its state in locals of `run`, starting from the encoding's initial state each time. There is no streaming decoder; adding one is a new abstraction (`Decoder` with carried state), not a flag on the existing one.

Decoders are shared instances: `Decoders.of` returns the same object for the same charset, with the single-byte decoders created lazily. `CharsetTest` pins both facts.

## Where label policy stops

`Charset.resolve` is the WHATWG label table plus purely additive IANA names (UTF-7, UTF-32 and the `cs*` aliases of the UTF-16 forms). It does not reinterpret any label the standard has: `utf-16` is `Utf16Le`, and the labels the standard maps to `replacement` resolve to `Absent` although two of them (`hz-gb-2312`, `iso-2022-kr`) name a `Charset` the module can decode. A protocol that reads labels differently, as mail does (RFC 2781's UTF-16 with big-endian default, real decoders for the ISO-2022 labels, an RFC 2231 language suffix on the label), applies its own table before `resolve`; that table lives in the protocol module. Do not add a protocol's overrides here, and do not add a parameter that selects a policy: one label table, one answer.

## Adding an encoding

1. If it is a WHATWG encoding with an index: copy the standard's `index-<name>.txt` into `data/whatwg-encoding/`, add its `file` line with the SHA-256 to `MANIFEST`. `encodings.json` already names it, so `WhatwgLabels` and `resolve` need no change.
2. Add the `Charset` case with the standard's exact name (`encodings.json`'s `name`; `CharsetTest` checks every name the file lists is a case).
3. Write the decoder in `kyo.internal.charset`, scaladoc citing the algorithm's section, following the shape above; register it in `Decoders.of`.
4. Tests: every mapped pointer against the raw file, every lead byte with every trail byte against the algorithm, each single byte, the end-of-input cases, empty input; a `decodeStrict` sample in `CharsetTest`; the WPT cases when the standard's test suite has them (add the files to `vectors/wpt-encoding/` with their hashes).
5. `IndexTableFixtures.singleByte` for a single-byte encoding, so the generic table tests cover it.

A non-WHATWG encoding needs, in addition, its label entries in `Charset.ianaAliases` (checked against the WHATWG table for collisions by `CharsetTest`) and its own specification cited.

## Licences

The Encoding Standard is CC BY 4.0, with portions incorporated into source code under the BSD 3-Clause License. The generated tables are such portions: `META-INF/kyo-charset/NOTICE` carries the copyright notice, the conditions and the disclaimer, and `CharsetTablesGen` writes them into every generated table's header. The WPT files are BSD 3-Clause and the utf8tests corpus MIT; each set's `MANIFEST` records the terms and its `LICENSE` file is vendored beside it (licence files are not embedded into test sources).

## Decision checklist

- [ ] Does the change keep every decoder WHATWG-exact? The index and WPT tests are the proof; a test changed to pass is a red flag.
- [ ] Is a new table a verbatim copy with its hash in `MANIFEST`?
- [ ] Does every `out.replacement(i)` site pass the read position, and does `CharsetTest`'s `decodeStrict` leaf still pin the offsets?
- [ ] Is the public surface still one type, in `kyo.charset`, with no effect dependency?
- [ ] Did a protocol's label policy stay out of `resolve`?
- [ ] Do `scalafmtAll` on every platform and `kyo-charsetJVM/doc` pass, and do the JS, Native and Wasm suites run green?
