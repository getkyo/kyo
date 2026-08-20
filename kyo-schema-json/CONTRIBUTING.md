# kyo-schema-json contributor guide

This file documents the design contracts, invariants, and conventions specific
to `kyo-schema-json`. Read the root [CONTRIBUTING.md](../CONTRIBUTING.md) first;
everything there applies here, and this file extends it with module-local rules.

---

## Module boundary

`kyo-schema-json` owns the complete JSON surface. `Json` handles one whole JSON
value, `Json.Lines` provides pure JSONL framing and whole-input operations, and
`Jsonl` drives that same framing over streams and files.

The format-agnostic `kyo-schema` core remains effect-free and depends only on
`kyo-data`. This format module also depends on `kyo-system`, which brings the
effect runtime and cross-platform file API needed by `Jsonl`. Consumers that
need JSON therefore get one artifact and one coherent set of serialization
rules instead of choosing between separate codec and streaming artifacts.

The boundary inside the module is strict:

| Surface | Role |
|---|---|
| `Json` | Whole-value JSON text and bytes, plus JSON Schema generation |
| `Json.Lines` | Pure framing and whole-input JSONL decode and encode |
| `Jsonl` | Effectful byte-stream and file drivers over `Json.Lines` |

New framing and parsing rules belong in `JsonLines.scala`. `Jsonl.scala` owns
only the effectful drivers that feed bytes to that pure implementation.

---

## The central `Jsonl` invariant: all parsing delegates to `Json.Lines`

Every byte of framing and every JSON parse driven by `Jsonl` happens inside
`Json.Lines.Framer` and `Json.Lines.decodeRecord`.
`Jsonl` contains no newline scanning, no line splitting, no byte-order-mark
handling, no CRLF handling, no blank-line skipping, and no JSON parsing.

Exactly one function in `Jsonl` feeds the framer, the private `frameChunk`.
Every public `Jsonl` entry point routes its bytes through it. The only other
framer calls in `Jsonl` are `Framer.init` at the top of each driver and
`Framer.finishLine` at end of input in `pipe` and `pipeResults`, which the watch
drivers cannot make because a watch stream has no end of input.

- `pipe` and `pipeResults` drive it from a `Poll[Chunk[Byte]]` loop, carrying the
  framer as the loop's state.
- `watch` and `watchResults` drive it as the step function of
  `Path.watch`, carrying the framer as that loop's state.
- `watch` is `watchResults` plus a `flatMapChunk` that splits at the first
  failure; it has no framing of its own.
- `read` and `readResults` are `path.readBytesStream.into(pipe...)`.
- `encode`, `write`, and `append` all reduce to `Json.Lines.encodeAllBytes` per
  chunk. `write` and `append` share one private `writeAll` and differ only in a
  boolean.

**Adding parsing logic here is the failure mode this structure exists to
prevent.** Two framers with slightly different rules would mean a file that
decodes one way through `Jsonl.read` and another way through
`Json.Lines.decodeAll`, and the drift would show up only on the inputs nobody
writes a test for: a byte order mark split across a read boundary, a `\r\n`
terminator arriving one byte at a time, a blank line at a chunk edge. If a
framing rule needs to change, change it in `JsonLines.scala` and let both
surfaces inherit it.

The same rule applies in the other direction. `frameChunk` is pure, and that is
what lets the watch drivers use it as `Path.watch`'s step function at all: the
framer becomes state the watch loop owns and can discard on a rewind, rather
than state a downstream pipe holds and knows nothing about. Do not make
`frameChunk` effectful.

---

## Why framing is byte-level, not text-level

A UTF-8 character can occupy up to four bytes, and a chunk boundary can fall in
the middle of one. Decoding each chunk to `String` and then splitting on `'\n'`
would corrupt any multibyte character that straddles a read boundary, or would
require an incremental text decoder carrying partial code units across chunks.

Splitting bytes on `'\n'` first and decoding each complete record afterwards
removes the hazard entirely: a record's bytes are identical no matter how the
input was chunked, so `new String(bytes, UTF_8)` at the record level is always
handed a complete sequence.

