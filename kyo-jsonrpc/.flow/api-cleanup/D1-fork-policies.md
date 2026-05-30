# D1. Fork 1 Resolution: 6 policy/strategy types, final per-type verdict

Source-grounded resolution of Fork 1 from `C-cleanup-plan.md §11`. The plan's recommendations were derived from A3 (consumer footprint, repo-wide) and A4 (naming/nesting suggestions). This document re-examines each of the 6 types against a stricter rubric:

1. What shape is the type (behavior contract trait, ADT-with-presets, opaque function alias, closed config record)?
2. How do **callers** (in tests AND in cross-module consumers) actually use it: subclass / pattern-match / construct custom / select-preset / pass-default?
3. What does kyo-http do for the analogous role (config-touched policy, nested vs top-level)?
4. Would a new external consumer reading the API today expect to find this type top-level under `kyo.*`, nested under `kyo.JsonRpcEndpoint.*`, or never see it at all?

A3 was scoped to "consumers outside kyo-jsonrpc/kyo-jsonrpc-http itself" and missed in-module test-suite extensions; that omission is corrected below.

Important reconciliation against `feedback_export_only_when_warranted`: zero current-external-callers is necessary but not sufficient to move-to-internal. The deciding question is whether the type is **designed as an extension point** that the cross-harness ecosystem (kyo-browser CDP, future kyo-lsp, future kyo-mcp) will need to extend or construct. If yes, keep public (nest or prefix). If no (the in-module engine only consumes it, no plausible external implementer), move to internal.

---

## 1. `MessageGate` (`kyo-jsonrpc/shared/src/main/scala/kyo/MessageGate.scala:4`)

**Shape**: behavior contract trait with three-case `Decision` ADT. One abstract method `beforeDispatch(env: JsonRpcEnvelope): Decision < Sync`. Open extension point by design.

**Caller evidence**: A3 reported "0 consumers". That count excluded kyo-jsonrpc's own test suite. Actual caller analysis:

- `kyo-jsonrpc/shared/src/test/scala/kyo/MessageGateTest.scala:27, :36`: two anonymous `new MessageGate:` subclass instances asserting Drop/Allow semantics.
- `kyo-jsonrpc/shared/src/test/scala/kyo/UnknownMethodPolicyTest.scala:95, :112, :133, :163, :184`: five distinct `MessageGate` implementations exercising Allow / Reject(error) / Drop / LSP-init-pattern. These tests document the contract for external implementers.
- `kyo-jsonrpc/shared/src/test/scala/kyo/scenario/HttpStyleTest.scala:89`: `lspInitGate: MessageGate` constructed inline.
- `kyo-jsonrpc/shared/src/main/scala/kyo/internal/JsonRpcEndpointImpl.scala:967, :969, :976, :1134, :1136, :1148`: engine code pattern-matches on `MessageGate.Decision` in two dispatch paths (request + notification).

Eight distinct in-repo subclass instances plus an engine that pattern-matches the `Decision` ADT. The "LSP initialize pattern" subclass in `UnknownMethodPolicyTest.scala:184-192` is exactly the use case an external `kyo-lsp` server would write. The "zero external callers TODAY" finding is an artifact of no kyo-lsp module existing yet; the trait is a load-bearing extension point.

**kyo-http template**: There is no exact analogue. The closest is `HttpFilter` (`kyo-http/shared/src/main/scala/kyo/HttpFilter.scala:43`), a behavior-contract trait that filters/transforms requests. `HttpFilter` is **top-level public** with the `Http` prefix, NOT nested under `HttpServer` or `HttpClient`. The nested pattern in `HttpServerConfig.Cors` (`kyo-http/shared/src/main/scala/kyo/HttpServerConfig.scala:117`) is reserved for **closed data records**, not for open extension-point traits.

**Verdict: RENAME-WITH-PREFIX-KEEP-PUBLIC** → `JsonRpcMessageGate` (top-level).

**Rationale**: This is an open user-implementable trait, exercised by 8 in-module subclasses and 1 engine dispatch site. Nesting it as `JsonRpcEndpoint.Gate` would mismatch the kyo-http precedent that reserves nesting for closed records, not open traits (`HttpFilter` stays top-level). Moving to `kyo.internal.engine.*` would force test files to import internal packages, AND would force future kyo-lsp/kyo-mcp implementers to do so as well. The right move is the same rule the rest of the cleanup applies to user-implementable traits: add the prefix and keep public. The plan's C §3 row claims "no caller has implemented this trait anywhere in the repo"; that claim is contradicted by `MessageGateTest.scala:27` and 7 sibling sites.

