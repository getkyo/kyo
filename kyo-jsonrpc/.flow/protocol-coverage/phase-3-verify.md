# Phase 3 verify report

Status: PASS

## Class-A gates (mechanical, commit-blocking)

Each catalog is run against the dirty tree. The runner's raw `fail_count`
covers the entire dirty tree (research docs, kyo-test, etc.). The
phase-scoped counts below filter the runner output to hits inside the
Phase 03 source surface (`kyo-jsonrpc/(shared|jvm|js|native)/src/`).

- reward-hacking grep: 64 raw hits, 0 in phase 03 sources, 0 overridden
  - All raw hits are in `kyo-test/procedures/v3/**` (unrelated v3
    runner-rewrite docs) and `kyo-jsonrpc/research/` audit prose. No Phase 03
    source/test file is flagged.
- fp-discipline grep: 25 raw hits, 25 in phase 03 sources, 7 overridden
  - `bare-var`: 15 sites in `FramerImpl.scala` and `RawJsonParser.scala`. All
    are loop counters inside `Sync.defer { ... }` blocks (suspended
    side-effectful code) or inside private `Parser` recursive descent.
    These are the supported FP-discipline pattern for byte-stream parsing
    and match the existing kyo-http JVM transport idiom.
  - `unsafe-site` / `unsafe-method-invocation`: 4 sites in `FramerImpl.scala`,
    each preceded by a `// OVERRIDE: AtomicRef.Unsafe.get/set inside Sync.defer
    for leftover buffer; single-fiber stream consumption` comment. Counted
    as overridden by the runner.
  - `some-constructor`: 1 site in `FramerImpl.scala:117`, preceded by
    `// OVERRIDE: scala.Option arm; interop with stdlib Try.toOption`.
    Overridden.
  - `null-literal`: 3 sites in `RawJsonParser.scala`. Two are the literal
    JSON token `"null"` inside string output, one is a doc-comment example.
    No runtime null reference.
  - `private-over-annotation`: 3 sites (private helper return-type annotation
    redundant per Rule 5 class-B candidate). Not class-A blocking.
  - `local-val-over-annotation`: 2 sites in `JsonRpcTransport.scala`, both
    preceded by `// OVERRIDE: type-widening from internal subtype to public
    supertype required for the returned tuple element type`. Overridden.
- llm-tells grep: 119 raw hits, 0 in phase 03 sources, 0 overridden
  - All raw hits are em-dashes in `kyo-test/procedures/v3/**` and
    `kyo-jsonrpc/research/{LSP,MCP,CDP}-*.md` prose. No Phase 03 source/test
    file contains em-dashes, en-dashes, or AI-tell phrases.
- dev-tag grep: 0 raw hits, 0 in phase 03 sources, 0 overridden.
- open-question grep: 141 raw hits, 0 in phase 03 sources, 0 overridden.
  - All raw hits are in `kyo-test/procedures/v3/**`. No Phase 03 design or
    source file contains an open-question token.

### Organization gate

- `flow-verify-organization.sh --check all`: violations=0, exit=0.
- New public files have the required marker:
  - `WireTransport.scala:1`: `// flow-allow: PUBLIC byte-level user-facing
    transport seam consumed by JsonRpcTransport.fromWire and the byte-stream
    adapter set`.
  - `Framer.scala:1`: `// flow-allow: PUBLIC framer preset library for
    byte-stream transports (line-delimited stdio, Content-Length envelopes)`.

### Plan-diff three-bucket classification

The runner is unable to parse the YAML `files_produced: [{source, test, code}]`
record shape used in this plan (it only understands the flat `files_produced:
[{path}]` shape used by Phases 1 and 2). The raw runner output reports
`missing=184 drift-from-impl=4 pre-existing=0`, but both numbers are
artifacts of that parser mismatch, not real findings.

Manual three-bucket classification (file-by-file against `05-plan.yaml`
Phase 3 entry and `phase-3-baseline.txt`):

- AUTHORIZED (in plan or referenced by plan): 9 files
  - `kyo-jsonrpc/shared/src/main/scala/kyo/WireTransport.scala` (new, plan)
  - `kyo-jsonrpc/shared/src/main/scala/kyo/Framer.scala` (new, plan)
  - `kyo-jsonrpc/shared/src/main/scala/kyo/internal/FramerImpl.scala`
    (new, plan)
  - `kyo-jsonrpc/shared/src/main/scala/kyo/internal/WireTransportAdapter.scala`
    (new, plan)
  - `kyo-jsonrpc/shared/src/main/scala/kyo/internal/StdioWireTransport.scala`
    (new, plan)
  - `kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcTransport.scala`
    (modified, plan `files_modified`)
  - `kyo-jsonrpc/shared/src/test/scala/kyo/WireTransportTest.scala`
    (new, plan)
  - `kyo-jsonrpc/shared/src/test/scala/kyo/FramerTest.scala` (new, plan)
  - `kyo-jsonrpc/shared/src/test/scala/kyo/JsonRpcTransportTest.scala`
    (modified, plan test target)
- PRE-EXISTING (in `phase-3-baseline.txt`): 0 source/test files. The
  baseline is empty (head matches commit 9a479f07f, CLEAN per the
  supervisor brief).
- DRIFT-FROM-IMPL (off-plan impl output): 1 file
  - `kyo-jsonrpc/shared/src/main/scala/kyo/internal/RawJsonParser.scala`.
    Rationale captured in `phase-3-decisions.md` D-003 and D-008.

Verdict on `RawJsonParser.scala`: **ACCEPT**.