Splitting on a raw `'\n'` byte is exact rather than approximate here, because
JSON requires control characters inside strings to be escaped, so a valid record
cannot contain an unescaped newline. That is what makes a whole-input JSON
reader sufficient and an incremental JSON parser unnecessary.

`JsonlTest` pins this with the same input fed at chunk sizes 4096, 3, and 1, and
asserts all three produce identical output. Keep that test shape for any change
to the framing path: one chunk size alone cannot catch a boundary regression.

Two related properties follow from the same reasoning and are also tested:

- What a strict surface emits before it aborts is a function of the data alone,
  never of the upstream chunk size. `pipe` emits every value decoded before the
  bad record and only then aborts; folding a chunk to one `Result` and aborting
  before emitting would make a 1000-record input failing at record 500 deliver
  499 values when fed a byte at a time and none when fed in one chunk.
- Encoding terminates each record with a newline, never each chunk, so the bytes
  out are a function of the values alone.

---

## The byte order mark is consumed, not framed

Several Windows editors and export tools write the UTF-8 byte order mark,
`EF BB BF`, at the head of a text file to declare its encoding. It belongs to no
record, so the framer consumes it before framing begins. Left in place it would
prefix the first record, and the mark is not JSON whitespace, so that record
would fail to parse.

Consuming it does not hide it from the positions the framer reports: a record's
`byteOffset` counts every byte of the input, the mark included, so an offset
still addresses the record in the original source.

The mark is recognized ACROSS SPAN BOUNDARIES, which is the part a refactor can
break. A framer starts in a start state, and while the bytes seen so far are
still a proper prefix of the mark it holds them and stays in that state rather
than guessing. A proper prefix holds no newline, so nothing is withheld by
waiting. That is what makes a one-byte-at-a-time feed strip the mark exactly as
a whole-input feed does, and it is why `Framer.atStart` exists at all rather
than the mark being stripped once at the head of the first span.

---

## `watch` defaults to `Origin.Start`; `tailBytes` defaults to `Origin.End`

Both defaults are deliberate and they must not be made to agree.

`Path.tailBytes` is the byte-level view of a watched file, and new-content-only
is what a byte-level tail means: it emits bytes appended after the stream attached,
and nothing that was already in the file. `Path.tail` is its sibling over one
private polling loop, not its caller, and passes `Origin.End` explicitly, so
`tailBytes`'s default answers only for `tailBytes`.

`Jsonl.watch` defaults to `Origin.Start`. Reading a live agent transcript or an
application log wants every record, not only those written after the reader
attached, and a JSONL consumer that silently skipped the existing file would be
surprising in a way a `tail` command is not. `Origin.End` remains available for
the new-content-only case, and `Origin.Offset` for a consumer resuming from a
recorded position.

`Jsonl.watch` is a sibling driver over `Path.watch`, not a layer on top of
`Path.tailBytes`, so the two defaults are independent choices rather than one
overriding the other.

### What "watching" means here

Watching tracks the OPEN FILE, not the name. This is `tail -f`, never
`tail -F`. The file is opened once and every later decision comes from that open
handle, so the name it was opened under can afterwards be renamed, replaced, or
deleted with no effect on the stream:

- **Rename**: the stream stays on the original file. It emits whatever is still
  appended through that file's new name, and nothing written to a new file
  created under the old name.
- **Delete**: no further records and no failure. An unlinked file that is still
  open keeps its content and can still be measured, so the stream simply waits.
- **Truncate in place**: this is the same file, so the loop rewinds to byte 0 and
  replays it.

The rename and delete rows are POSIX descriptor behavior. Windows keeps the
directory entry alive until the last handle closes and refuses the operation
outright for some open modes, so those two outcomes are undefined there, which is
why `PathTest`'s five file-identity tests carry
`assume(!Platform.isWindows, ...)`. Truncation in place is unaffected. Any new
test for these outcomes needs the same guard.

A consumer that needs to pick up a rotated-in replacement closes the stream and
opens a new one against the path. Do not add name-watching to this module; it
belongs in `kyo-system` if it belongs anywhere, and it is a different contract.

