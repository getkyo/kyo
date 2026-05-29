# Phase 4 decisions

Decision 64: Phase 04 dead-code cleanup
Removed: CdpEnvelope, CdpWireMessage, FallbackIdEnvelope from CdpTypes.scala.
Each was a self-reference only - no consumer code in the campaign branch
post-Phase 03. The original plan also listed CdpReply, CdpNoParams,
CdpEventParams, CdpEvent for deletion, but each is still in use (CdpReply:
26 refs across BrowserEval, Actionability, CdpEvalEnvelope; CdpNoParams:
64 refs across typed sendUnit wrappers; CdpEventParams: 5 refs; CdpEvent:
30 refs). The plan's broader deletion scope was based on the engine
absorbing all envelope/event handling, but kyo-browser still uses these
types for its own internal wire-string operations (the synthetic CdpReply
wire from runtimeEvaluate, typed CdpNoParams for sendUnit).
Time: 2026-05-29T00:00Z

Decision 65: FallbackIdEnvelope test-fixture replacement
CdpBackendLifecycleJvmTest.scala (object CdpBackendFixtureServer) used
FallbackIdEnvelope to decode the `id` field from inbound CDP request wire
frames in the replyOk fixture helper. With FallbackIdEnvelope deleted from
CdpTypes.scala, the fixture now defines a private FixtureIdEnvelope case
class (id: Maybe[Int] = Absent) with derives Schema inline in the
CdpBackendFixtureServer object. This is a test-only type with no production
scope; keeping it local avoids polluting CdpTypes with a fixture-only
concern. The logic in replyOk is byte-identical; only the decoded type name
changes.
Time: 2026-05-29T00:00Z
