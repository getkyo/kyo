# kyo-mime

The header grammars mail and HTTP share, as values: a `Content-Type` as a `MediaType`, a `Content-Disposition` as a `Disposition`, the `name=value` parameters of either as `Parameters`, and the boundary rules of a multipart body as `Multipart`. Reading follows RFC 2045, RFC 2231 and RFC 8187 in full, so a file name written as `filename*=UTF-8''r%C3%A9sum%C3%A9.pdf`, as `filename*0*=...; filename*1*=...`, or with a comment between the tokens reads as the same value; writing never lets a quote, a CR or an LF reach the header line.

The module depends on kyo-schema only and has no effect dependency: every operation is a pure function, one that can fail returns a `Result`, and a value that exists has passed its checks.

```scala
import kyo.*
import kyo.mime.*

val disposition = Disposition.init("attachment", "filename" -> "€ rates.csv")

// For an HTTP response header: RFC 8187, one unit, the browser decodes it
disposition.flatMap(_.render) // Result.succeed("attachment; filename*=UTF-8''%E2%82%AC%20rates.csv")

// For a mail header that will be folded: RFC 2231, units of at most 76 octets
disposition.flatMap(_.render(Parameters.Style.Mime)) // Result.succeed("attachment; filename*=utf-8''%E2%82%AC%20rates.csv")

Disposition.parse("attachment; filename=\"EURO rates\"; filename*=utf-8''%e2%82%ac%20rates").map(_.filename)
// Result.succeed(Present("€ rates")): filename* wins over filename (RFC 6266)
```

## Media types

`MediaType.parse` reads a `Content-Type` value: the type and subtype are lowercased, parameter names are lowercased, values keep their case, CFWS and comments anywhere between the tokens are removed, a quoted string has its quoted pairs resolved, and RFC 2231 continuations are merged. A parameter written twice keeps its first value, so a reader and a writer agree on which one counts. Anything that is not a type, a subtype and parameters is not a media type, and the failure names what is wrong without repeating the header.

```scala
import kyo.*
import kyo.mime.*

MediaType.parse("Text/HTML; Charset=\"utf-8\" (the charset)").map(m => (m.baseType, m.charset))
// Result.succeed(("text/html", Present("utf-8")))

MediaType.parse("multipart/form-data; boundary=\"----x \"").map(_.parameter("boundary"))
// Result.succeed(Present("----x")): a boundary never ends in white space (RFC 2046)

MediaType.parse("text").map(_.baseType)
// Result.fail(MimeInvalidMediaTypeException(NotWellFormed("text")))

MediaType.init("application", "json").flatMap(_.render) // Result.succeed("application/json")
```

`MediaType.init` builds a media type from its parts and returns the violation for a part that is not a token or a name given twice; `Disposition.init` does the same for a disposition. Both types have a `Schema`, decoding through the same checks, so a media type in a JSON configuration is validated where it is read.

## Parameters and RFC 2231 encoded values

`Parameters.read` is the parameter grammar on its own, for a header that is neither a `Content-Type` nor a `Content-Disposition`, or for a caller that needs an encoded value before it is decoded. An encoded value comes back as `Parameters.Value.Encoded` with its charset label, its language tag and its octets, undecoded: RFC 8187 allows only UTF-8 and a browser ignores the rest, while mail allows any charset. Which charsets are accepted is the caller's policy, and `Parameters.decodeUtf8` is the HTTP one, used by default by `readDecoded`, `MediaType.parse` and `Disposition.parse`. The policy function sees every value, plain ones included, so a protocol whose plain values can carry a further encoding (mail, where a client writes `filename="=?utf-8?B?...?="`) decodes those in the same place.

```scala
import kyo.*
import kyo.mime.*
import kyo.mime.Parameters.Value

Parameters.readDecoded("; title*=UTF-8''%c2%a3%20and%20%e2%82%ac%20rates", 0)
// Chunk("title" -> "£ and € rates")

Parameters.read("; title*=iso-8859-1'en'%A3%20rates", 0).map {
    case (name, Value.Encoded(charset, language, octets)) => s"$name: $charset $language, ${octets.size} octets"
    case (name, Value.Text(text))                         => s"$name: $text"
}
// Chunk("title: Present(iso-8859-1) Present(en), 7 octets"):
// the caller decodes the octets with the charset it accepts
```

