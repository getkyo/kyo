# sweep-naming.md

Topic: naming consistency across the new public + internal surface introduced
in Phase 01-05 of the protocol-coverage campaign.

Scope: every file newly created or modified in commits b07967942 (Phase 01)
through 00a562bb0 (Phase 05), under `kyo-jsonrpc/` and `kyo-jsonrpc-http/`.
Pre-existing surface (Phase 5/6/7 of the original campaign) is treated as the
baseline; references to it appear only when a NEW symbol must match it.

Read-only sweep. Findings are PASS / WARN with file:line anchors.

---

## 1. Protocol-name leakage in NEW code

The driving concern: any occurrence of `Mcp`, `Lsp`, `Cdp`, `mcp`, `lsp`,
`cdp`, `partialResult`, `_meta`, `sessionId`, `progressToken` in Phase 01-05
NEW source counts as a layering breach.

Scanned added-file set:

- `kyo-jsonrpc/shared/src/main/scala/kyo/WireTransport.scala`
- `kyo-jsonrpc/shared/src/main/scala/kyo/Framer.scala`
- `kyo-jsonrpc/shared/src/main/scala/kyo/internal/FramerImpl.scala`
- `kyo-jsonrpc/shared/src/main/scala/kyo/internal/StdioWireTransport.scala`
- `kyo-jsonrpc/shared/src/main/scala/kyo/internal/WireTransportAdapter.scala`
- `kyo-jsonrpc/shared/src/main/scala/kyo/internal/RawJsonParser.scala`
- `kyo-jsonrpc/jvm/src/main/scala/kyo/JsonRpcTransportJvm.scala`
- `kyo-jsonrpc/jvm/src/main/scala/kyo/internal/UdsWireTransport.scala`
- `kyo-jsonrpc-http/src/main/scala/kyo/JsonRpcHttpTransport.scala`

Result: **0 hits in any newly created file.**

Scanned added-line set (Phase 01-05 diffs against pre-Phase-01 baseline) for
modifications to pre-existing files: 0 protocol-name introductions.

Pre-existing hits remain (informational only, NOT findings of this sweep):

- `kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcCodec.scala:16` ; `Cdp` preset
- `kyo-jsonrpc/shared/src/main/scala/kyo/CancellationPolicy.scala:58,67` ;
  `lsp` / `mcp` presets
- `kyo-jsonrpc/shared/src/main/scala/kyo/ProgressPolicy.scala:38,48,50,52,54,55,56,59`
  ; `lsp` / `mcp` presets and `progressToken` / `_meta` string literals
  inside `ProgressPolicy.mcp` preset
- `kyo-jsonrpc/shared/src/main/scala/kyo/UnknownMethodPolicy.scala:4,24` ;
  `lsp` preset
- `kyo-jsonrpc/shared/src/main/scala/kyo/internal/JsonRpcCodecImpl.scala:120,243`
  ; `Cdp` codec impl
- `kyo-jsonrpc/shared/src/main/scala/kyo/internal/JsonRpcEndpointImpl.scala:374,454`
  ; error-message text referencing `ProgressPolicy.lsp / .mcp`

These all sit in protocol-preset slots which are the design contract for
protocol-agnostic engines (the engine consumes a `Policy` typeclass; the
preset is a user-facing alias). They do not introduce engine coupling.

Verdict: **PASS.** Engine remains protocol-agnostic post Phase 01-05.

---

## 2. Companion conventions

Existing pattern: every public `trait Foo` has a companion `object Foo:` that
holds preset `val`s (e.g. `JsonRpcCodec.Strict2_0`, `CancellationPolicy.lsp`,
`MessageGate.alwaysOpen`).

NEW companions checked:

- `WireTransport` ; `object WireTransport:` with preset `val empty`
  (`kyo-jsonrpc/shared/src/main/scala/kyo/WireTransport.scala:12-18`).
  Pattern match: PASS.

- `Framer` ; `object Framer:` with presets `val lineDelimited`,
  `val contentLength`
  (`kyo-jsonrpc/shared/src/main/scala/kyo/Framer.scala:12-37`).
  Pattern match: PASS.

- `JsonRpcTransport` (modified, not new) ; companion already exists, Phase 03
  added factory `def fromWire` and `def stdio`, Phase 02 added `def inMemory`
  (`kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcTransport.scala:12-54`).
  Pattern match: PASS.

NEW companion-less extension objects:

- `JsonRpcTransportJvm` ; `object JsonRpcTransportJvm:` carrying `unixDomain`
  factory plus an `extension (self: JsonRpcTransport.type)` block that
  hoists `unixDomain` onto `JsonRpcTransport.type`
  (`kyo-jsonrpc/jvm/src/main/scala/kyo/JsonRpcTransportJvm.scala:10-46`).
  This makes both `JsonRpcTransportJvm.unixDomain(path)` and
  `JsonRpcTransport.unixDomain(path)` legal on JVM.
  Convention: novel in the module; no existing precedent. Reasonable choice
  for platform-conditional surface that augments a shared companion.
  Verdict: PASS, but see WARN-N1 below for the asymmetric kyo-jsonrpc-http
  case.

