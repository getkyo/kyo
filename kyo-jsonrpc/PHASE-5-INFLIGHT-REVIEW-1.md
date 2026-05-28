# Phase 5 In-Flight Review (pulse 1)

Pulse 1: 2026-05-28T06:05Z
Files reviewed:
- `kyo-jsonrpc/shared/src/main/scala/kyo/CancellationPolicy.scala` (62 lines)
- `kyo-jsonrpc/shared/src/main/scala/kyo/internal/CancellationEngine.scala` (112 lines)
- `kyo-jsonrpc/shared/src/main/scala/kyo/internal/JsonRpcEndpointImpl.scala` (grep hits)
- `kyo-jsonrpc/shared/src/test/scala/kyo/CancellationPolicyTest.scala` (NOT FOUND)

---

## Plan anchor

- **CancellationPolicy.scala replaced (FAIL-partial)**: The `final case class CancellationPolicy` is present with 6 fields. The IMPLEMENTATION.md spec at line 316 lists 5 fields; the implementation adds a 6th field `extractId: CancellationPolicy.IdExtractor` and a companion type alias `type IdExtractor = Maybe[Structure.Value] => Maybe[JsonRpcId]`. Neither `extractId` nor `IdExtractor` appear anywhere in IMPLEMENTATION.md or DESIGN.md. This is an undocumented scope expansion. Whether intentional or accidental is unresolved — flag for agent confirmation before accepting.
- **internal/CancellationEngine.scala present**: YES, 112 LOC. Three methods: `handleInboundCancel`, `buildAndEnqueueOutboundCancel`, `handleTimeout`. Naming diverges slightly from spec (`buildAndEnqueueOutboundCancel` vs spec's `handleOutboundCancel`), but functionally covers the contract.
- **JsonRpcEndpointImpl.scala touchpoints applied**: 20+ grep hits for "cancel"/"policy"/"cancellation". Reader-fiber step-1 intercept present (lines 361-366), cancel method present (lines 168-196), `protectedMethods` check present (line 183), `callerRegistry` absent-warning present (line 171). Coverage looks complete.
- **CancellationPolicyTest.scala labels**: 0/14. File does not exist yet.

---

## Convention sweep

- **em-dashes**: 0
- **asInstanceOf**: 0
- **Option[**: 0 (all uses are `Maybe`)
- **semicolons**: 0

---

## Reward-hacking

| Pattern | Verdict | Citation |
|---------|---------|---------|
| Tests weakened to pass | N/A | No test file written yet |
| `Result.Failure(_)` catch-all in existing tests | PRESENT (pre-existing) | `JsonRpcEndpointTest.scala:211`, `:409` — not Phase 5 regressions, but note for remediation |
| Compile errors hidden by not running `Test/compile` | BLOCKED | `CancellationPolicy.scala:26,29` — `Frame.internal` in `package kyo` causes "Frame cannot be derived within the kyo package" compile error. Build is currently broken. |

---

## Drifting

| Pattern | Verdict | Citation |
|---------|---------|---------|
| Extra field `extractId` not in spec | DRIFT | `CancellationPolicy.scala:12,20` — `IdExtractor` type and `extractId` field are absent from IMPLEMENTATION.md §Phase5 and DESIGN.md. The spec says `encodeParams: ParamsEncoder` but nothing about an `IdExtractor` in the case class. The inbound decode is arguably needed for `CancellationEngine.handleInboundCancel`, but it was not specced as a field on `CancellationPolicy`. |
| `Frame.internal` in `package kyo` | VIOLATION | `CancellationPolicy.scala:26,29` — `(using summon, Frame.internal)` inside `package kyo` triggers Kyo's lint rule "Frame cannot be derived within the kyo package." STEERING.md and project conventions prohibit `Frame.internal`. This causes compile failure. |
| `Sync.defer` wrapping `Structure.encode` inside a `private val` lambda | MINOR CONCERN | `CancellationPolicy.scala:26,29` — the lambda captures `summon` at definition time, not at call time. If `Schema` resolution is call-site-driven this is fine, but if it needs a fresh derivation the `summon` at `private val` initialization time could be wrong. Low risk given `derives Schema` on the case classes, but warrants a second look. |
| `CancellationEngine` comment says `AllowUnsafe.embrace.danger` is justified but `Log.live.unsafe` is used directly | MINOR | `CancellationEngine.scala:25-28` — `discard(Log.live.unsafe.warn(...))` accesses `Log.live` directly rather than through a passed-in logger. This assumes the default `Log.live` is always correct; diverges from the pattern in the rest of the engine which uses `Log.warn` (the safe path). |

---

## Scope-cutting (per tests 51-64)

| Test # | Present? | Strong assertion? |
|--------|----------|-------------------|
| 51 | NO | N/A |
| 52 | NO | N/A |
| 53 | NO | N/A |
| 54 | NO | N/A |
| 55 | NO | N/A |
| 56 | NO | N/A |
| 57 | NO | N/A |
| 58 | NO | N/A |
| 59 | NO | N/A |
| 60 | NO | N/A |
| 61 | NO | N/A |
| 62 | NO | N/A |
| 63 | NO | N/A |
| 64 | NO | N/A |

All 14 tests are missing. `CancellationPolicyTest.scala` does not exist. SLOT-A is still in the production-code writing phase; this is expected if tests come second, but the agent must not close Phase 5 without all 14 tests present and passing.

---

## CRITICAL

1. **Build is broken.** `CancellationPolicy.scala:26,29` uses `Frame.internal` inside `package kyo`. The compiler rejects this with "Frame cannot be derived within the kyo package." The fix is to thread `using Frame` through the `ParamsEncoder` lambdas or restructure so the `Frame.internal` call site is outside the `kyo` package. This must be resolved before any test run is possible.

2. **`IdExtractor` / `extractId` field is undocumented scope expansion.** The IMPLEMENTATION.md Phase 5 spec defines `CancellationPolicy` with 5 fields (no `extractId`). The live code has 6. Agent must either (a) confirm this was an intentional design fix and update IMPLEMENTATION.md/DESIGN.md, or (b) move `extractId` out of the public case class into `CancellationEngine` as a local extraction function. A public-API field change with no spec update is a drift violation.

3. **0 of 14 Phase 5 tests written.** All tests 51-64 are absent. Phase 5 cannot be called complete until all 14 are present and passing.

---

## MINOR

1. `buildAndEnqueueOutboundCancel` vs spec's `handleOutboundCancel`: name difference is minor and the semantics match, but IMPLEMENTATION.md should be updated to reflect the actual name to keep the supervision plan accurate.

2. `Log.live.unsafe.warn` in `CancellationEngine.handleInboundCancel` (line 25): accesses global `Log.live` directly rather than using the safe `Log.warn` path. Since the entire block is already inside `Sync.defer`, `Log.warn` (the safe call) could be used instead without any `unsafe` access. This is a code-quality nit but also removes an `AllowUnsafe` site.

3. Pre-existing `Result.Failure(_)` catch-all in `JsonRpcEndpointTest.scala` at lines 211, 409: these are not Phase 5 regressions, but since the test file is being touched, they should be tightened to specific error codes per the feedback rule on weak assertions.

---

## Compile state

```
sbt 'kyo-jsonrpc/Test/compile' 2>&1 | tail -10:

[error] -- Error: .../kyo-jsonrpc/shared/src/main/scala/kyo/CancellationPolicy.scala:26:98
[error] 26 | (id, _) => Sync.defer(Structure.encode(LspCancelParams(id))(using summon, Frame.internal))
[error]    | Frame cannot be derived within the kyo package.
[error] -- Error: .../kyo-jsonrpc/shared/src/main/scala/kyo/CancellationPolicy.scala:29:111
[error] 29 | (id, reason) => Sync.defer(Structure.encode(McpCancelParams(id, reason))(using summon, Frame.internal))
[error]    | Frame cannot be derived within the kyo package.
[error] two errors found
[error] (kyo-jsonrpc / Compile / compileIncremental) Compilation failed
```

---

## Recommendation: STEER

The production code is structurally sound (CAS logic, suppress flag, inbound/outbound split, protectedMethods check all present and correct per design) but has two blockers:

1. Fix `Frame.internal` in `CancellationPolicy.scala` (compile failure).
2. Resolve undocumented `extractId` field (spec drift or update the spec).

After those are resolved, write all 14 tests before marking Phase 5 done.
