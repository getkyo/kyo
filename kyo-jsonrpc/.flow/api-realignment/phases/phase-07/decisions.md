# Phase 07 decisions

Decision 1: Hoist 7 nested types to top-level with JsonRpc prefix
Rationale: Plan Phase G; locked naming decisions in realignment-plan.md. kyo-http keeps all analogous
types top-level. The types move from companion-nested to top-level files; their bodies are verbatim
copies. Backward-compat type aliases are added in JsonRpcHandler and JsonRpcTransport companions.
Time: 2026-05-30

Decision 2: Add backward-compat type aliases in JsonRpcHandler and JsonRpcTransport companions
Rationale: The hoist is a pure rename. Type aliases (e.g. `type CancellationPolicy = JsonRpcCancellationPolicy`)
allow existing callers using the qualified form to keep compiling without changes. This matches the
kyo-http pattern where inner types are sometimes re-exported from the companion.
Time: 2026-05-30

Decision 3: +Out covariance on JsonRpcHandler.Pending APPLIED
Rationale: Audit A7. `Out` appears only in `val result: Out < (Async & Abort[JsonRpcError | Closed])`.
The `<[+A, -S]` type is covariant in A (kyo-kernel/Pending.scala:42). A `val` in a final class
exposes a getter (covariant position). No contravariant field uses Out. The compiler accepts +Out.
Time: 2026-05-30

Decision 4: JsonRpcCodec.default = JsonRpcCodec.Strict2_0
Rationale: Axis 13. JsonRpcHandler.Config.default uses `codec: JsonRpcCodec = JsonRpcCodec.Strict2_0`,
so the sensible default preset is Strict2_0. Matches the established convention in Config.
Time: 2026-05-30

Decision 5: JsonRpcMessageGate.noop already present (Phase 06); confirmed in source, no action needed
Rationale: Phase 06 added noop. JsonRpcMessageGate.scala:48 has `val noop: JsonRpcMessageGate`.
Time: 2026-05-30

Decision 6: JsonRpcRoute.apply renamed to JsonRpcRoute.request; apply alias kept for backward-compat
Rationale: Axis 12 symmetry with JsonRpcRoute.notification. An `apply` alias delegates to `request`
so existing call sites using `JsonRpcRoute("methodName")(handler)` continue compiling. This matches
the kyo-http pattern where HttpRoute.apply is the canonical factory.
Time: 2026-05-30

Decision 7: Caller update strategy for the 7 hoisted types
Rationale: Bulk sed-replace for the qualified forms (e.g. `JsonRpcHandler.CancellationPolicy` ->
`JsonRpcCancellationPolicy`), followed by import cleanup. Backward-compat aliases mean import-based
callers (e.g. `import kyo.JsonRpcHandler.IdStrategy`) still compile after adding aliases.
Time: 2026-05-30

## Hoist inventory (old path -> new file)

1. JsonRpcHandler.CancellationPolicy -> kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcCancellationPolicy.scala
2. JsonRpcHandler.ProgressPolicy -> kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcProgressPolicy.scala
3. JsonRpcHandler.UnknownMethodPolicy -> kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcUnknownMethodPolicy.scala
4. JsonRpcHandler.IdStrategy -> kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcIdStrategy.scala
5. JsonRpcHandler.ExtrasEncoder -> kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcExtrasEncoder.scala
6. JsonRpcTransport.Framer -> kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcFramer.scala
7. JsonRpcTransport.WireTransport -> kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcWireTransport.scala