- `JsonRpcHttpTransport` ; `object JsonRpcHttpTransport:` carrying `webSocket`
  factory ONLY; no `extension (self: JsonRpcTransport.type)` block
  (`kyo-jsonrpc-http/src/main/scala/kyo/JsonRpcHttpTransport.scala:4-100`).

Verdict: **PASS** for kyo-jsonrpc shared/JVM surface; **WARN** for
kyo-jsonrpc-http asymmetry (see WARN-N1).

---

## 3. Method naming across transport adapters

Factory-method names introduced in Phase 02-05:

| Method        | Owner                                | Location                                                                  |
| ------------- | ------------------------------------ | ------------------------------------------------------------------------- |
| `inMemory`    | `JsonRpcTransport`                   | `kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcTransport.scala:18,31`      |
| `fromWire`    | `JsonRpcTransport`                   | `kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcTransport.scala:37`         |
| `stdio`       | `JsonRpcTransport`                   | `kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcTransport.scala:47`         |
| `unixDomain`  | `JsonRpcTransportJvm` + `JsonRpcTransport.type` ext. | `kyo-jsonrpc/jvm/src/main/scala/kyo/JsonRpcTransportJvm.scala:16,37-44`   |
| `webSocket`   | `JsonRpcHttpTransport`               | `kyo-jsonrpc-http/src/main/scala/kyo/JsonRpcHttpTransport.scala:6`        |

All use lowerCamelCase, all consistent with each other.

Parameter-order conventions:

- `fromWire(wire, framer, codec = JsonRpcCodec.Strict2_0)`
  (`kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcTransport.scala:37-41`)
- `stdio(codec = JsonRpcCodec.Strict2_0, framer = Framer.lineDelimited)`
  (`kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcTransport.scala:47-50`)
- `unixDomain(sockPath, codec = JsonRpcCodec.Strict2_0, framer = Framer.lineDelimited)`
  (`kyo-jsonrpc/jvm/src/main/scala/kyo/JsonRpcTransportJvm.scala:16-20`)
- `webSocket(url, headers = HttpHeaders.empty, codec = JsonRpcCodec.Strict2_0)`
  (`kyo-jsonrpc-http/src/main/scala/kyo/JsonRpcHttpTransport.scala:6-10`)

WARN-N2: codec parameter position is inconsistent. `fromWire` puts codec
last after required `(wire, framer)`. `stdio` and `unixDomain` put codec
first among optionals, then framer. `webSocket` puts codec last (no framer
applies, headers come first). The pattern is "codec is the last optional
unless framer is present, in which case framer is the last optional", and
this is followed by `stdio`/`unixDomain` but not by `fromWire` (where codec
trails both required positional args). Net effect: a user calling
`stdio(codec = X)` reads naturally; calling `fromWire(wire, framer, X)`
without a name is ambiguous in review. Low-severity; documented for
follow-up rather than rewrite.

Verdict: **PASS** on names, **WARN-N2** on parameter order.

---

## 4. Internal-type naming patterns

Existing internal-package convention (pre-Phase-01 baseline):

- `*Impl` for delegate objects that hold the logic of a public companion's
  preset `val`s. Example:
  `kyo-jsonrpc/shared/src/main/scala/kyo/internal/JsonRpcCodecImpl.scala:5`
  (`object JsonRpcCodecImpl`),
  `kyo-jsonrpc/shared/src/main/scala/kyo/internal/JsonRpcEndpointImpl.scala:725`
  (`object JsonRpcEndpointImpl`).
- `*Engine` for stateless logic modules driven from the endpoint. Examples:
  `CancellationEngine`, `ProgressEngine`, `RateLimitEngine`,
  `IdStrategyEngine`.
- Concrete-noun classes for in-memory / cross-wired adapters. Example:
  `InMemoryTransport`
  (`kyo-jsonrpc/shared/src/main/scala/kyo/internal/InMemoryTransport.scala:5`).

NEW internal types introduced in Phase 03-05:

| Symbol                  | Kind                                       | Location                                                                         |
| ----------------------- | ------------------------------------------ | -------------------------------------------------------------------------------- |
| `FramerImpl`            | `private[kyo] object`                      | `kyo-jsonrpc/shared/src/main/scala/kyo/internal/FramerImpl.scala:5`              |
| `RawJsonParser`         | `private[kyo] object`                      | `kyo-jsonrpc/shared/src/main/scala/kyo/internal/RawJsonParser.scala:23`          |
| `WireTransportAdapter`  | `final private[kyo] class`                 | `kyo-jsonrpc/shared/src/main/scala/kyo/internal/WireTransportAdapter.scala:5`    |
| `StdioWireTransport`    | `final private[kyo] class extends WireTransport` | `kyo-jsonrpc/shared/src/main/scala/kyo/internal/StdioWireTransport.scala:6`      |
| `UdsWireTransport`      | `final private[kyo] class extends WireTransport` | `kyo-jsonrpc/jvm/src/main/scala/kyo/internal/UdsWireTransport.scala:8`           |

