# kyo-charset

Character-set decoding that behaves the same on the JVM, Scala.js, Scala Native and Wasm: every encoding of the [WHATWG Encoding Standard](https://encoding.spec.whatwg.org/) except `replacement` and `x-user-defined`, plus UTF-7, UTF-32, ISO-2022-KR and HZ-GB-2312. A byte sequence decodes here exactly as a browser decodes it.

The JDK's `java.nio.charset` is not a cross-platform answer: Scala.js and Scala Native ship six charsets (US-ASCII, ISO-8859-1, UTF-8 and the three UTF-16 forms), and even the JVM has no UTF-7, `x-mac-cyrillic` or HZ-GB-2312. kyo-charset carries the WHATWG index tables as string constants, decoded on first use, one table at a time.

```scala
import kyo.*
import kyo.charset.Charset

// A body whose Content-Type says charset=iso-8859-1: WHATWG reads that label as windows-1252.
val body: Span[Byte] = Span.from(Array[Byte](0x50, 0x72, 0x69, 0x78, 0x20, 0xa3.toByte, 0x39))

val text: Maybe[String] = Charset.resolve("iso-8859-1").map(_.decode(body))
// Present("Prix £9")
```

## Resolving a label

`Charset.resolve` turns a label into a `Charset` through the WHATWG label table: `latin1`, `cp1252`, `us-ascii` and `iso-8859-1` all resolve to `Windows1252`; `gb2312` to `Gbk`; `x-sjis` and `ms_kanji` to `ShiftJis`; `utf8` and `unicode-1-1-utf-8` to `Utf8`. The label is trimmed of ASCII whitespace and folded ASCII-only, so `" UTF-8 "` resolves and a Turkish dotted `İ` or a Kelvin sign never matches. The IANA registry names WHATWG lacks are added: `UTF-7` (with `csUTF7` and `UNICODE-1-1-UTF-7`), `UTF-32` and its byte-order forms, and the `cs*` aliases of the UTF-16 forms.

```scala
import kyo.*
import kyo.charset.Charset

Charset.resolve("latin1")       // Present(Charset.Windows1252)
Charset.resolve("x-sjis")       // Present(Charset.ShiftJis)
Charset.resolve("UTF-7")        // Present(Charset.Utf7)
Charset.resolve("x-unknown")    // Absent
Charset.byName("Shift_JIS")     // Present(Charset.ShiftJis): the exact WHATWG name
```

Three WHATWG answers are worth knowing. `utf-16` resolves to `Utf16Le`, as the standard says; a protocol that reads a bare `UTF-16` label by RFC 2781 (big-endian unless a byte order mark says otherwise), as mail does, maps that label to `Charset.Utf16` itself before calling `resolve`. The labels WHATWG maps to its `replacement` decoder (`hz-gb-2312`, `iso-2022-kr`, `iso-2022-cn`) resolve to `Absent`; `Charset.HzGb2312` and `Charset.Iso2022Kr` exist for a caller that chooses to decode them, through `byName` or the enum case. `replacement` and `x-user-defined` name no charset.

## Decoding

`decode` never fails: each ill-formed sequence becomes one U+FFFD where the encoding's decoder algorithm says, and decoding continues. `decodeStrict` is the same algorithm in WHATWG's fatal mode: the first ill-formed sequence is the result, with the input offset at which the decoder found it.

```scala
import kyo.*
import kyo.charset.Charset

val bad = Span.from(Array[Byte](0x61, 0xff.toByte, 0x62))

Charset.Utf8.decode(bad)         // "a�b"
Charset.Utf8.decodeStrict(bad)   // Result.fail(Charset.Malformed(Charset.Utf8, 1))

val good = Span.from(Array[Byte](0xe2.toByte, 0x82.toByte, 0xac.toByte))
Charset.Utf8.decodeStrict(good)  // Result.succeed("€")
```

Each call decodes one whole unit of text (a MIME part, an encoded word, a buffered body) from the encoding's initial state, so a stateful encoding such as ISO-2022-JP or UTF-7 is decoded correctly without the caller holding any state, and one `Charset` serves any number of concurrent decodes.

Byte order marks follow the standards: UTF-8 removes a leading one; bare `Utf16` and `Utf32` read one to select the byte order and remove it, defaulting to big-endian; `Utf16Be`, `Utf16Le`, `Utf32Be` and `Utf32Le` keep a leading U+FEFF as content. `Charset.sniff` reads a mark without decoding, for a caller that has no label at all, and answers the charset that removes it (`Utf8` or `Utf16`), so sniffing and decoding compose into the WHATWG "decode" of marked input:

```scala
import kyo.*
import kyo.charset.Charset

val marked = Span.from(Array[Byte](0xff.toByte, 0xfe.toByte, 0x61, 0x00))

Charset.sniff(marked)                       // Present(Charset.Utf16)
Charset.sniff(marked).map(_.decode(marked)) // Present("a")
```

## What is not here

- **Encoding.** Only decoding is provided. Text kyo modules send is UTF-8, which every platform encodes.
- **Streaming.** A decode is one call over a complete unit. A chunked body is decoded once it is buffered.
- **Protocol label policy.** Mail reads `UTF-16` and the ISO-2022 labels differently from the web, and strips an RFC 2231 language suffix (`US-ASCII*EN`); that layer belongs to the protocol module, over `resolve`.

## Licence of the tables

The index tables are generated from the Encoding Standard's index files, which the WHATWG licenses under CC BY 4.0, with source-code incorporations under the BSD 3-Clause License. The artifact carries the copyright notice, the conditions and the disclaimer in `META-INF/kyo-charset/NOTICE`, and each generated table's header repeats them. The files themselves are checked in under `kyo-charset/data/whatwg-encoding/` with their SHA-256 in `MANIFEST`, which the build verifies before generating anything.
