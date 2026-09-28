# Contributing to kyo-mime

Module-specific guide for kyo-mime. Read the repository-root [CONTRIBUTING.md](../CONTRIBUTING.md) first: it carries the conventions, naming rules, type vocabulary and test patterns that apply across all of Kyo. This document records only what is specific to kyo-mime: the invariant every change must keep, why an encoded value is returned undecoded, where the two writing styles differ, the boundary rules, and what a change must not pull in.

## What kyo-mime is

kyo-mime reads and writes the header grammars mail and HTTP share: `MediaType` (RFC 2045 section 5.1, RFC 9110 section 8.3.1), `Disposition` (RFC 2183, RFC 6266), `Parameters` (RFC 2231, RFC 8187) and `Multipart` (RFC 2046 section 5.1.1). Its public surface is package `kyo.mime`, reached with `import kyo.mime.*` (the way kyo-net and kyo-crypto keep their packages): the four objects, the failure types `MimeException` and its three leaves, and `MimeException.Violation`. The lexical rules they share (tokens, quoted strings, CFWS, comments, attribute characters) are `kyo.internal.mime.Grammar`. The module depends on kyo-schema (for the `Schema` of `MediaType` and `Disposition`) and nothing else; every operation is a pure function.

## The invariant: read everything the RFCs allow, write nothing that can break a line

Reading is total over the grammar: any way the RFCs let a parameter be written (a quoted string with quoted pairs, a comment between any two tokens, `name*N` continuations in any order, `name*N*` encoded segments with a `charset'language'` prefix on segment 0, a parameter with no `;` before it) reads to the same value, and a header a reader cannot make sense of fails with a `Violation` that names the offending token, never the header. Writing never emits a quote, a CR or an LF unescaped: a value that is not printable ASCII is percent-encoded in both styles, and `MimeException.Violation.UnwritableParameterName` refuses the one name no writer can produce, a name holding `*`. `ParametersTest` and `DispositionTest` ("never lets a quote, CR or LF through unescaped, in either style") pin both halves; a change that makes a hostile value reach the line unescaped is a defect however the tests are then adjusted.

## An encoded value is returned undecoded

`Parameters.read` returns an RFC 2231 encoded value as `Value.Encoded(charset, language, octets)`, the `%XX` escapes decoded to octets and nothing more. The charset a caller accepts is protocol policy: RFC 8187 allows UTF-8 only and a browser ignores any other label, while mail allows any charset and kyo-email resolves the label through kyo-charset. Decoding here would either bind kyo-mime to kyo-charset (300 KB of tables in every kyo-http bundle) or hard-code the HTTP policy for mail. So `decodeUtf8` is the default `decode` of `readDecoded`, `MediaType.parse` and `Disposition.parse`, and a protocol module passes its own. `decode` takes the whole `Value`, plain text included, because a protocol's plain values can carry a further encoding of their own (mail clients write RFC 2047 encoded words inside a quoted file name) and that reading belongs to the same policy function; the default leaves plain text as it is. Do not add a charset dependency, and do not make `MediaType` carry an `Encoded` value: the parsed types hold text, and the caller's `decode` is where the octets become text.

The `unencoded` function is the other half of the same rule: an unencoded segment merged into an encoded value (`name*0*=iso-8859-1''caf%E9; name*1=" au lait"`) contributes its text as octets in the value's charset, which only the caller knows. UTF-8 is the default, through `kyo.internal.mime.Utf8` rather than the platform's `getBytes`, which writes an unpaired surrogate as `?` on the JVM, Scala.js and Scala Native alike (measured 2026-09-28: `61 3F 2E 70 64 66` for `a\ud800.pdf` on all three). A `?` is indistinguishable from one the text held; U+FFFD (EF BF BD) is the replacement character a reader recognizes. `Utf8Test` pins it.

## Two styles, one grammar

`Style.Mime` writes RFC 2231 for a header that will be folded: a unit is at most 76 octets, a long printable value becomes quoted continuations, a non-ASCII or control value becomes `%XX` segments, and neither a quoted pair nor a `%XX` is cut across units. `Style.Http` writes RFC 8187 for a header that will not be folded: one `name*=UTF-8''...` unit with no cap, and no continuations, which HTTP does not define. A value holding `=?` is percent-encoded in the MIME style so that a mail reader can never take it for an RFC 2047 encoded word. The style choice changes only how a value is split and escaped; the reader accepts both forms whatever the style that wrote them, and every writing test reads its output back.

## The boundary rules

`Multipart` is functions over `Span[Byte]`, not a parser: kyo-email's nested MIME tree and kyo-http's streaming form-data reader both keep their parsers and apply these rules. A delimiter is `--boundary` at the start of a line only (offset 0 or right after an LF, with or without a CR before it); the same bytes mid-line are data. `isDelimiterAt` matches by prefix, so a body line `--p10` holds a delimiter for the boundary `p1`; that is why `Multipart.boundary` takes every number a digit run starts with, not only the whole run. `boundary` is deterministic, choosing the least `<prefix><k>` no line of an enclosed part starts with: a writer needs no randomness, and the same parts always get the same boundary, which makes a written message reproducible. A part is given as pieces so that a writer holding its output in chunks never concatenates them to scan; every part starts at a line start, since the writer puts a delimiter line before it.

## Failures

`MimeException` is sealed with three leaves, one per parsed type plus one for writing, and a shared `Violation` enum, so a caller matches on what is wrong (`NotWellFormed`, `NotAToken`, `DuplicateParameter`, `UnwritableParameterName`) rather than on a message. A message goes through `Grammar.printable`: controls and non-ASCII escaped, cut at 200 characters, and it names the token, never the whole header value. `parse`, `init`, `write` and `render` return the failure in a `Result`; only `apply` panics.

## Decision checklist

- [ ] Does every reading change keep `read` total, and every writing change keep a quote, CR and LF off the line?
- [ ] Is an encoded value still returned undecoded, with no charset dependency added?
- [ ] Does a written value read back equal through `Parameters.read` in the test?
- [ ] Do the delimiter functions still match at a line start only, and does `boundary` still take every prefix number?
- [ ] Does a new failure carry a `Violation` and name a token, not a header?
- [ ] Do `scalafmtAll` on every platform and `kyo-mimeJVM/doc` pass, and do the JS, Native and Wasm suites run green?
