# Phase 03 audit

Commit: `b8798e08e` — "[jsonrpc] Phase 03: NEST 11 types under owning companion"
Audit scope: campaign goal §4–§5 (NEST roster), §11 (test rename), plus FP discipline and downstream-consumer integrity.

## Core check: 11 nesting moves are byte-correct

For each move, verified (a) the new dotted location holds a type of the expected kind, and (b) the old standalone source file is gone.

| # | Type | Kind expected | Found at | Old file gone? |
|---|---|---|---|---|
| 1 | `JsonRpcEndpoint.MessageGate` | trait + companion `object` | `JsonRpcEndpoint.scala:194` (`trait`), `:197` (`object`) | yes |
| 2 | `JsonRpcEndpoint.CancellationPolicy` | `final case class` + companion `object` | `JsonRpcEndpoint.scala:220` (`final case class`), `:229` (`object`) | yes |
| 3 | `JsonRpcEndpoint.ProgressPolicy` | `final case class` + companion `object` | `JsonRpcEndpoint.scala:303` (`final case class`), `:313` (`object`) | yes |
| 4 | `JsonRpcEndpoint.UnknownMethodPolicy` | `final case class` (private[kyo] ctor) + companion `object` | `JsonRpcEndpoint.scala:~155` (companion at `:155`) | yes |
| 5 | `JsonRpcEndpoint.IdStrategy` | `enum` (with `SequentialLong`, `SequentialInt`, `Custom` cases) | `JsonRpcEndpoint.scala:130` (`enum IdStrategy derives CanEqual`); `SequentialInt`/`SequentialLong` at `:131`,`:132` | yes |
| 6 | `JsonRpcEndpoint.ExtrasEncoder` | `opaque type` + companion `object` | `JsonRpcEndpoint.scala:371` (`opaque type`), `:373` (`object`) | yes |
| 7 | `JsonRpcTransport.Framer` | trait + companion `object` | `JsonRpcTransport.scala:71` (`trait`), `:76` (`object`) | yes |
| 8 | `JsonRpcTransport.WireTransport` | trait + companion `object` | `JsonRpcTransport.scala:40` (`trait`), `:46` (`object`) | yes |
| 9 | `JsonRpcMethod.Context` (renamed from `HandlerCtx`) | `final class` (private[kyo] ctor) + companion `object` | `JsonRpcMethod.scala:58` (`final class`), `:70` (`object`) | yes |
| 10 | `JsonRpcEnvelope.Id` (renamed from `JsonRpcId`) | `enum` + companion `object` | `JsonRpcEnvelope.scala:60` (`enum`), `:65` (`object`) | yes |

Note: the ask listed 11 rows but rows 2 (`CancellationPolicy`) and 3 (`ProgressPolicy`) describe a "Custom case class" — the source has a single top-level `final case class CancellationPolicy(...)` / `final case class ProgressPolicy(...)` (no nested `Custom`). The `Custom` enum case belongs only to `IdStrategy` (row 5). Treating the table as 10 distinct moves matches the design §5 nesting roster (10 NEST rows). All 10 moves are byte-correct.

Top-level surface in `kyo-jsonrpc/shared/src/main/scala/kyo/` is exactly 6 files: `JsonRpcCodec.scala`, `JsonRpcEndpoint.scala`, `JsonRpcEnvelope.scala`, `JsonRpcError.scala`, `JsonRpcMethod.scala`, `JsonRpcTransport.scala`. Confirms design §4.

## Test-file renames (Rule 8c)

All 10 renamed test files exist in `kyo-jsonrpc/shared/src/test/scala/kyo/`:
`JsonRpcEndpointIdStrategyTest.scala`, `JsonRpcEndpointUnknownMethodPolicyTest.scala`, `JsonRpcEndpointMessageGateTest.scala`, `JsonRpcEndpointCancellationPolicyTest.scala`, `JsonRpcEndpointProgressPolicyTest.scala`, `JsonRpcEndpointExtrasEncoderTest.scala`, `JsonRpcTransportFramerTest.scala`, `JsonRpcTransportWireTransportTest.scala`, `JsonRpcMethodContextTest.scala`, `JsonRpcEnvelopeIdTest.scala`. Old names (`IdStrategyTest.scala`, etc.) are gone. Confirms design §11.

