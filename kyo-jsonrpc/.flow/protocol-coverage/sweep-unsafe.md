# sweep-unsafe.md

Topic: `unsafe` discipline across Phase 01..05 (`b07967942..HEAD`)
Scope: production sources under `kyo-jsonrpc/{shared,jvm}/src/main/` and `kyo-jsonrpc-http/src/main/`. Test code is excluded per the sweep contract.
Per `feedback_kyo_sql_safe_only`: unsafe is permitted only for justified bridging. Per Decision #32: every unsafe site must carry a `// flow-allow:` rationale.

## Coverage

Files touched by Phase 03..05 that contain unsafe sites:

- `kyo-jsonrpc/shared/src/main/scala/kyo/internal/FramerImpl.scala` (NEW, Phase 03)
- `kyo-jsonrpc/shared/src/main/scala/kyo/internal/StdioWireTransport.scala` (NEW, Phase 03) — no unsafe sites
- `kyo-jsonrpc/shared/src/main/scala/kyo/internal/WireTransportAdapter.scala` (NEW, Phase 03) — no unsafe sites
- `kyo-jsonrpc/shared/src/main/scala/kyo/WireTransport.scala` (NEW, Phase 03) — no unsafe sites
- `kyo-jsonrpc/jvm/src/main/scala/kyo/internal/UdsWireTransport.scala` (NEW, Phase 04)
- `kyo-jsonrpc-http/src/main/scala/kyo/JsonRpcHttpTransport.scala` (NEW, Phase 05)

Files NOT touched by Phase 01..05 (pre-existing unsafe sites; out of sweep scope but checked for context): `JsonRpcEndpointImpl.scala`, `CancellationEngine.scala`, `IdStrategyEngine.scala`. `git diff b07967942..HEAD` on these files shows zero added unsafe lines beyond one onComplete handler block already audited by the prior unsafe sweep.

Unsafe sites surveyed in this sweep: **11** (4 in FramerImpl, 5 in UdsWireTransport, 2 in JsonRpcHttpTransport).

## Per-site verdicts

### FramerImpl.scala (Phase 03)

| Line | Construct | Rationale | Verdict |
|------|-----------|-----------|---------|
| 16 | `bufRef.unsafe.get()(using AllowUnsafe.embrace.danger)` | "AtomicRef.Unsafe.get/set inside Sync.defer for leftover buffer; single-fiber stream consumption" (L15) | VALIDATED |
| 19 | `bufRef.unsafe.set(left)(using AllowUnsafe.embrace.danger)` | same as L15 | VALIDATED |
| 35 | `bufRef.unsafe.get()(using AllowUnsafe.embrace.danger)` | "AtomicRef.Unsafe.get/set inside Sync.defer for leftover buffer; single-fiber stream consumption" (L34) | VALIDATED |
| 38 | `bufRef.unsafe.set(left)(using AllowUnsafe.embrace.danger)` | same as L34 | VALIDATED |

Notes: rationale is specific (names mechanism + concurrency assumption: single-fiber `mapChunk` consumer). `AtomicRef.init` is created via the safe `AtomicRef.init[Chunk[Byte]](Chunk.empty)` at L12, so the suspension boundary is correctly placed. Could be refactored to thread `Sync` through `AtomicRef.get`/`set` (safe variants), but the cost is a doubled effect lift inside a hot per-chunk path; the rationale's "single-fiber stream consumption" invariant justifies the bridge.

### UdsWireTransport.scala (Phase 04)

| Line | Construct | Rationale | Verdict |
|------|-----------|-----------|---------|
| 14 | `AtomicRef.Unsafe.init[Maybe[SocketChannel]](Absent)` | comment block L10-12 explains MVP single-client model | WARN |
| 19 | `activeChannelRef.get()` | "AtomicRef.Unsafe.get inside Sync.defer for SocketChannel handoff" (L18) | VALIDATED |
| 31 | `activeChannelRef.get()` | "AtomicRef.Unsafe access for accept-then-read MVP" (L30) | VALIDATED |
| 35 | `activeChannelRef.compareAndSet(...)` | piggybacks L30 rationale | VALIDATED |
| 52 | `activeChannelRef.get().foreach(_.close())` | "AtomicRef.Unsafe.get inside Sync.defer for SocketChannel handoff" (L51) | VALIDATED |