**Target**: `kyo.JsonRpcMessageGate` at `kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcMessageGate.scala`. The `Decision` ADT remains nested in its companion: `JsonRpcMessageGate.Decision.{Allow, Reject, Drop}`.

---

## 2. `CancellationPolicy` (`kyo-jsonrpc/shared/src/main/scala/kyo/CancellationPolicy.scala:10`)

**Shape**: closed configuration record (`final case class` with 6 fields including two function-typed `ParamsEncoder` / `ParamsDecoder` aliases) plus two named presets `.lsp` and `.mcp`. Not a trait, there is no subclassing surface.

**Caller evidence**:

- `kyo-jsonrpc/shared/src/test/scala/kyo/CancellationPolicyTest.scala:11, :12, :447`: selects `CancellationPolicy.lsp` and `.mcp` presets.
- `kyo-jsonrpc/shared/src/test/scala/kyo/CancellationPolicyTest.scala:646`: `CancellationPolicy("x.cancel", CancellationPolicy.lsp.encodeParams, decoder, false, Absent, Set.empty)` constructs a fully-custom policy mixing borrowed encoder with custom decoder/method. This is the "user wants their own dialect" code path.
- `kyo-jsonrpc/shared/src/test/scala/kyo/scenario/BidiTest.scala:64, :127`; `MaxInFlightTest.scala:186, :320`: select presets in scenario tests.
- `kyo-jsonrpc/shared/src/test/scala/kyo/ProgressPolicyTest.scala:32`: pairs `.mcp` cancellation with `.mcp` progress.

External (A3) consumers: zero in kyo-browser today. But the in-module evidence shows the type is designed for **dialect customization**: the test at line 646 demonstrates how a third LSP/MCP-like dialect (call it kyo-dap, kyo-cdp-as-jsonrpc, kyo-language-x) would construct a fully-bespoke policy. `JsonRpcEndpointImpl.scala:374, :454` advertises the type in error messages ("pass Config.progress = Present(ProgressPolicy.lsp / .mcp)"), confirming the intent-to-extend.

**kyo-http template**: `HttpTlsConfig` (`kyo-http/shared/src/main/scala/kyo/HttpTlsConfig.scala:21`) is the closest structural match, a top-level public `case class` with `.default` and embedded `enum` types (`ClientAuth`, `Version` at `HttpTlsConfig.scala:41, :44`) referenced via `HttpServerConfig.tls: Maybe[HttpTlsConfig]` (`HttpServerConfig.scala:63`). It stays **top-level**, prefix-rule applied. The principle: a non-trivial config record with multiple presets and discriminated sub-enums earns a top-level slot, NOT a nest. By contrast, `HttpServerConfig.Cors` (which IS nested) is a tiny 5-field `case class` with no presets, no helper types, no static `.allowAll`-equivalent ecosystem.

**Verdict: RENAME-WITH-PREFIX-KEEP-PUBLIC** → `JsonRpcCancellationPolicy` (top-level).

**Rationale**: 76-line record with `ParamsEncoder` / `ParamsDecoder` type aliases plus two presets, putting this at `HttpTlsConfig`-scale, not `Cors`-scale. The presets `.lsp` and `.mcp` are dialect documentation; a kyo-dap or kyo-cdp-as-jsonrpc port would add a third preset alongside, exactly as `HttpTlsConfig.Version.TLS12` lives alongside `.TLS13`. Nesting it as `JsonRpcEndpoint.CancellationPolicy` would balloon `JsonRpcEndpoint.scala` by 76 lines for content that has its own coherent identity. Moving to internal would force the `.lsp`/`.mcp` preset constants out of public reach AND force `CancellationPolicyTest.scala:646` to import internals.

**Target**: `kyo.JsonRpcCancellationPolicy` at `kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcCancellationPolicy.scala`. The `ParamsEncoder` / `ParamsDecoder` type aliases stay in the companion, the `.lsp` and `.mcp` presets stay in the companion.

---

## 3. `ProgressPolicy` (`kyo-jsonrpc/shared/src/main/scala/kyo/ProgressPolicy.scala:10`)

**Shape**: closed configuration record (`final case class` with 7 function-typed fields) plus two named presets `.lsp` and `.mcp`. Same shape as `CancellationPolicy`: record-with-presets, not a trait.