The rewind is the case that constrains this module's design. `Path.watch`
restores the step's INITIAL state at a rewind, and the framer is that state, so a
record left half written when the truncation happened is dropped along with the
rest of the old content and can never be spliced onto the first replayed record.
This is why the framer is the watch loop's carried state and not a pipe
downstream of it: a downstream pipe would keep its residual across a rewind it
cannot see. **If you refactor `watchResults`, the framer must stay inside the
loop.**

---

## `maxLineSize` and the one unrecoverable failure

`maxLineSize` (default `Json.Lines.DefaultMaxLineSize`, 16 MiB) exists because
a byte stream containing no newline would otherwise grow the framer's residual
without bound. `maxDepth` and `maxCollectionSize` do not cover this: both
operate inside a single document, and a stream with no record boundary never
produces a document to apply them to. A watch stream on an attacker-influenced
or malfunctioning writer is exactly the shape that hits it.

A breach means one of two different things, and the framer's own type says
which. A terminated line over the ceiling has a known boundary, so framing skips
it and resumes after its newline: that is a `Result.Failure` among the lines of a
`Json.Lines.Framed.Continued`, and it reaches this module as one failure element
sitting where the record sat. Pending bytes that outgrow the ceiling with no
newline among them have nothing to skip to: that is `Json.Lines.Framed.Halted`,
which carries no framer, and it is the one unrecoverable framing failure. That
drives the error contract on both tiers, and the asymmetry is intentional:

| Surface | Undecodable record | Terminated over-long record | Oversized residual |
|---|---|---|---|
| `pipe`, `read`, `watch` (strict) | emit every value decoded before it, then abort | emit every value framed before it, then abort | emit every value framed before it, then abort |
| `pipeResults`, `readResults`, `watchResults` (lenient) | one failure element, stream carries on | one failure element, stream carries on | emit the records framed before it, then the failure element, then END the stream |

The lenient surfaces recover wherever there is a next record to resume at, which
covers a bad record and a terminated over-long one alike. That equivalence is
the point on a watch stream: ending on a skippable breach would let one
over-long line stop a live log's reader permanently. They cannot recover from an
oversized residual, and staying open would leave a consumer waiting on a stream
that can never emit again, so they end instead.

`watchResults` ends by returning `Path.Step.Stop` from its watch step. `Stop`
carries no state, so the framer a halt leaves behind has nowhere to go, and the
loop stops rather than polling bytes no framer can consume. `Framing.Halted`
carries no framer for the same reason. Keep both that way.

`Json.Lines.decodeRecord` wraps EVERY decode failure in a
`RecordDecodeException`, so a record's own limit breach can never be mistaken
for a framing breach. That wrapping is a contract of `kyo-schema-json`; the
strict surfaces' `Abort.fail(breach)` path depends on it staying true.

---

## `Json` is a trailing `using` parameter, and that matters

`Json.encode` and `Json.encodeBytes` take `using json: Json` as their LAST
`using` parameter, after `Schema[A]` and `Frame`, and summon nothing internally.
Two separate decisions are packed into that, with two separate reasons, and
neither is "so a caller's given propagates by position":

- **It is a parameter rather than a `summon[Json]` in the body.** A plain
  `summon` inside an `inline def`'s body is resolved once, when the method is
  type-checked, not fresh at each call site after inlining. An explicit `using`
  parameter is resolved per call, which is what makes a caller-scoped `Json`
  given actually reach the encoder.
- **It sits last rather than first.** Call sites across the codebase already
  supply `schema` explicitly as `Json.encode(v)(using someSchema)`, relying on
  the rule that unsupplied `using` parameters must form a trailing suffix.
  Putting `json` first would shift that existing explicit argument onto it and
  break every such call site with a type mismatch. Four modules contain them:
  `kyo-ai` (3), `kyo-http` (9), `kyo-mcp` (1), and `kyo-schema-tests` (15,
  test-only).