WARN at L14: the field-level `AtomicRef.Unsafe.init` is initialized at class-construction time, NOT inside `Sync.defer`. This means a caller that constructs `new UdsWireTransport(server)` outside a Sync boundary will trigger `AllowUnsafe.embrace.danger` evaluation eagerly. The class is `final private[kyo]` and the only known constructor call site is `JsonRpcTransport.unixDomain` (which is presumably inside `Sync.defer`), but the safer pattern (used in `JsonRpcHttpTransport.webSocket` at L16) is to do `Sync.defer(AtomicRef.Unsafe.init(...))` inside the factory and capture it in a `val`. The current code carries the `// Single client-at-a-time MVP` block comment but no inline `// flow-allow:` annotation at L13/L14 specifically asserting the init-outside-Sync invariant.

### JsonRpcHttpTransport.scala (Phase 05)

| Line | Construct | Rationale | Verdict |
|------|-----------|-----------|---------|
| 16 | `Fiber.Promise.Unsafe.init[Unit, Async]()` | "Unsafe Promise used as a close gate; completed by transport.close() or Scope.ensure" (L15) | SHOULD-REFACTOR |
| 19 | `doneRef.completeUnitDiscard()(using AllowUnsafe.embrace.danger)` | piggybacks L15 | VALIDATED (downstream of L16) |
| 43 | `doneRef.completeUnitDiscard()(using AllowUnsafe.embrace.danger)` | implicitly relies on L15 rationale; no inline annotation at site | WARN |

SHOULD-REFACTOR at L16: `Promise.init` and `Promise.completeDiscard` both have safe variants returning `Promise[E,A] < Sync` and `Unit < Sync` respectively (kyo-core/Fiber.scala:449, 478). The Unsafe variant is being used despite already being inside a `for`-comprehension lifted with `Sync.defer(...)` at L16. The safe replacement is:

```
doneRef <- Fiber.Promise.init[Unit, Async]()
...
Scope.ensure(doneRef.completeUnitDiscard().andThen(...))
```

This satisfies `feedback_kyo_sql_safe_only` (no bridging case here: nothing forces the Unsafe path) and aligns with `feedback_no_unsafe`. The current rationale "Unsafe Promise used as a close gate" does NOT explain why the safe Promise cannot be used; it only describes the promise's purpose.

WARN at L43: the `close()` method's `completeUnitDiscard` site has no inline `// flow-allow:` annotation. If L16 is refactored to safe Promise, this site disappears. If retained, it needs its own rationale per Decision #32.

## Summary table

| Site count | Verdict |
|-----------:|---------|
| 8 | VALIDATED |
| 2 | WARN |
| 1 | SHOULD-REFACTOR |

## Concrete refactor recommendations

1. **`JsonRpcHttpTransport.scala:16,19,43`** — replace `Fiber.Promise.Unsafe.init` + `completeUnitDiscard(using AllowUnsafe...)` with safe `Fiber.Promise.init` + `completeUnitDiscard()`. The whole block is already `<- ... in a Sync-effectful for-comprehension. This is the cleanest of the three findings and removes 3 unsafe usages outright.

2. **`UdsWireTransport.scala:13-14`** — add an explicit `// flow-allow:` annotation at L13 documenting why class-construction-time `AtomicRef.Unsafe.init` is acceptable (i.e. constructor is only invoked from inside `Sync.defer` in `JsonRpcTransport.unixDomain`). Alternative: take an `AtomicRef.Unsafe[...]` as a constructor parameter and have the factory build it inside `Sync.defer`, matching the `JsonRpcHttpTransport` capture pattern.

3. **`FramerImpl.scala`** — no action; rationales are concrete and the single-fiber invariant is correctly named.
