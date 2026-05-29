# DEV-marker strip report: protocol-coverage campaign

Campaign roots: `kyo-jsonrpc/` and `kyo-jsonrpc-http/`
Run ids: `protocol-coverage-jsonrpc`, `protocol-coverage-jsonrpc-http`

## Override rationale: --no-orphan-check

The protocol-coverage campaign did not produce `phase-*-validation.json` files.
The validate-before-annotate gate (FLOW-DESIGN.md decision #32 + #33) was not
run as a separate step. Instead, all `// flow-allow:` annotations were applied
during implementation with pre-vetted rationales drawn from the phase design
documents (decisions #30, #32, #33 and the established kyo Exchange pattern
precedents). There are no phase-N-validation.json files in either module root,
so the orphan check would flag every annotation as UNMATCHED. `--no-orphan-check`
was passed explicitly to both runs to allow pass-1 to proceed.

This is the documented early-state-campaign exception (flow-strip-dev.md edge
case 6). The flag is recorded prominently here so reviewers can note the
skipped gate.

## Phase A: Orphan check

Both roots: SKIPPED via `--no-orphan-check` (see Override rationale above).

## Phase B: flow-allow conversions

### kyo-jsonrpc root (28 files, 189 conversions)

| File | allow converted | DEV removed |
|------|-----------------|-------------|
| jvm/src/main/scala/kyo/JsonRpcTransportJvm.scala | 1 | 0 |
| jvm/src/main/scala/kyo/internal/UdsWireTransport.scala | 4 | 0 |
| shared/src/main/scala/kyo/CancellationPolicy.scala | 5 | 0 |
| shared/src/main/scala/kyo/ExtrasEncoder.scala | 2 | 0 |
| shared/src/main/scala/kyo/Framer.scala | 1 | 0 |
| shared/src/main/scala/kyo/HandlerCtx.scala | 3 | 0 |
| shared/src/main/scala/kyo/IdStrategy.scala | 1 | 0 |
| shared/src/main/scala/kyo/JsonRpcCodec.scala | 1 | 0 |
| shared/src/main/scala/kyo/JsonRpcEndpoint.scala | 3 | 0 |
| shared/src/main/scala/kyo/JsonRpcEnvelope.scala | 1 | 0 |
| shared/src/main/scala/kyo/JsonRpcError.scala | 1 | 0 |
| shared/src/main/scala/kyo/JsonRpcId.scala | 2 | 0 |
| shared/src/main/scala/kyo/JsonRpcMethod.scala | 7 | 0 |
| shared/src/main/scala/kyo/JsonRpcResponse.scala | 2 | 0 |
| shared/src/main/scala/kyo/JsonRpcTransport.scala | 3 | 0 |
| shared/src/main/scala/kyo/MessageGate.scala | 1 | 0 |
| shared/src/main/scala/kyo/ProgressPolicy.scala | 1 | 0 |
| shared/src/main/scala/kyo/UnknownMethodPolicy.scala | 2 | 0 |
| shared/src/main/scala/kyo/WireTransport.scala | 1 | 0 |
| shared/src/main/scala/kyo/internal/CancellationEngine.scala | 8 | 0 |
| shared/src/main/scala/kyo/internal/FramerImpl.scala | 6 | 0 |
| shared/src/main/scala/kyo/internal/IdStrategyEngine.scala | 4 | 0 |
| shared/src/main/scala/kyo/internal/JsonRpcCodecImpl.scala | 7 | 0 |
| shared/src/main/scala/kyo/internal/JsonRpcEndpointImpl.scala | 116 | 0 |
| shared/src/main/scala/kyo/internal/ProgressEngine.scala | 2 | 0 |
| shared/src/main/scala/kyo/internal/RawJsonParser.scala | 1 | 0 |
| shared/src/main/scala/kyo/internal/StdioWireTransport.scala | 1 | 0 |
| shared/src/main/scala/kyo/internal/WireTransportAdapter.scala | 2 | 0 |
| **kyo-jsonrpc totals** | **189** | **0** |

### kyo-jsonrpc-http root (1 file, 4 conversions)

| File | allow converted | DEV removed |
|------|-----------------|-------------|
| src/main/scala/kyo/JsonRpcHttpTransport.scala | 4 | 0 |
| **kyo-jsonrpc-http totals** | **4** | **0** |

### Combined totals

- flow-allow conversions: 193
- DEV lines/blocks removed: 0
- Files touched: 29

## Phase C: DEV-comment removal

No `// DEV:` lines were present in either module root. Implementation agents
were instructed not to use DEV-tags during this campaign. Zero lines removed.

## Phase D: Final residual scan

kyo-jsonrpc: PASS. Zero `// flow-allow:` or `// DEV:` markers remain.
kyo-jsonrpc-http: PASS. Zero `// flow-allow:` or `// DEV:` markers remain.

Both scripts exited 0.

## Phase E: Cross-platform test results

All 6 suites passed after the strip.

| Suite | Tests | Result |
|-------|-------|--------|
| kyo-jsonrpc (JVM) | 173 | PASS |
| kyo-jsonrpcJS | 169 | PASS |
| kyo-jsonrpcNative | 169 | PASS |
| kyo-jsonrpc-http (JVM) | 4 | PASS |
| kyo-jsonrpc-httpJS | 4 | PASS |
| kyo-jsonrpc-httpNative | 4 | PASS |

Total: 523 tests, 0 failures.