**Caller evidence**:

- `kyo-jsonrpc/shared/src/test/scala/kyo/ProgressPolicyTest.scala:29, :31`: selects `.lsp` and `.mcp` presets.
- `kyo-jsonrpc/shared/src/test/scala/kyo/scenario/BidiTest.scala:191`: `cancellation = Present(ProgressPolicy.lsp)` (typo: should be `progress`, but the reference exists).
- `kyo-jsonrpc/shared/src/test/scala/kyo/scenario/MaxInFlightTest.scala:319`: `progress = Present(ProgressPolicy.lsp)`.
- `kyo-jsonrpc/shared/src/main/scala/kyo/internal/JsonRpcEndpointImpl.scala:374, :454`: error messages reference `ProgressPolicy.lsp / .mcp` by name, instructing callers how to enable progress notifications.

External consumers: zero today, but the engine error message explicitly tells future callers "construct a ProgressPolicy". The structural symmetry with `CancellationPolicy` means the same design intent applies: dialect customization point.

**kyo-http template**: same as §2, namely `HttpTlsConfig` (`HttpTlsConfig.scala:21`). Non-trivial record with presets stays top-level.

**Verdict: RENAME-WITH-PREFIX-KEEP-PUBLIC** → `JsonRpcProgressPolicy` (top-level).

**Rationale**: Identical shape and design intent as `CancellationPolicy`; the verdict must match. The two policies pair as the "LSP/MCP dialect customization surface" of `JsonRpcEndpoint.Config`. Either both nest or neither nests; the kyo-http precedent supports both staying top-level with the prefix.

**Target**: `kyo.JsonRpcProgressPolicy` at `kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcProgressPolicy.scala`. The `.lsp` and `.mcp` presets stay in the companion.

---

## 4. `UnknownMethodPolicy` (`kyo-jsonrpc/shared/src/main/scala/kyo/UnknownMethodPolicy.scala:5`)

**Shape**: closed `case class` with three fields + `UnknownAction` enum (3 cases) + 3 named presets `.minimal`, `.lsp`, `.strict`. Constructor is `private[kyo]` (smart-constructor pattern), so external callers cannot construct directly without re-export.

Verifying the constructor visibility: `final case class UnknownMethodPolicy private[kyo] (...)`. The primary constructor is `private[kyo]`, so an external module CANNOT do `UnknownMethodPolicy(...)`. Only the three presets are externally constructable.

But `JsonRpcPortInvariantsSpec.scala:304-306` in kyo-browser DOES construct a custom policy:

```scala
val dropPolicy = UnknownMethodPolicy(
    onUnknownRequest = UnknownMethodPolicy.UnknownAction.Drop,
    onUnknownNotification = UnknownMethodPolicy.UnknownAction.Drop,
    ...
```

This call site lives in `kyo-browser/shared/src/test/scala/kyo/internal/JsonRpcPortInvariantsSpec.scala` whose package is `kyo.internal` (declared inside `kyo` package). Since the constructor is `private[kyo]`, anything in the `kyo` package or sub-package can construct it. kyo-browser's test file is `package kyo` -based, so it's inside that visibility boundary. This is intentional cross-module privilege based on the `kyo` package convention.

**Caller evidence**:

- `kyo-browser/shared/src/main/scala/kyo/internal/CdpBackend.scala:199, :460`: `UnknownMethodPolicy.minimal` preset.
- `kyo-browser/shared/src/test/scala/kyo/internal/JsonRpcPortInvariantsSpec.scala:304-306`: custom `UnknownMethodPolicy(...)` construction (relies on `private[kyo]` visibility).
- `kyo-jsonrpc/shared/src/test/scala/kyo/UnknownMethodPolicyTest.scala:27, :42, :74`: selects `.lsp`, `.strict` presets.
- `kyo-jsonrpc/shared/src/test/scala/kyo/JsonRpcEndpointTest.scala:465`: `cfg.unknownMethod == UnknownMethodPolicy.minimal`.

**kyo-http template**: same record-with-presets pattern as `HttpTlsConfig` (`HttpTlsConfig.scala:21`); top-level. The `UnknownAction` nested enum mirrors `HttpTlsConfig.ClientAuth` (`HttpTlsConfig.scala:41`).

**Verdict: RENAME-WITH-PREFIX-KEEP-PUBLIC** → `JsonRpcUnknownMethodPolicy` (top-level).