Given-propagation itself would work identically with `json` first; the position
is about not breaking existing explicit-argument call sites. Every entry point in
this module carries `Json` in its `using` clause. Do not reorder it, and do not
replace it with a `summon[Json]` inside a method body. See `Json.scala:36-47`
for the full rationale on the source.

---

## Bounded memory on both directions

Nothing in this module buffers a whole stream, in either direction, and every
entry point must keep that property.

Reading: the framer holds at most one partial record, capped by `maxLineSize`.
Records complete within a chunk are emitted and dropped.

Writing: `writeAll` folds the value stream chunk by chunk. Each chunk goes
through `Json.Lines.encodeAllBytes`, which sizes its output array once and
copies each encoded value and its newline into it exactly once, and is written
straight out. What is held at any moment is one chunk of encoded bytes,
regardless of how many values the stream has.

Three details of `writeAll` are contracts rather than incidental:

- **One handle across the whole fold**, so a stream of any length costs one open
  and one close rather than one of each per chunk. `kyo-system` exposes no `Path`
  stream sink, which is why this is hand-rolled over `Path.Unsafe.openWrite`
  instead of composing an existing combinator.
- **The fold runs under `Abort.run[Any]` INSIDE the bracket**, not outside it.
  `Sync.acquireReleaseWith` releases when its body completes or panics but not
  when it aborts, and a stream's own values can abort for reasons unrelated to
  the file. Reifying every outcome into a `Result` first leaves the bracket a
  computation that always completes; the outcome is re-raised after the close.
  Moving that `Abort.run` outward leaks the file handle on an aborting stream.
- **The open happens before the fold and carries no bytes**, which is what makes
  the file's existence, and for `write` its emptiness, a fact about the call
  rather than about whether the stream produced anything. Writing an empty
  stream empties the file; appending an empty stream still creates it.

A failure part way through leaves the records already written on disk rather
than removing the file. A prefix of a JSONL file is a valid JSONL file, so a
consumer reads what completed instead of losing all of it to the record that did
not. Do not add rollback.

---

## `append` splices onto an unterminated last record

`append` writes at the file's current end and emits no separator of its own, so
appending to a file whose last record has no trailing newline joins the new
first record onto the old last one, producing one corrupt line. `pipe`
deliberately accepts an unterminated final record, so `Jsonl.read` reads such a
file without complaint, which is what makes this a real trap rather than an
obvious one.

Everything this module writes ends in a newline, so it only fires on externally
produced or truncated files. This is documented on `append` and must stay
documented there; do not "fix" it by having `append` probe the file's last byte,
which costs a read on every call and races another writer.

---

## Cross-platform stance

The module is cross-platform (JVM, JS, Native, Wasm) with all source and tests
in `shared/`. There is no platform-specific source. Everything platform-varying
is already handled by `Path` in `kyo-system` and `Stream` in `kyo-core`. A
`Jsonl` change that needs platform-specific source is a sign the behavior
belongs in one of those lower modules.

---

## Testing conventions

`JsonlTest` matches `Jsonl.scala` and keeps its effectful cases separate from
the pure `JsonTest` and `JsonLinesTest` suites. Its
helpers pin the things that silently pass otherwise, and new tests should use
them:

- `assertRecordFailure(result, index)` pins the exception TYPE and the failing
  record's index, not merely that something failed. A failure attributed to the
  wrong record cannot pass.
- `assertLimitBreach(result, limit, maximum)` pins WHICH limit was applied and
  the value it was applied with. `maxDepth` and `maxCollectionSize` are both
  `Int`, so a transposed argument compiles; this is what catches it.
- `byteStream(s, chunkSize)` parameterizes the chunk size. Any test of framing,
  of an abort prefix, or of encoding output should run at more than one chunk
  size, for the reason given above.

Watch-mode tests must be deterministic: drive them with latches and controlled
`Clock` advances rather than sleeps, per the root guide.

---

## Scaladoc bar

Every public entry point carries scaladoc stating what it does AND the contract
a caller can be surprised by: which failures abort versus surface as elements,
where a stream ends, what a rewind does to carried state, what the `Origin`
default is. Those are the properties this module is about; a comment that only
restates the signature is below the bar.
