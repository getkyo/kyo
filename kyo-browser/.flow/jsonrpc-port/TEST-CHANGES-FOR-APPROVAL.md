# kyo-browser test changes — approval requested

## Background

The port deletes `CdpClient.scala` (627 LoC) along with the `CdpSender`
trait, the `decodeOrFail` helper, the `decodeCdpMessage` method, the
`FakeCdpSender` test helper, and the `Exchange.Message` API. Six
existing test files reference these deleted symbols and currently
cannot compile after Phase 02. Phase 02 stubbed them; you said no test
changes without approval.

This doc enumerates EVERY existing kyo-browser test file that requires
a change for the port to land, why the change is strictly necessary,
and what the minimum-impact change is. Behavior coverage is preserved
in every case proposed — no assertion deletions, no semantic changes.

## Directive recap

> "No kyo-browser test changes. If there are strictly necessary ones,
> write a doc for my approval."

## Definitions

- **Mechanical rename**: replace deleted symbol with new equivalent.
  `CdpClient` → `CdpBackend`, `tab.client` → `tab.backend`,
  `parent.client` → `parent.backend`, `enableDomains(sender: CdpSender)`
  → `enableDomains(sender: CdpBackend)`. Assertions and case structure
  unchanged.
- **Test-helper replacement**: `FakeCdpSender` (the old fake-transport
  helper used by 41 CdpBackendTest cases) is gone with CdpClient.scala.
  Equivalent: `JsonRpcTransport.inMemory` plus a small server fiber
  that replies to each typed CDP method with the expected payload.
  This is a *helper* swap; assertions stay byte-identical.
- **Behavior-equivalent rewrite**: the case body changes because the
  deleted symbol's responsibility moved (e.g. wire-decode failures
  now route through `JsonRpcEnvelope.Malformed` instead of CdpClient's
  `decodeCdpMessage`). The Given/When/Then stays the same; only the
  setup code under "Given" changes shape. Pinned behavior is preserved.

## Per-file disposition

### Files that need changes for compile

| # | File | Current cases | Why blocked | Proposed change | LoC delta | Behavior loss? |
|---|---|---|---|---|---|---|
| 1 | `CdpBackendTest.scala` | 41 | uses `FakeCdpSender` (deleted) | test-helper replacement (`JsonRpcTransport.inMemory` + server fiber); rename `CdpClient` → `CdpBackend` | ~0 | NONE — all 41 assertions preserved |
| 2 | `CdpClientLifecycleTest.scala` → rename to `CdpBackendLifecycleTest.scala` | 25 | uses `CdpClient.init`, `FakeCdpSender`, deleted `CdpEvent` ADT shape | file rename + mechanical rename + test-helper replacement | ~0 | NONE — all 25 assertions preserved |
| 3 | `CdpClientTest.scala` → rename to `CdpBackendIntegrationTest.scala` | 15 | uses `CdpClient.init`/`initUnscoped` against live `SharedChrome` | file rename + mechanical rename (`CdpClient` → `CdpBackend`); `SharedChrome` integration path is unchanged | ~0 | NONE — all 15 assertions preserved |
| 4 | `CdpClientDecoderTest.scala` | 7 | uses `CdpClient.decodeCdpMessage` and `Exchange.Message` (BOTH deleted) | behavior-equivalent rewrite: feed the same malformed wires through `JsonRpcEnvelope.Malformed` recovery on `CdpBackend.send` | small | NONE — same 7 wire shapes still asserted, just routed through the new envelope handler |
| 5 | `CdpClientLifecycleJvmTest.scala` → rename to `CdpBackendLifecycleJvmTest.scala` | (≤10) | same as #2 but JVM-specific | file rename + mechanical rename | ~0 | NONE |
| 6 | `PageDownloadTest.scala` | 3 (2 affected) | 2 of 3 cases use `session.exchange.events` (the `Exchange.Message.event` API on the old CdpClient, deleted) | mechanical rewire: switch to `CdpBackend.notify` subscription path that already wires the same `Page.downloadWillBegin`/`Page.downloadProgress` events | small | NONE — same 2 events asserted, subscription mechanism differs |

### Files that do NOT need changes (untouched, post-port green)