Match-up against the existing pattern:

- `FramerImpl` ; delegate object for `Framer` companion's preset `val`s
  (`Framer.lineDelimited` calls `internal.FramerImpl.parseLineDelimited`,
  `Framer.contentLength` calls `internal.FramerImpl.parseContentLength`).
  Mirrors `JsonRpcCodec` / `JsonRpcCodecImpl`. PASS.

- `RawJsonParser` ; no public companion. It is a free utility object
  underneath `WireTransportAdapter`. The name does not follow the `*Impl` or
  `*Engine` patterns. Closest precedent in module: none — this is a new
  utility category. Name is descriptive and unambiguous; module convention
  did not pre-exist a "utility under internal" pattern.
  WARN-N3 (minor): could have been `JsonRawParser` for surname-prefix
  consistency with `JsonRpc*` family but `RawJsonParser` reads more
  naturally and is not consumer-facing. Accept as-is.

- `WireTransportAdapter` ; a concrete `JsonRpcTransport` impl that lifts a
  `WireTransport`+`Framer`+`JsonRpcCodec` triple. The `*Adapter` suffix is
  a new pattern in the module. Existing precedent is `InMemoryTransport`
  (concrete-noun class). PASS — `Adapter` clearly conveys the intent
  (translate one trait into another) and is preferred to a noun like
  `WireBackedTransport`.

- `StdioWireTransport` and `UdsWireTransport` ; concrete `WireTransport`
  implementations. Match `InMemoryTransport` (concrete-noun class) pattern,
  modulo the `Wire` qualifier denoting which trait they implement. PASS.

  WARN-N4: minor naming asymmetry. `InMemoryTransport` implements
  `JsonRpcTransport` without a `JsonRpc` qualifier in its own name; the new
  `*WireTransport` variants embed `Wire` as a disambiguator from
  `JsonRpcTransport`. Two equally-valid naming conventions in one
  internal package. Suggested next-cycle fix would be either renaming
  `InMemoryTransport` -> `InMemoryJsonRpcTransport` (consistent with
  embedding the implemented-trait suffix), OR leaving as-is and accepting
  that "kind of transport" prefix is conventional for `Wire`-flavored
  impls. Not actionable in this sweep.

Verdict: **PASS** with two low-severity WARNs (N3, N4).

---

## 5. Summary

| Section                        | Verdict |
| ------------------------------ | ------- |
| Protocol-name leakage          | PASS    |
| Companion conventions          | PASS (1 WARN: WARN-N1) |
| Factory method naming          | PASS (1 WARN: WARN-N2) |
| Internal-type naming           | PASS (2 WARNs: WARN-N3, WARN-N4) |

WARN count: 4 low-severity findings; zero blockers.

WARN list:

- WARN-N1: `JsonRpcHttpTransport`
  (`kyo-jsonrpc-http/src/main/scala/kyo/JsonRpcHttpTransport.scala:4-100`)
  does not mirror the `extension (self: JsonRpcTransport.type)` block that
  `JsonRpcTransportJvm`
  (`kyo-jsonrpc/jvm/src/main/scala/kyo/JsonRpcTransportJvm.scala:36-45`)
  uses. Asymmetric call ergonomics: `JsonRpcTransport.unixDomain(p)` works
  on JVM but `JsonRpcTransport.webSocket(url)` does NOT work even when
  kyo-jsonrpc-http is on the classpath; users must write
  `JsonRpcHttpTransport.webSocket(url)`. Either both should provide the
  extension or neither should. Recommend adding the extension to
  `JsonRpcHttpTransport`.

- WARN-N2: codec parameter position is inconsistent across factories.
  `fromWire(wire, framer, codec)`
  (`kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcTransport.scala:37-41`)
  puts codec at position 3 of a no-defaults-on-required signature.
  `stdio(codec, framer)`
  (`kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcTransport.scala:47-50`)
  and `unixDomain(sockPath, codec, framer)`
  (`kyo-jsonrpc/jvm/src/main/scala/kyo/JsonRpcTransportJvm.scala:16-20`)
  put codec first. Consider standardizing to "always codec last" or
  "always codec second", whichever fits the ergonomics goal.

- WARN-N3: `RawJsonParser`
  (`kyo-jsonrpc/shared/src/main/scala/kyo/internal/RawJsonParser.scala:23`)
  does not match either `*Impl` or `*Engine` naming convention. Acceptable
  as a utility-object category; flagged only because Phase 03 introduced a
  new naming bucket.

- WARN-N4: `*WireTransport` internal classes embed the implemented-trait
  qualifier
  (`kyo-jsonrpc/shared/src/main/scala/kyo/internal/StdioWireTransport.scala:6`,
  `kyo-jsonrpc/jvm/src/main/scala/kyo/internal/UdsWireTransport.scala:8`),
  while `InMemoryTransport`
  (`kyo-jsonrpc/shared/src/main/scala/kyo/internal/InMemoryTransport.scala:5`)
  does not. Two valid conventions co-exist in one internal package.