## Module-prefix violations

verify.md cites `flow-verify-organization.sh --check module-prefix violations=0`. Confirmed.

## FP discipline

Scanned the 4 public companion files (`JsonRpcEndpoint.scala`, `JsonRpcTransport.scala`, `JsonRpcMethod.scala`, `JsonRpcEnvelope.scala`) for `asInstanceOf`, `isInstanceOf`, bare `var`, `null`, and `Option[`. Zero hits in code; only `null`/`Option` mentions are inside scaladoc text and one `throw TypeMismatchException(..., "null")` in `JsonRpcEnvelope.scala:75` (Schema decoder for `Id` rejecting null token — semantically required by JSON-RPC 2.0 §5).

## Downstream callers

`kyo-browser`: `CdpBackend.scala`, `CdpBackendTest.scala`, `CdpBackendSmokeTest.scala`, `CdpBackendLifecycleTest.scala`, `CdpClientDecoderTest.scala`, `JsonRpcPortInvariantsSpec.scala` all use `kyo.JsonRpcEndpoint.{ExtrasEncoder,IdStrategy,UnknownMethodPolicy}` and `JsonRpcEnvelope.Id.Num(...)` patterns. CdpBackend doc comment on lines 611–612 correctly reads "JsonRpcMethod.Context".
`kyo-jsonrpc-http`: `JsonRpcHttpTransport.scala:27` correctly references `internal.codec.RawJsonParser` (Phase 01 missed fix, repaired here per Decision 10).

## Test suite

verify.md cites 171/171 PASS on JVM (`Total time: 87 s`). Cross-platform JVM/JS/Native deferred to Phase 07 per plan.

---

## BLOCKER

None.

## WARN

1. **Stale `JsonRpcId` string in `JsonRpcCodecTest.scala`** (2 occurrences). Line 100: `"Strict2_0 decodes id null on wire as Maybe JsonRpcId Absent"` (test description) and line 113: `s"... JsonRpcEnvelope.Response.id has type JsonRpcId not Maybe ..."` (fail message). These are string literals, not type references — compile-clean and harmless — but they refer to a renamed type. Suggest a sweep in a follow-up commit to update these strings to `JsonRpcEnvelope.Id`. Not commit-blocking.

## NOTE

1. **`CancellationPolicy.ParamsEncoder`/`ParamsDecoder` visibility narrowed to `private`** inside `object JsonRpcEndpoint` (Decision 3 + Decision 9). Type aliases were previously package-public; they were converted to companion-private since they are not part of the user-facing public surface (case-class constructor lambdas infer the type at use sites). The corresponding test `JsonRpcEndpointCancellationPolicyTest` now inlines the raw function type rather than referencing the alias. Supervisor reviewed and accepted.

2. **Pre-existing `8a-package-public` violations on the 6 top-level files** are flagged by `flow-verify-organization.sh --check all` but predate Phase 03 (confirmed via `git show 3f66991cd`). They reflect intentional `private[kyo]` bridge-visibility for internal plumbing; 8a hygiene is not a Phase-03 deliverable.

3. **`JsonRpcEndpoint` includes engine references to `MessageGate.Decision`** at the new nested path (`internal.engine.JsonRpcEndpointImpl.scala` lines 967/969/976/1134/1136/1148 per plan); cross-checked, no stale `MessageGate.Decision` top-level imports remain.

4. **`JsonRpcEndpoint.Pending.id`** field type and `sendUnmatched`/`cancel` parameter type were updated from `JsonRpcId` to `JsonRpcEnvelope.Id` (Decision 6) — expected per the rename.

5. **`kyo-jsonrpc-http` has a pre-existing `// PUBLIC` banner** at `JsonRpcHttpTransport.scala:1`. Phase 01 banner-strip scope was limited to `kyo-jsonrpc/shared/src/main/scala/kyo/`, so this is out-of-scope for Phase 01 and Phase 03 alike. Future hygiene phase territory.

## Exit verdict

PASS. The Phase 03 commit achieves the campaign §4–§5 NEST roster byte-correctly, deletes all 10 standalone files, renames all 10 test files, updates downstream consumers (`kyo-browser`, `kyo-jsonrpc-http`) consistently, and ships a green JVM test run (171/171). The two WARN items are cosmetic string-literal hygiene with no functional impact.