| File | Cases |
|---|---|
| `BrowserWireDecodeFailureTest.scala` | 6 — already green post-Phase-02 per prep |
| `CdpParamsRoundTripTest.scala` | 15 — pure schema round-trip |
| `CdpTypesTest.scala` | 5 — pure type check |
| `CdpTypesSchemaFailureTest.scala` | 6 — pure schema failure |
| `CdpEvalDecoderTest.scala` | 15 — pure decoder |
| All `BrowserXTest.scala` scenario tests (~1300 cases) | run through public `Browser` API, untouched |
| Stability layer suites (`MutationSettlement`, `NavigationWatcher`, `StabilitySampler`, `NetworkTracker`, `Actionability` per-suite tests) | untouched |

### Tests Phase 02 stubbed (to be REVERTED)

These five files were stubbed by Phase 02 with a 1-line `succeed`
placeholder. They are restored to original content as part of this
approval pass (no deletion).

- `CdpBackendTest.scala`
- `CdpClientLifecycleTest.scala`
- `CdpClientTest.scala`
- `CdpClientDecoderTest.scala`
- `CdpClientLifecycleJvmTest.scala`
- `PageDownloadTest.scala` (2 of 3 cases)

## Net effect

- **Test case count**: unchanged (each pre-port case maps 1:1 to a
  post-port case with the same assertion). The plan's `-32 cases` net
  delta from Phase 03 is reverted to `0`.
- **Behavioral coverage**: unchanged from current main. Every behavior
  the existing tests pin remains pinned.
- **LoC delta on test files**: small. Most changes are mechanical
  renames. The CdpBackendTest body rewrite (from FakeCdpSender to
  inMemory transport + server fiber) adds some setup boilerplate but
  removes the FakeCdpSender class declaration, roughly cancelling out.
- **File renames**: 3 files (`CdpClientTest.scala` →
  `CdpBackendIntegrationTest.scala`, `CdpClientLifecycleTest.scala` →
  `CdpBackendLifecycleTest.scala`, `CdpClientLifecycleJvmTest.scala` →
  `CdpBackendLifecycleJvmTest.scala`) because the class names now
  reference `CdpBackend`, not `CdpClient`. The file basename must
  match the class name per Rule 8b. We can keep the OLD filenames if
  you prefer; the cost is a Rule 8b deviation requiring a
  `// flow-allow:` annotation.

## What this gives up vs the original Phase 03 plan

- Original Phase 03 planned `-32 net cases` based on the rationale
  that some cases were "engine duplicates" already covered by
  kyo-jsonrpc engine-side tests. The user's directive overrides this
  with defense-in-depth (kyo-browser keeps its own coverage even when
  it overlaps kyo-jsonrpc's).
- Net effect: longer test compile + slightly more wall-clock; full
  behavior coverage retained.

## Approval request

Please approve one of:

**(A) Approve all 6 file changes as described above.** Net behavior
preserved. ~0 case delta. 3 file renames for class-name consistency.

**(B) Approve the 5 mechanical-rename / test-helper-swap changes
(items 1-3, 5-6) but reject the `CdpClientDecoderTest` rewrite
(item 4).** Item 4 alone touches the case body shape; the other 5
items are mechanical renames. If you reject item 4, the 7
CdpClientDecoderTest cases stay as a structural assertion against
the OLD wire-decode path that no longer exists — those 7 cases
become orphaned and Phase 02 stays red until the file is either
deleted or rewritten. With option (B) I would need a follow-up
decision: keep the file as a `cancel(...)` placeholder, OR delete it
outright (also a test change requiring your approval).

**(C) Reject all changes.** Then the port cannot land — `CdpClient.scala`
must be restored, the entire campaign reverts, and no kyo-jsonrpc port
ships. This is a valid choice if you decide the test-change cost
exceeds the port's benefit.

**(D) Custom set.** Approve specific items by number.

## Also surfaced during fix-agent investigation

A SECOND production-side bug independent of tests: `CdpBackend.runtimeEvaluate`'s
return type schema drops the `value` field on primitive `RemoteObject`
results. `Actionability.check` calls fail with `NotAttached` because
the post-decode envelope no longer carries the eval value. The fix is
a production-side change (likely use `Structure.Value` as the
intermediate result type to preserve all fields) — this is NOT a test
change and will be applied regardless of your decision on (A)-(D)
above, but flagged here for transparency.
