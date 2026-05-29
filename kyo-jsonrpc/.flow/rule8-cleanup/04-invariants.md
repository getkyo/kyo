# 04 Invariants: Rule 8 (Organization) cleanup in kyo-jsonrpc

Task type: refactor
Mode: cross-phase invariant ledger (plan-time)

This refactor reorganizes file layout and relocates a small set of
framework-internal sub-symbols inside `kyo-jsonrpc/shared/src/`. The
public API surface is preserved verbatim, except for one INTERNAL
relocation: `kyo.JsonRpcRequest` (the case class) moves to
`kyo.internal.JsonRpcRequest` because no in-tree source references it
(02-design.md §Package surface verdicts, Rejected alternatives entry 7).
Cross-phase invariants are therefore structural (file layout, symbol
presence, marker coverage, wire-format stability) rather than runtime
concurrency invariants.

Phase numbering follows the plan (05-plan.md):

- Phase 1: 8a relocations. Sub-symbol moves of `IdStrategy.mkNextId`
  into `kyo.internal.IdStrategyEngine`, and
  `JsonRpcCodec.cdpReservedKeys` into `kyo.internal.JsonRpcCodecImpl`.
  Add `// flow-allow: PUBLIC <rationale>` top-of-file markers to the
  PUBLIC files. Add `// flow-allow:` rationales to surviving
  `private[kyo]` declarations per the 02-design.md §8a table.
- Phase 2: 8b split. Extract `JsonRpcResponse` from
  `kyo/JsonRpcRequest.scala` into a new `kyo/JsonRpcResponse.scala`
  (PUBLIC). Relocate the `JsonRpcRequest` case class to a new
  `kyo/internal/JsonRpcRequest.scala` (INTERNAL). The pre-split
  `kyo/JsonRpcRequest.scala` is dissolved. Pair the new PUBLIC file
  with `kyo/JsonRpcResponseTest.scala` in the same phase commit per
  Rule 8c HARD.
- Phase 3: 8c orphan relocations. Rename `kyo/Test.scala` to
  `kyo/JsonRpcTestBase.scala` (class `Test` to `JsonRpcTestBase`,
  update 11 spec `extends` clauses); move `ScenarioBidiTest`,
  `ScenarioHttpStyleTest`, `ScenarioWsStyleTest` and `MaxInFlightTest`
  under `kyo/scenario/` with the renames specified in 02-design.md.
- Phase 4: 8c missing tests created. Add the 8 new focused test files
  enumerated in 02-design.md §8c (`JsonRpcErrorTest`, `MessageGateTest`,
  `IdStrategyTest`, `JsonRpcRequestTest`, `JsonRpcEnvelopeTest`,
  `JsonRpcIdTest`, `HandlerCtxTest`, `ExtrasEncoderTest`). The
  `JsonRpcResponseTest` already shipped in Phase 2 paired with its
  source per Rule 8c HARD.

If the planner later splits Phase 4 into multiple per-file sub-phases,
INV-006 is replicated per sub-phase (each new test file is its own
producer of "compiles and passes on JVM/JS/Native for that file").

## Invariants

INV-001: Public API surface is preserved. Every public symbol enumerated
in `01-exploration.md` §Citations index keeps the same fully-qualified
name, package, signature, and visibility after the refactor. Users
importing `kyo.IdStrategy`, `kyo.JsonRpcCodec`, `kyo.JsonRpcResponse`,
`kyo.HandlerCtx`, `kyo.JsonRpcEndpoint`, `kyo.JsonRpcMethod`,
`kyo.UnknownMethodPolicy`, `kyo.JsonRpcEnvelope`, `kyo.JsonRpcError`,
`kyo.JsonRpcId`, `kyo.JsonRpcTransport`, `kyo.MessageGate`,
`kyo.CancellationPolicy`, `kyo.ProgressPolicy`, `kyo.ExtrasEncoder`
continue to resolve through their original FQNs. The single expected
FQN change is `kyo.JsonRpcRequest` to `kyo.internal.JsonRpcRequest`
(INTERNAL verdict; explicitly excluded from the public enumeration
because grep against `kyo-jsonrpc/shared/src/` finds zero in-tree users).
  produced_by: Phase 1
  consumed_by: Phase 2, Phase 3, Phase 4
  smoke_test_path: kyo-jsonrpc/shared/src/test/scala/kyo/JsonRpcEndpointTest.scala::compiles-against-original-imports (verified by `sbt kyo-jsonrpcJVM/Test/compile` exiting 0)

INV-002: Sub-symbol relocations leave no dangling references. After
Phase 1, no source under `kyo-jsonrpc/shared/src/main/scala/kyo/`
references `IdStrategy.mkNextId` or `JsonRpcCodec.cdpReservedKeys` at
their pre-move FQNs. The sole call site at
`internal/JsonRpcEndpointImpl.scala:735` and the consumer sites in
`internal/JsonRpcCodecImpl.scala:151, 172` resolve to the new locations
(`IdStrategyEngine.mkNextId` and a local `cdpReservedKeys` val on the
impl object).
  produced_by: Phase 1
  consumed_by: Phase 2, Phase 3, Phase 4
  smoke_test_path: kyo-jsonrpc/shared/src/main/scala/kyo/internal/JsonRpcEndpointImpl.scala::IdStrategyEngine.mkNextId-call-site (verified by `sbt kyo-jsonrpcJVM/compile` exiting 0)