**Rationale**: Identical shape to `CancellationPolicy` and `HttpTlsConfig`. Closed record + nested action enum + 3 named presets. The presets are the documented user surface (`.minimal`, `.lsp`, `.strict`), and the `private[kyo]` constructor lets the kyo-browser invariants spec build a bespoke policy. Nesting under `JsonRpcEndpoint.UnknownMethodPolicy` is technically possible but yields `JsonRpcEndpoint.UnknownMethodPolicy.UnknownAction.Drop`, a four-segment access path that adds no clarity over `JsonRpcUnknownMethodPolicy.UnknownAction.Drop`. The kyo-http precedent for "policy record with nested enum" is top-level (`HttpTlsConfig.ClientAuth`), not deeply nested.

**Target**: `kyo.JsonRpcUnknownMethodPolicy` at `kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcUnknownMethodPolicy.scala`. The `UnknownAction` enum, the three presets `.minimal`/`.lsp`/`.strict`, and the `private[kyo]` constructor stay as-is.

---

## 5. `IdStrategy` (`kyo-jsonrpc/shared/src/main/scala/kyo/IdStrategy.scala:4`)

**Shape**: 8-line enum with 3 cases: `SequentialLong`, `SequentialInt`, `Custom(next: () => JsonRpcId < Sync)`. The `Custom` constructor is the user-extension hatch; the two `Sequential*` cases are presets.

**Caller evidence**:

- `kyo-browser/shared/src/main/scala/kyo/internal/CdpBackend.scala:203, :464, :576`: `IdStrategy.SequentialInt` (3 sites in main code).
- `kyo-browser/shared/src/test/scala/kyo/internal/CdpBackendTest.scala:52`, `CdpClientDecoderTest.scala:45`, `JsonRpcPortInvariantsSpec.scala:56, :321`, `CdpBackendLifecycleTest.scala:1178`, `CdpBackendSmokeTest.scala:43`: all select `.SequentialInt`.
- `kyo-jsonrpc/shared/src/test/scala/kyo/JsonRpcEndpointTest.scala:362, :381, :389, :435, :438`: selects all three presets including `IdStrategy.Custom(() => ...)` with 100 concurrent calls. This is the user-extension code path.
- `kyo-jsonrpc/shared/src/test/scala/kyo/IdStrategyTest.scala:10, :23, :38, :50`: exhaustive test of all three cases.

The `IdStrategy.Custom` constructor IS exercised as a user-extension point.

**kyo-http template**: kyo-http has no exact analogue (no client-side ID generation contract). The closest pattern is `HttpStatus` (`kyo-http/shared/src/main/scala/kyo/HttpStatus.scala:25`), a `sealed abstract class` with many named values plus a `Custom` escape hatch, top-level with prefix. Or `HttpMethod` (`HttpMethod.scala:8`), a small ADT, top-level with prefix.

**Verdict: RENAME-WITH-PREFIX-KEEP-PUBLIC** → `JsonRpcIdStrategy` (top-level).

**Rationale**: A tiny 8-line enum is borderline-nestable (cf. `HttpServerConfig.Cors` is 5 fields, similar mass). But the deciding criterion is **how callers reach it**: kyo-browser writes `IdStrategy.SequentialInt` at 6+ sites and `JsonRpcEndpointTest.scala:438` writes `IdStrategy.Custom(...)`. Nesting as `JsonRpcEndpoint.IdStrategy.SequentialInt` adds a path segment for every site that already lives outside `JsonRpcEndpoint`. A new external consumer reading the kyo-jsonrpc README would expect `idStrategy = JsonRpcIdStrategy.SequentialInt` to mirror `tls = JsonRpcTlsConfig.default`-style kyo-http patterns. Nesting saves nothing here; the prefix-and-keep approach is the same rule applied to every other top-level config-touched type.

**Target**: `kyo.JsonRpcIdStrategy` at `kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcIdStrategy.scala`. The three enum cases stay as `JsonRpcIdStrategy.{SequentialLong, SequentialInt, Custom}`.

---

## 6. `ExtrasEncoder` (`kyo-jsonrpc/shared/src/main/scala/kyo/ExtrasEncoder.scala:4`)

**Shape**: `opaque type ExtrasEncoder = JsonRpcId => Maybe[Structure.Value] < Sync`. Function-typed alias with companion factories (`apply`, `empty`, `const`) and one extension method (`resolve`). Caller-facing as a parameter type on three `JsonRpcEndpoint` methods: `call`, `notify`, `sendUnmatched` (`JsonRpcEndpoint.scala:12, :19, :27`).

