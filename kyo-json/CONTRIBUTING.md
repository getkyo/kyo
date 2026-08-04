# kyo-json contributor guide

This file documents the design contracts, invariants, and conventions specific
to `kyo-json`. Read the root [CONTRIBUTING.md](../CONTRIBUTING.md) first;
everything there applies here, and this file extends it with module-local rules.

---

## Why this module exists at all

`kyo-json` holds one object, `Jsonl`, and it is a separate published artifact
rather than a few more methods on `kyo-schema-json`. That split is deliberate
and load-bearing.

The `kyo-schema-*` family is effect-free at compile scope. `kyo-schema` depends
only on `kyo-data`; each format module adds only the core. Nothing in the family
sees `kyo-kernel`, `kyo-prelude`, or `kyo-core`, which is what makes the family
adoptable as a standalone serialization library with no Kyo effect runtime on
the classpath.

`Jsonl` needs `Sync`, `Async`, `Scope`, `Stream`, `Pipe`, `Poll`, and `Path`.
Putting it in `kyo-schema-json` would put `kyo-core` on the classpath of every
consumer of the JSON codec. `kyo-test-snapshot` is a real such consumer: it
depends on `kyo-schema` and all six format modules and has no path to `kyo-core`
(`build.sbt`, the `kyo-test-snapshot` block). Folding the effectful surface into
the codec module would give it, and every project like it, a transitive
dependency on the effect runtime for a feature it does not use.

So the split runs along the effect boundary, not along the format boundary:

| Half | Home | Depends on |
|---|---|---|
| Pure framing and whole-input decode/encode (`Json.Lines`) | `kyo-schema-json` | `kyo-schema`, `kyo-data` |
| Effectful drivers (`Jsonl`) | `kyo-json` | `kyo-schema-json`, `kyo-core` |

**Do not move `Jsonl` into `kyo-schema-json`, and do not add an effectful entry
point to any `kyo-schema-*` module.**

---

## The central invariant: all parsing lives in `Json.Lines.Framer`

Every byte of framing and every JSON parse in this module happens inside
`Json.Lines.Framer` and `Json.Lines.decodeRecord`, both in `kyo-schema-json`.
`Jsonl` contains no newline scanning, no line splitting, no byte-order-mark
handling, no CRLF handling, no blank-line skipping, and no JSON parsing.

Exactly one function in this module feeds the framer, the private `frameChunk`.
Every public entry point routes its bytes through it. The only other framer
calls are `Framer.init` at the top of each driver and `Framer.finish` at end of
input in `pipe` and `pipeResults`, which the follow drivers cannot make because
a follow stream has no end of input.

- `pipe` and `pipeResults` drive it from a `Poll[Chunk[Byte]]` loop, carrying the
  framer as the loop's state.
- `follow` and `followResults` drive it as the step function of
  `Path.follow`, carrying the framer as that loop's state.
- `follow` is `followResults` plus a `flatMapChunk` that splits at the first
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
what lets the follow drivers use it as `Path.follow`'s step function at all: the
framer becomes state the follow loop owns and can discard on a rewind, rather
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

## `follow` defaults to `Origin.Start`; `tailBytes` defaults to `Origin.End`

Both defaults are deliberate and they must not be made to agree.

`Path.tailBytes` is the byte-level primitive `Path.tail` is built on, and
`Path.tail` has always been follow-only: it emits lines appended after the
stream attached, and nothing that was already in the file. `tailBytes` defaults
to `Origin.End` to preserve that contract for its caller.

`Jsonl.follow` defaults to `Origin.Start`. Reading a live agent transcript or an
application log wants every record, not only those written after the reader
attached, and a JSONL consumer that silently skipped the existing file would be
surprising in a way a `tail` command is not. `Origin.End` remains available for
the follow-only case, and `Origin.Offset` for a consumer resuming from a
recorded position.

`Jsonl.follow` is a sibling driver over `Path.follow`, not a layer on top of
`Path.tailBytes`, so the two defaults are independent choices rather than one
overriding the other.

### What "following" means here

Following tracks the OPEN FILE, not the name. This is `tail -f`, never
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
belongs in `kyo-core` if it belongs anywhere, and it is a different contract.