INV-003: `private[kyo]` rationale coverage. Every `private[kyo]`
declaration that remains in a `kyo/*.scala` file after Phase 1 carries
a `// flow-allow:` rationale on the same or immediately preceding line,
citing either Hub.scala:22 (smart-constructor pattern) or Stream.scala:48
(sealed-protocol with framework-only abstract members) as the precedent.
The expected set matches the 02-design.md §Package surface verdicts
narrative verbatim: HandlerCtx ctor + forTest, JsonRpcEndpoint primary
ctor + Pending ctor, JsonRpcMethod sealed-trait abstract members
(schemaIn / schemaOut / handle), UnknownMethodPolicy ctor, JsonRpcResponse
ctor (post-split, carried into the new file in Phase 2).
  produced_by: Phase 1
  consumed_by: Phase 2, Phase 3, Phase 4
  smoke_test_path: kyo-jsonrpc/shared/src/main/scala/kyo/HandlerCtx.scala::flow-allow-rationales (verified by `flow-verify-grep.sh --catalog organization-8a --target kyo-jsonrpc/shared/src/main/scala/kyo/` exiting 0)

INV-004: Wire-format and Schema derivation stability for the split
types. After Phase 2, `Schema[kyo.internal.JsonRpcRequest]` and
`Schema[JsonRpcResponse]` still derive successfully, and JSON round-trip
through `Json.encode` / `Json.decode` produces byte-equivalent output
to the pre-split baseline for the same inputs (notification shape with
`id` Absent, success shape, failure shape). The JsonRpcRequest case
class continues to carry `derives Schema, CanEqual` at its new internal
location; the JsonRpcResponse case class continues to carry the same
derivation at its new public file.
  produced_by: Phase 2
  consumed_by: Phase 3, Phase 4
  smoke_test_path: kyo-jsonrpc/shared/src/test/scala/kyo/JsonRpcResponseTest.scala::schema-roundtrip (the Phase 2 focused test absorbs the existing JsonRpcCodecTest.scala:185-213 response-roundtrip cases; verified by `sbt kyo-jsonrpcJVM/test` exiting 0)

INV-005: Scenario relocations preserve test semantics. After Phase 3,
the four relocated specs (`scenario.BidiTest`, `scenario.HttpStyleTest`,
`scenario.WsStyleTest`, `scenario.MaxInFlightTest`) still execute the
same assertions through the same code paths. The shared base
`JsonRpcTestBase` resolves for all 11 specs that previously extended
`Test` plus the Phase 2 `JsonRpcResponseTest`. No spec is dropped,
skipped, or marked pending.
  produced_by: Phase 3
  consumed_by: Phase 4
  smoke_test_path: kyo-jsonrpc/shared/src/test/scala/kyo/scenario/MaxInFlightTest.scala::run (verified by `sbt kyo-jsonrpcJVM/test` exiting 0 with the same test count as pre-Phase-3 baseline plus any new tests added in earlier phases)

INV-006: New 8c test files compile and pass on JVM, JS, and Native.
After Phase 4, all 8 new test files added in Phase 4 (`JsonRpcErrorTest`,
`MessageGateTest`, `IdStrategyTest`, `JsonRpcRequestTest`,
`JsonRpcEnvelopeTest`, `JsonRpcIdTest`, `HandlerCtxTest`,
`ExtrasEncoderTest`) plus the Phase 2 `JsonRpcResponseTest` compile and
pass across all three platforms. Every `kyo/*.scala` source has a
matching `kyo/<basename>Test.scala` in `shared/src/test/scala/kyo/`,
and every test file either matches a source basename or lives under
`kyo/scenario/`.
  produced_by: Phase 4
  consumed_by: (none yet)
  smoke_test_path: kyo-jsonrpc/shared/src/test/scala/kyo/JsonRpcErrorTest.scala::rfc-code-constants (verified by `sbt kyo-jsonrpcJVM/test kyo-jsonrpcJS/test kyo-jsonrpcNative/test` exiting 0 and `flow-verify-grep.sh --catalog organization-8c --target kyo-jsonrpc/shared/src/` exiting 0)