A mail reader passes its own `decode` to `MediaType.parse` and `Disposition.parse`, resolving the label through its charset table (kyo-charset's `Charset.resolve` in kyo-email) and falling back where the label is unknown; kyo-mime itself never decodes a charset other than UTF-8.

## Writing

`Parameters.write` gives the units of one parameter in a `Parameters.Style`. A value that is a token is written bare, a printable ASCII value as a quoted string, and any other value percent-encoded. `Style.Http` writes one `name*=UTF-8''...` unit with no length cap, as RFC 8187 has it. `Style.Mime` writes RFC 2231 units of at most 76 octets: quoted continuations `name*0="..."; name*1="..."` for a long printable value, `name*0*=utf-8''...; name*1*=...` otherwise, never cutting a quoted pair or a `%XX` across units, so each unit folds onto a line of its own.

```scala
import kyo.*
import kyo.mime.*

Parameters.write("filename", "a b", Parameters.Style.Http)             // Result.succeed(Chunk("filename=\"a b\""))
Parameters.write("filename", "€", Parameters.Style.Http)               // Result.succeed(Chunk("filename*=UTF-8''%E2%82%AC"))
Parameters.write("name", "a" * 100, Parameters.Style.Mime).map(_.size) // Result.succeed(2)
Parameters.write("name*0", "x", Parameters.Style.Http) // Result.fail(MimeInvalidParameterException(UnwritableParameterName("name*0")))
```

A hostile value such as `a"\r\nX-Injected: yes` is percent-encoded in both styles, so a file name from a request can never end the header line early.

## Form data

A multipart/form-data part header follows the HTML standard, not RFC 6266: a browser quotes every value, writes LF, CR and `"` as `%0A`, `%0D` and `%22`, and escapes nothing else, so a Windows path keeps its backslashes and there is no `filename*`. `Style.FormData` writes that encoding, and `Disposition.parseFormData` reads it: exactly `form-data; name="..."`, optionally `; filename="..."`, with only those three escapes decoded and a CR or LF inside a value refused.

```scala
import kyo.*
import kyo.mime.*

Disposition.init("form-data", "name" -> "upload", "filename" -> "C:\\docs\\\"q3\".pdf").flatMap(_.render(Parameters.Style.FormData))
// Result.succeed("form-data; name=\"upload\"; filename=\"C:\\docs\\%22q3%22.pdf\"")

Disposition.parseFormData("form-data; name=\"upload\"; filename=\"C:\\docs\\%22q3%22.pdf\"").map(_.filename)
// Result.succeed(Present("C:\\docs\\\"q3\".pdf"))
```

## Multipart boundaries

`Multipart` holds the boundary rules of RFC 2046 section 5.1.1 as functions over bytes, for a parser or a writer to apply. A delimiter is `--` and the boundary at the start of a line, at offset 0 or right after an LF; the same bytes mid-line are data, which keeps a part that quotes `--boundary` whole. The line end before a delimiter belongs to the delimiter, and what follows the boundary on its line is transport padding or `--` for the close delimiter.

```scala
import kyo.*
import kyo.mime.*

val boundary = Span.from("b".getBytes("UTF-8"))
val body     = Span.from("--b\r\nhello --b there\r\n--b--".getBytes("UTF-8"))

val first  = Multipart.findDelimiter(body, boundary, 0)         // 0
val second = Multipart.findDelimiter(body, boundary, first + 1) // 22: the mid-line "--b" is data
val start  = Multipart.delimiterLineEnd(body, boundary, first)  // 5: the part's first byte
val end    = Multipart.partEndBefore(body, second)              // 20: the CRLF before a delimiter is not part of the part
Multipart.isCloseDelimiter(body, boundary, second) // true

Multipart.isValidBoundary("simple boundary") // true: 1 to 70 bchars, not ending in a space
Multipart.boundary("=_kyo0_", Chunk(Chunk(Span.from("--=_kyo0_0\r\n".getBytes("UTF-8")))))
// "=_kyo0_1": the least number no line of an enclosed part starts with, so a writer needs no randomness
```

## What is not here

- **A multipart parser or writer.** kyo-email keeps its nested MIME tree, kyo-http its streaming form-data reader; each applies these rules.
- **Charset decoding.** An RFC 2231 value's charset label is returned, not resolved; kyo-charset resolves labels, and the protocol module decides which it accepts.
- **RFC 2047 encoded words.** `=?utf-8?Q?...?=` is a mail header phrase, not a parameter value; a value holding `=?` is percent-encoded here so that no encoded word is ever written into a parameter.