The rewind is the case that constrains this module's design. `Path.follow`
restores the step's INITIAL state at a rewind, and the framer is that state, so a
record left half written when the truncation happened is dropped along with the
rest of the old content and can never be spliced onto the first replayed record.
This is why the framer is the follow loop's carried state and not a pipe
downstream of it: a downstream pipe would keep its residual across a rewind it
cannot see. **If you refactor `followResults`, the framer must stay inside the
loop.**

---

## `maxLineBytes` and the one unrecoverable failure

`maxLineBytes` (default `Json.Lines.DefaultMaxLineBytes`, 16 MiB) exists because
a byte stream containing no newline would otherwise grow the framer's residual
without bound. `maxDepth` and `maxCollectionSize` do not cover this: both
operate inside a single document, and a stream with no record boundary never
produces a document to apply them to. A follow stream on an attacker-influenced
or malfunctioning writer is exactly the shape that hits it.

A breach is the one framing failure with nothing to skip to, since no record
boundary was found for the pending bytes. That drives the error contract on both
tiers, and the asymmetry is intentional:

| Surface | Undecodable record | `maxLineBytes` breach |
|---|---|---|
| `pipe`, `read`, `follow` (strict) | emit every value decoded before it, then abort | emit every value framed before it, then abort |
| `pipeResults`, `readResults`, `followResults` (lenient) | one failure element, stream carries on | emit the records framed before it, then the failure element, then END the stream |

The lenient surfaces recover from a bad record because there is a next record to
resume at. They cannot recover from a breach, and staying open would leave a
consumer waiting on a stream that can never emit again, so they end instead.
`endAtBreach` does that truncation one layer out from `Path.follow`, because
that loop's step is pure and a pure step cannot end a stream. The composition
can: truncating the emitted stream leaves the framer inside the loop untouched,
so the rewind that rebuilds it is unaffected, and discarding the continuation is
what puts the unusable post-breach framer out of reach.

`Framing.Halted` deliberately carries no framer, so there is no value to feed
again by mistake. Keep it that way.

A `LimitExceededException` arriving at `endAtBreach` can only be the framer's,
because `Json.Lines.decodeRecord` wraps EVERY decode failure in a
`RecordDecodeException`, so a record's own limit breach can never look like a
framing breach. That wrapping is a contract of `kyo-schema-json`; if it changes,
`breachIndex` here breaks silently.

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
  break every such call site with a type mismatch. Nine dependent modules
  contain them.

Given-propagation itself would work identically with `json` first; the position
is about not breaking existing explicit-argument call sites. Every entry point in
this module carries `Json` in its `using` clause. Do not reorder it, and do not
replace it with a `summon[Json]` inside a method body. See `Json.scala:36-47`
for the full rationale on the source.

---

## Bounded memory on both directions

Nothing in this module buffers a whole stream, in either direction, and every
entry point must keep that property.

Reading: the framer holds at most one partial record, capped by `maxLineBytes`.
Records complete within a chunk are emitted and dropped.

Writing: `writeAll` folds the value stream chunk by chunk. Each chunk goes
through `Json.Lines.encodeAllBytes`, which sizes its output array once and
copies each encoded value and its newline into it exactly once, and is written
straight out. What is held at any moment is one chunk of encoded bytes,
regardless of how many values the stream has.

Three details of `writeAll` are contracts rather than incidental:

- **One handle across the whole fold**, so a stream of any length costs one open
  and one close rather than one of each per chunk. `kyo-core` exposes no `Path`
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
is already handled by `Path` and `Stream` in `kyo-core`, so a change here that
needs a `jvm/` or `js/` source file is a sign the change belongs in `kyo-core`
instead.

---

## Testing conventions

`JsonlTest` is the single test file, matching the single source file. Its
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

Follow-mode tests must be deterministic: drive them with latches and controlled
`Clock` advances rather than sleeps, per the root guide.

---

## Scaladoc bar

Every public entry point carries scaladoc stating what it does AND the contract
a caller can be surprised by: which failures abort versus surface as elements,
where a stream ends, what a rewind does to carried state, what the `Origin`
default is. Those are the properties this module is about; a comment that only
restates the signature is below the bar.
