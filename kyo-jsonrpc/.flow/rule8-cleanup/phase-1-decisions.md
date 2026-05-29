# Phase 1 Decisions Log

Total entries: 8

## Decision 1: IdStrategyEngine.scala uses plan's match arm identifiers verbatim

The plan's code block in IdStrategyEngine uses qualified identifiers (`IdStrategy.SequentialLong`, `IdStrategy.SequentialInt`, `IdStrategy.Custom(next)`) because the object is in package `kyo.internal`, not `kyo`. The original `IdStrategy.scala` companion used unqualified names (since it was inside the enum's companion). The plan already accounts for this: its code block for IdStrategyEngine correctly uses fully qualified case names. No adaptation needed.

## Decision 2: IdStrategy companion object removed entirely

The original `IdStrategy.scala` contained an `object IdStrategy` companion with only `mkNextId`. After relocating `mkNextId` to `IdStrategyEngine`, the companion became empty. The plan's AFTER block shows no companion object at all, so the entire `object IdStrategy` block was removed (not left as an empty object).

## Decision 3: JsonRpcCodecImpl cdpReservedKeys accessibility

The plan says `private val cdpReservedKeys` inside `JsonRpcCodecImpl`. This is correct: since both `buildWithExtras` and `decode` (in the nested `Cdp` anonymous class) reference `cdpReservedKeys`, the val is accessible because the anonymous class inherits the outer object's scope. Scala allows anonymous-class bodies to access private members of the enclosing object. No adaptation needed.

## Decision 4: SBT module name for compile verification

The plan's verification command uses `kyo-jsonrpcJVM/Test/compile`. The actual SBT project name is `kyo-jsonrpc` (JVM is the default platform, not a separate project ID). Used `; project kyo-jsonrpc; Test/compile` instead, which produced a successful compile in 15 seconds.

## Decision 5: PUBLIC marker rationales chosen exactly per plan

All 13 files received the exact one-line rationale specified in the plan's AFTER blocks:
- CancellationPolicy: "PUBLIC config-policy type referenced by JsonRpcEndpoint.Config.cancellation field"
- ExtrasEncoder: "PUBLIC opaque-type for the JsonRpcEndpoint.call/notify extras parameter"
- HandlerCtx: "PUBLIC handler-context receiver consumed by user JsonRpcMethod handlers"
- IdStrategy: "PUBLIC config-strategy sum type referenced by JsonRpcEndpoint.Config.idStrategy field"
- JsonRpcCodec: "PUBLIC codec interface referenced by JsonRpcEndpoint.Config.codec field"
- JsonRpcEndpoint: "PUBLIC primary user-facing endpoint surface"
- JsonRpcEnvelope: "PUBLIC wire-shape ADT exposed through JsonRpcTransport and MessageGate user implementations"
- JsonRpcError: "PUBLIC error-channel ADT appearing in JsonRpcEndpoint Abort rows and user error matching"
- JsonRpcId: "PUBLIC id ADT referenced by JsonRpcEndpoint.cancel, Pending.id, ExtrasEncoder, HandlerCtx.requestId"
- JsonRpcMethod: "PUBLIC method-binding surface built by user and passed to JsonRpcEndpoint.init"
- JsonRpcRequest: "PUBLIC wire-shape pair; INTERNAL split of JsonRpcRequest follows in Phase 2 (file dissolved)"
- JsonRpcTransport: "PUBLIC transport interface implemented by users and consumed by JsonRpcEndpoint.init"
- MessageGate: "PUBLIC gate trait implemented by users and consumed via JsonRpcEndpoint.Config.gate"
- ProgressPolicy: "PUBLIC config-policy type referenced by JsonRpcEndpoint.Config.progress field"
- UnknownMethodPolicy: "PUBLIC config-policy type referenced by JsonRpcEndpoint.Config.unknownMethod field with three documented presets"

## Decision 6: Organization check result - zero violations

After applying all markers and relocations, `flow-verify-organization.sh --check package` reported `violations=0`. The gate is clean. The plan stated the expected remaining flag was `JsonRpcRequest.scala` (to be dissolved in Phase 2), but the PUBLIC marker added in this phase already silences that flag, so no remaining 8a flag exists at the Phase 1 boundary.

## Decision 7: JsonRpcRequest.scala top-of-file comment placement

The current file's first line was `package kyo` (no preceding blank line or comment). The plan's AFTER block shows the PUBLIC marker comment on line 1 before the package declaration. Applied as the very first line of the file, with `package kyo` immediately after. This matches Scala convention where file-level comments precede the package clause.

## Decision 8: No import changes in JsonRpcEndpointImpl.scala

The plan noted that `IdStrategyEngine` resides in package `kyo.internal` and `JsonRpcEndpointImpl` also resides in `kyo.internal`, so no new import is needed. Confirmed: the only change to `JsonRpcEndpointImpl.scala` was the single callsite rewrite on line 735.