INV-007: PUBLIC marker coverage on every PUBLIC file. After Phase 1,
every `kyo-jsonrpc/shared/src/main/scala/kyo/*.scala` file (excluding
`kyo/internal/`) that the 02-design.md §Package surface verdicts table
classifies as PUBLIC carries a top-of-file `// flow-allow: PUBLIC
<rationale>` marker. The expected count at the Phase 1 boundary is 12
PUBLIC markers across the 12 PUBLIC files extant after Phase 1
(CancellationPolicy, ExtrasEncoder, HandlerCtx, JsonRpcEndpoint,
JsonRpcEnvelope, JsonRpcError, JsonRpcId, JsonRpcMethod,
JsonRpcTransport, MessageGate, ProgressPolicy, UnknownMethodPolicy).
The SPLIT files IdStrategy and JsonRpcCodec also carry PUBLIC markers
on their public-half heads after Phase 1. After Phase 2 produces
`kyo/JsonRpcResponse.scala` (PUBLIC), the total reaches 13 PUBLIC files
with markers; the pre-split `kyo/JsonRpcRequest.scala` is dissolved in
Phase 2 and contributes no marker post-split. The relocated
`kyo/internal/JsonRpcRequest.scala` is INTERNAL and carries no PUBLIC
marker.
  produced_by: Phase 1
  consumed_by: Phase 2, Phase 3, Phase 4
  smoke_test_path: kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcEndpoint.scala::flow-allow-PUBLIC-marker (verified by `flow-verify-grep.sh --catalog organization-8a --target kyo-jsonrpc/shared/src/main/scala/kyo/` exiting 0 with the PUBLIC marker count matching 12 at the Phase 1 boundary and 13 after Phase 2)

INV-008: One top-level type per file (Rule 8b structural). After
Phase 2, every `kyo-jsonrpc/shared/src/main/scala/kyo/**/*.scala` file
holds exactly one top-level `class`, `trait`, `enum`, `object`, or
`case class` plus an optional companion of the same name. The pre-split
`kyo/JsonRpcRequest.scala` (which held two top-level case classes,
JsonRpcRequest at line 10 and JsonRpcResponse at line 16) is dissolved;
`kyo/JsonRpcResponse.scala` (PUBLIC) holds one type plus its companion,
and `kyo/internal/JsonRpcRequest.scala` (INTERNAL) holds one type plus
its companion.
  produced_by: Phase 2
  consumed_by: Phase 3, Phase 4
  smoke_test_path: kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcResponse.scala::one-type-per-file (verified by `flow-verify-grep.sh --catalog organization-8b --target kyo-jsonrpc/shared/src/main/scala/kyo/` exiting 0)

INV-009: Existing tests continue to compile and pass on all three
platforms at every phase boundary. The refactor preserves observable
behavior; no existing test regresses on JVM, JS, or Native at any phase
commit. The producer set lists every phase because the suite is
re-verified at each boundary; the consumer set is the next phase
(whichever phase reads the prior phase's still-green suite to schedule
its own edits).
  produced_by: Phase 1, Phase 2, Phase 3, Phase 4
  consumed_by: (none yet)
  smoke_test_path: kyo-jsonrpc/shared/src/test/scala/kyo/JsonRpcEndpointTest.scala::full-suite-green (verified by `sbt kyo-jsonrpcJVM/test kyo-jsonrpcJS/test kyo-jsonrpcNative/test` exiting 0 at the end of every phase)

## Per-phase invariant declarations

Phase 1 (8a relocations, PUBLIC markers, flow-allow rationales):
  produces: INV-001, INV-002, INV-003, INV-007, INV-009
  consumes: (none; first phase)

Phase 2 (8b split JsonRpcRequest into internal + JsonRpcResponse public):
  produces: INV-004, INV-008, INV-009
  consumes: INV-001, INV-002, INV-003, INV-007

Phase 3 (8c orphan relocations and shared-base rename):
  produces: INV-005, INV-009
  consumes: INV-001, INV-002, INV-003, INV-004, INV-007, INV-008

Phase 4 (8c missing tests created):
  produces: INV-006, INV-009
  consumes: INV-001, INV-002, INV-003, INV-004, INV-005, INV-007, INV-008

## Orphan check

Every `consumed_by` reference resolves to a producer in this ledger:

- INV-001 consumed by Phase 2, 3, 4; produced by Phase 1. OK.
- INV-002 consumed by Phase 2, 3, 4; produced by Phase 1. OK.
- INV-003 consumed by Phase 2, 3, 4; produced by Phase 1. OK.
- INV-004 consumed by Phase 3, 4; produced by Phase 2. OK.
- INV-005 consumed by Phase 4; produced by Phase 3. OK.
- INV-006 consumed by (none yet); produced by Phase 4. OK (terminal
  invariant; the suite-green and 8c-gate exit codes are the final
  campaign acceptance signal, no later phase consumes them).
- INV-007 consumed by Phase 2, 3, 4; produced by Phase 1. OK.
- INV-008 consumed by Phase 3, 4; produced by Phase 2. OK.
- INV-009 consumed by (none yet); produced by Phase 1, 2, 3, 4. OK
  (re-verified at every phase boundary; no downstream phase reads a
  named "INV-009 was last green at phase N" claim, the green sbt exit
  IS the signal).

No orphans. No cycles (every producer phase number is strictly less
than every consumer phase number for the same invariant; INV-009's
per-phase production is a re-attestation pattern, not a self-cycle).

## Phases explicitly marked as no-invariant

None. Every phase in the plan both produces and (after Phase 1)
consumes at least one structural invariant, because the refactor is a
single connected dependency chain: relocations and PUBLIC marker
attestation (Phase 1) underpin the split (Phase 2), which underpins
the orphan relocations and shared-base rename (Phase 3), which
underpins the new test files (Phase 4). No phase is mechanically
independent of the others.