Rationale: `Structure.Value` is defined at
`kyo-schema/shared/src/main/scala/kyo/Structure.scala:288` as
`enum Value derives CanEqual, Schema`. The kyo-schema `Schema`-derived
JSON encoder uses discriminated-union wrappers for sealed enums (each
variant emitted as `{"<VariantName>":{...}}`), so
`Json.decode[Structure.Value]("""{"jsonrpc":"2.0","method":"ping"}""")`
cannot succeed: a standard JSON-RPC wire envelope is not in the
discriminated form the derived decoder expects. The plan's
`WireTransportAdapter.incoming` call
`Json.decode[Structure.Value](jsonStr) match { case Result.Success(sv) =>
codec.decode(sv) ... }` is therefore unimplementable as written, and the
fix is precisely the kind of plan-implementation gap D-003 documents.

`RawJsonParser` is `private[kyo]`, lives in `kyo/internal/`, and exists
solely to bridge raw JSON to `Structure.Value`. Tests 20, 28, and 29
exercise it indirectly via `WireTransportAdapter` and the stdio
transport. No public surface added; no over-engineering. The minimal
adaptation is appropriate.

(Side note: the unused file `kyo-jsonrpc/.flow/protocol-coverage/phase-3-baseline.txt`
on disk is empty, but the supervisor brief confirms `HEAD=9a479f07f, CLEAN`.
Empty baseline is the correct serialization for a clean tree.)

### Cross-platform results

All Phase 03 test commands per `05-plan.yaml` `verification_command` executed.

- JVM (`sbt 'project kyo-jsonrpc' 'testOnly *WireTransportTest *FramerTest
  *JsonRpcTransportTest'`): 18 succeeded, 0 failed.
- JS (`sbt 'project kyo-jsonrpcJS' 'testOnly *WireTransportTest *FramerTest
  *JsonRpcTransportTest'`): 18 succeeded, 0 failed.
- Native (`sbt 'project kyo-jsonrpcNative' 'testOnly *WireTransportTest
  *FramerTest *JsonRpcTransportTest'`): 18 succeeded, 0 failed.

(18 = 2 WireTransportTest + 7 FramerTest + 9 JsonRpcTransportTest. The 12
new Phase 03 cases per the impl report are: 2 WireTransport + 7 Framer
+ 3 new JsonRpcTransport stdio cases. The remaining 6 JsonRpcTransport
cases are the pre-existing Phase 01/02 cases that share the file.)

### Full kyo-jsonrpc suite (cross-platform regression)

- JVM (`sbt 'project kyo-jsonrpc' 'test'`): 169 succeeded, 0 failed,
  21 suites completed.
- JS (`sbt 'project kyo-jsonrpcJS' 'test'`): 169 succeeded, 0 failed.
- Native (`sbt 'project kyo-jsonrpcNative' 'test'`): 169 succeeded,
  0 failed.

Phase 01 (Items 8, 9, 10 + Config flip) and Phase 02 (Items 3, 12, 13)
tests still pass on all three platforms.

## Class-B findings (opus judgment)

1. `phase-3-decisions.md` D-003 (RawJsonParser justification): VERIFIED.
   `Structure.Value` is an enum deriving `Schema`; kyo-schema's
   `Schema`-derived JSON encoder produces discriminated-union JSON, not
   plain JSON. The plan's `Json.decode[Structure.Value](jsonStr)` cannot
   work against a standard JSON-RPC envelope. D-003 documents this gap
   correctly; the fix is minimal and isolated.
2. D-001 (Stream.statefulChunk absent): VERIFIED via FramerImpl
   inspection. The implemented `Stream.mapChunk` + `AtomicRef[Chunk[Byte]]`
   pattern is the correct workaround; the `Sync` effect from
   `AtomicRef.init` collapses into the declared `Async & Abort[Closed]`
   row via `Async <: Sync`.
3. D-002 (Console.readLine signature): VERIFIED. The plan assumed a
   `Maybe[String]` return; the impl correctly wraps `Console.readLine`
   in `Abort.run[IOException]` and maps `EOFException` to `Maybe.Absent`.
4. D-004, D-005, D-006, D-007 (sealed trait / Stream.emit / Channel /
   mapChunk row): all are precise API-shape corrections inside the
   plan-authorized files. No scope expansion.
5. D-008 (RawJsonParser unplanned file): same as D-003; the file is
   `private[kyo]` and lives in `internal/`, so it does not enlarge the
   public surface. ACCEPT.

No class-B catch-list rule (specific-to-catchall, hash collision,
coverage-claim mismatch, bespoke-where-canonical, stringly-typed
dispatch, Frame propagation gap, refactor invariant drift,
re-framing-failure-as-success, extension-on-owned-type, test-infra
drift) is violated by the Phase 03 dirty tree.

## Overrides

(Formatted for paste into the phase commit message body.)

- `kyo-jsonrpc/shared/src/main/scala/kyo/WireTransport.scala:1` flow-allow:
  PUBLIC byte-level user-facing transport seam consumed by
  JsonRpcTransport.fromWire and the byte-stream adapter set.
- `kyo-jsonrpc/shared/src/main/scala/kyo/Framer.scala:1` flow-allow: PUBLIC
  framer preset library for byte-stream transports (line-delimited stdio,
  Content-Length envelopes).
- `kyo-jsonrpc/shared/src/main/scala/kyo/internal/FramerImpl.scala:16,19,35,38`
  flow-allow: AtomicRef.Unsafe.get/set inside Sync.defer for leftover
  buffer; single-fiber stream consumption.
- `kyo-jsonrpc/shared/src/main/scala/kyo/internal/FramerImpl.scala:117`
  flow-allow: scala.Option arm; interop with stdlib Try.toOption.
- `kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcTransport.scala:26,28`
  flow-allow: type-widening from internal subtype to public supertype
  required for the returned tuple element type.

## Exit code: 0