**Caller evidence**:

- `kyo-browser/shared/src/main/scala/kyo/internal/CdpBackend.scala:41, :44, :590, :593`: `ExtrasEncoder.const(...)` and `ExtrasEncoder.empty` for per-session sessionId stamping (4 sites in main code).
- `kyo-browser/shared/src/test/scala/kyo/internal/CdpBackendSmokeTest.scala:152, :186, :211, :251`: `ExtrasEncoder.const(...)` in 4 test scenarios.
- `kyo-browser/shared/src/test/scala/kyo/internal/JsonRpcPortInvariantsSpec.scala:212`: `ExtrasEncoder.const(...)`.
- `kyo-jsonrpc/shared/src/test/scala/kyo/JsonRpcEndpointTest.scala:249, :285, :339, :354, :365, :384, :449`: `ExtrasEncoder(...)` and `ExtrasEncoder.const(...)`.

10+ call sites across kyo-browser and kyo-jsonrpc tests. This is a hot user-facing API.

**kyo-http template**: kyo-http has no exact analogue. Function-typed opaque types in kyo-http are nested: `HttpFilter.Factory` (referenced at `HttpFilter.scala:74`, internal-pattern in A4's reading). But the closer match is `HttpFormCodec` (`kyo-http/shared/src/main/scala/kyo/HttpFormCodec.scala:25`), a codec-like extension surface that stays top-level prefixed. Function-typed-opaque is a kyo-jsonrpc-specific pattern with no exact precedent.

**Verdict: RENAME-WITH-PREFIX-KEEP-PUBLIC** → `JsonRpcExtrasEncoder` (top-level).

**Rationale**: 10+ external call sites, all using factory methods (`ExtrasEncoder.const`, `.empty`, `ExtrasEncoder(...)`). This is unambiguously a public extension point. C-cleanup-plan §3 row argues nest-as-`JsonRpcEndpoint.Extras` to "clean up `kyo.*` namespace", but nesting an opaque type with companion factories forces every caller to write `JsonRpcEndpoint.Extras.const(...)` at 21 characters where `JsonRpcExtrasEncoder.const(...)` is 20 characters. The rename loses one character and gains a name that explicitly says what it is (an encoder), versus the bare `Extras` which is ambiguous. The `opaque type` itself is not deeply tied to `JsonRpcEndpoint`. It is tied to the JSON-RPC dispatch concept of per-call routing metadata, and could be used by any future API that needs the same hook. Prefix-keep is the clean choice.

**Target**: `kyo.JsonRpcExtrasEncoder` at `kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcExtrasEncoder.scala`. The companion factories `apply`/`empty`/`const` and the `resolve` extension method stay.

---

## Summary table

| Type | Verdict | Target name/location | Why this beats the alternatives |
|---|---|---|---|
| `MessageGate` | RENAME-WITH-PREFIX-KEEP-PUBLIC | `kyo.JsonRpcMessageGate` at `kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcMessageGate.scala` | Open user-implementable trait with 8 in-repo subclass extensions (`MessageGateTest.scala:27, :36`, `UnknownMethodPolicyTest.scala:95, :112, :133, :163, :184`, `scenario/HttpStyleTest.scala:89`). kyo-http precedent for behavior-contract traits is top-level with prefix (`HttpFilter.scala:43`), not nested under a config record. Moving to internal would force test imports through `kyo.internal.engine.*` and block future kyo-lsp/kyo-mcp implementers. |
| `CancellationPolicy` | RENAME-WITH-PREFIX-KEEP-PUBLIC | `kyo.JsonRpcCancellationPolicy` at `kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcCancellationPolicy.scala` | 76-line dialect-customization record with `ParamsEncoder`/`ParamsDecoder` type aliases and `.lsp`/`.mcp` presets, plus a custom-construction code path at `CancellationPolicyTest.scala:646`. Matches `HttpTlsConfig.scala:21` shape (top-level, prefixed, with nested helper enums and `.default`-style presets). Nesting under `JsonRpcEndpoint` would balloon that file by 76 lines for content with its own coherent identity. |
| `ProgressPolicy` | RENAME-WITH-PREFIX-KEEP-PUBLIC | `kyo.JsonRpcProgressPolicy` at `kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcProgressPolicy.scala` | Structural twin of `CancellationPolicy`: same shape, same caller pattern (`MaxInFlightTest.scala:319`, `BidiTest.scala:191`, `ProgressPolicyTest.scala:29, :31`), same kyo-http template (`HttpTlsConfig.scala:21`). Engine error messages at `JsonRpcEndpointImpl.scala:374, :454` explicitly advertise the type to callers. Verdict must match `CancellationPolicy` for symmetry. |
| `UnknownMethodPolicy` | RENAME-WITH-PREFIX-KEEP-PUBLIC | `kyo.JsonRpcUnknownMethodPolicy` at `kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcUnknownMethodPolicy.scala` | Closed record + nested `UnknownAction` enum + three `.minimal`/`.lsp`/`.strict` presets, with `private[kyo]` constructor exercised by kyo-browser at `JsonRpcPortInvariantsSpec.scala:304-306`. Matches `HttpTlsConfig` (`HttpTlsConfig.scala:21`) + `HttpTlsConfig.ClientAuth` (`HttpTlsConfig.scala:41`) pattern: top-level record with nested helper enum. A four-segment access path `JsonRpcEndpoint.UnknownMethodPolicy.UnknownAction.Drop` (if nested) adds no clarity over the three-segment `JsonRpcUnknownMethodPolicy.UnknownAction.Drop`. |
| `IdStrategy` | RENAME-WITH-PREFIX-KEEP-PUBLIC | `kyo.JsonRpcIdStrategy` at `kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcIdStrategy.scala` | 8-line enum with 2 presets + `Custom(next)` extension hatch. The `Custom` constructor IS exercised at `JsonRpcEndpointTest.scala:438` as a user-extension point. 6 kyo-browser sites reference `IdStrategy.SequentialInt`. Nesting under `JsonRpcEndpoint` adds a path segment for every existing site outside `JsonRpcEndpoint`; no caller benefits. Matches `HttpMethod`/`HttpStatus` "small enum with presets + Custom" pattern (`HttpStatus.scala:25`), both top-level with prefix. |
| `ExtrasEncoder` | RENAME-WITH-PREFIX-KEEP-PUBLIC | `kyo.JsonRpcExtrasEncoder` at `kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcExtrasEncoder.scala` | 10+ external call sites in kyo-browser main and tests plus kyo-jsonrpc test suite. Function-typed opaque alias used as a parameter type on `JsonRpcEndpoint.{call, notify, sendUnmatched}`. The bare `Extras` name (if nested) is ambiguous; the `Encoder` suffix communicates the role. Prefix-keep saves one character per call site versus nesting (`JsonRpcExtrasEncoder.const` is 20 chars, `JsonRpcEndpoint.Extras.const` is 21 chars) and gains a clearer name. |

---

## Reconciliation note vs `C-cleanup-plan.md §3`

The plan as written sends three of the six types (`MessageGate`, `CancellationPolicy`, `ProgressPolicy`) to `kyo.internal.engine.*` based on A3's "zero external callers" count. That count missed the in-module test-suite extensions (especially the 8 `MessageGate` subclass instances and the custom `CancellationPolicy` construction at line 646). It also did not credit the engine error messages at `JsonRpcEndpointImpl.scala:374, :454` that explicitly tell callers to construct `ProgressPolicy.lsp / .mcp`.

This D1 resolution diverges from §3 on those three types: the verdict for all six is uniformly RENAME-WITH-PREFIX-KEEP-PUBLIC. The rule applied is consistent with §3's treatment of `Framer` and `WireTransport` (also "0 external callers today" per A3) which §3 already recommends prefix-and-keep on `feedback_export_only_when_warranted` grounds. The same logic extends to the three policy types.

Phase impact: Phase 2 of the plan ("Move A3-UNUSED types to internal") collapses into Phase 5 ("Prefix-rename"). The 6 files become 6 renames under `kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpc*.scala`. `JsonRpcEndpoint.Config`'s field types update accordingly: `cancellation: Maybe[JsonRpcCancellationPolicy]`, `progress: Maybe[JsonRpcProgressPolicy]`, `unknownMethod: JsonRpcUnknownMethodPolicy`, `gate: Maybe[JsonRpcMessageGate]`, `idStrategy: JsonRpcIdStrategy`. The plan's downstream phases (Config fluent setters, default constant, require validations, derives CanEqual, drop redundant Sync) are unchanged.
