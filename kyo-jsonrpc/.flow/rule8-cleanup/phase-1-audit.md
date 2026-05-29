# Phase 1 Audit Report

HEAD: bec833d33 ("[jsonrpc] Rule 8a Phase 1: package-kyo verdicts + sub-symbol relocations")
Compared against: 02-design.md (v2), 05-plan.md (v3) Phase 1, phase-1-verify.md, phase-1-decisions.md.
Auditor: flow-phase-audit (Phase 1 post-commit deep audit).

## Verdict

PASS with WARN routing. BLOCKER count = 0. Phase 1 is committed and existing tests are green. Phase 2 may proceed (and has, with uncommitted scaffolding already on disk at JsonRpcResponse.scala and internal/JsonRpcRequest.scala).

## Existing test suite result

Ran `sbt 'project kyo-jsonrpc; test'`. Result: 108 tests, 12 suites, 0 failures, 0 ignored. Exit 0 in 15s. INV-009 holds at the Phase 1 boundary. Caveat: the working tree at the time of the run already contained uncommitted Phase 2 work (deleted kyo/JsonRpcRequest.scala, untracked kyo/JsonRpcResponse.scala, untracked kyo/internal/JsonRpcRequest.scala plus the new JsonRpcResponseTest); the suite is green in that mid-Phase-2 state, which is a strictly stronger signal than green-at-HEAD alone.

## BLOCKER findings

None.

## WARN findings (route to Phase 2 prep input)

### WARN-1: Phase 1 silently absorbed the audit/flow-allow-verdicts.md convention sweep on JsonRpcEndpointImpl.scala

Plan 05-plan.md:155-163 authorizes exactly one edit to `kyo/internal/JsonRpcEndpointImpl.scala`: rewrite the `IdStrategy.mkNextId(config.idStrategy)` callsite at line 735 to `IdStrategyEngine.mkNextId(...)`. Decisions log echoes this at phase-1-decisions.md:50 ("the only change to `JsonRpcEndpointImpl.scala` was the single callsite rewrite on line 735").

Reality in HEAD: 142 additions and 30 deletions in that file. 116 of the additions are `// flow-allow:` per-call rationales tracing back to audit/flow-allow-verdicts.md (the 113 unsafe-site + 31 unsafe-method-invocation flags listed at audit/flow-allow-verdicts.md:9-13). The remaining ~25 lines are refactors also traceable to that audit doc: removing redundant type ascriptions (lines 125/132/220/296/303 per audit/flow-allow-verdicts.md:38-43), extracting `val msg = WriterMsg.SendEnvelope(response)` + `// format: off` markers (audit/flow-allow-verdicts.md "Catalog calibration findings" 2), and extracting `val nowMs` (calibration finding 4).

Concrete diff anchors:
- 116 lines: `git show bec833d33 -- 'kyo-jsonrpc/shared/src/main/scala/kyo/internal/JsonRpcEndpointImpl.scala' | grep -E '^\+' | grep -c 'flow-allow'`
- 5 type-ascription removals: `val abortError: JsonRpcError = ...` becomes `val abortError = ...` at the 4 cancellation match sites, plus 2 sites of `val id: JsonRpcId = rawId.eval` becoming `val id = rawId.eval`.
- 3 sites: extract `val msg = WriterMsg.SendEnvelope(response)` and wrap in `// format: off` / `// format: on`.
- 1 site: extract `val nowMs = java.lang.System.currentTimeMillis()` then use `nowMs + config.requestTimeout.toMillis`.
- 2 sites: pure whitespace alignment changes inside `val tokenToDeadline` / `val methodMap` / `val nextIdFn` block.

Phase 1 verify report flagged 3 other files in the same family (`internal/CancellationEngine.scala`, `internal/ProgressEngine.scala`, `test/scala/kyo/CancellationPolicyTest.scala`) as unauthorized scope creep at phase-1-verify.md:63-69, and the supervisor took remediation path 1 (revert those three). But the verify report did NOT catch the same class of unauthorized changes on JsonRpcEndpointImpl ; the file was on the authorized list for the single-line callsite rewrite, so the verify report's plan-diff check did not flag the additional 140+ lines as out-of-scope. This is a class-C judgment gap the verify gates do not natively cover.

Substantive note: the audit verdicts themselves (audit/flow-allow-verdicts.md) are reasonable and the changes appear correct. The concern is scope discipline ; Phase 1's plan does NOT cite that audit doc as authorization, and the decisions log actively misreports the scope ("only change ... single callsite rewrite"). Route to Phase 2's prep input as a steering note: either (a) future phases must explicitly cite audit/flow-allow-verdicts.md as the authority before sweeping per-call annotations, or (b) the audit doc work needs its own dedicated phase entry in 05-plan.md.

### WARN-2: JsonRpcError.scala received 11 unplanned type-ascription additions

Plan 05-plan.md:280-293 authorizes a single edit to `kyo/JsonRpcError.scala`: add the top-of-file PUBLIC marker. Reality: the diff also adds explicit `: JsonRpcError` ascriptions to all 11 companion-object error constants (ParseError, InvalidRequest, ..., RequestFailed at lines 13-23). These are the 11 `public-api-missing-annotation` REFACTOR verdicts at audit/flow-allow-verdicts.md:19-29. Same class as WARN-1: the work is correct and traces to a real audit, but the plan did not authorize it and the decisions log does not document it (the only decisions log entry naming the file is Decision 5, which only describes the PUBLIC marker text).

Route to Phase 2 prep: same steering recommendation as WARN-1.

### WARN-3: JsonRpcCodecImpl.scala received 6 unplanned scala.Option-interop rationales

Plan 05-plan.md:131-153 authorizes adding the `cdpReservedKeys` val and rewriting the 2 callsites at lines 151 and 172. Reality: the diff adds 6 additional `// flow-allow: scala.Option arm; interop with stdlib Map.get` / `Iterator.find` comments at lines 63-71 and 154-161 of the post-Phase-1 file. These match the convention-sweep "Option-vs-Maybe" item in 05-plan.md:478 but that bullet is a sweep listed under Phase 1, so it's a borderline call ; if "Option-vs-Maybe" is a Phase 1 sweep target, the impl was authorized; if the sweep is meant as a check (no findings expected), then the impl exceeded scope.

Route to Phase 2 prep: clarify in 05-plan.md whether the per-phase "Convention sweep" bullet is a check-only gate or an authorization to add per-call rationales encountered during sweep.

### WARN-4: Plan and design contain a small factual error about ExtrasEncoder's pre-existing marker

Design 02-design.md:18 and the prep+plan describe an existing `// flow-allow: opaque-type companion carve-out (FLOW Decision #30 (b))` marker on ExtrasEncoder.scala:13 that "stays". Reality: that marker did NOT exist in HEAD~1 (git show HEAD~1:kyo-jsonrpc/shared/src/main/scala/kyo/ExtrasEncoder.scala confirms no such comment). The HEAD commit ADDS that marker. Net behavior is correct (the marker is now present), but the design's claim is factually wrong, which is a documentation drift NOTE for the Phase 2 prep input to correct.

## NOTE findings (informational)

### NOTE-1: IdStrategyEngine.scala carries a path-comment header

The new file at kyo-jsonrpc/shared/src/main/scala/kyo/internal/IdStrategyEngine.scala:1 begins with `// kyo-jsonrpc/shared/src/main/scala/kyo/internal/IdStrategyEngine.scala` ; a literal copy of the path label from the plan's fenced code block. This is harmless and matches the plan's code-block prefix convention, but it is unusual relative to the rest of the codebase (no other source file begins with its own path as a comment). Consider removing during a future hygiene pass or leaving as a campaign-trail breadcrumb.

### NOTE-2: 8b state is preserved as required

JsonRpcRequest.scala at HEAD contains both `case class JsonRpcRequest` and `case class JsonRpcResponse` top-level as expected (Phase 2 dissolves the file). Phase 1 brief item 4 verified.

### NOTE-3: JsonRpcResponse.scala and internal/JsonRpcRequest.scala already exist as uncommitted Phase 2 work

`git status` shows `kyo/JsonRpcRequest.scala` deleted and `kyo/JsonRpcResponse.scala` + `kyo/internal/JsonRpcRequest.scala` untracked. The test run that produced 108 green tests was against this mid-Phase-2 state, not pure HEAD. This is informational; the supervisor is mid-stream.

## PASS items (clean spots)

- INV-001 (public API stability): every PUBLIC symbol's FQN and signature is preserved. PASS.
- INV-002 (no dangling references): `grep "IdStrategy.mkNextId"` returns 0 hits across kyo-jsonrpc/shared/src/. PASS.
- INV-003 (private[kyo] rationale coverage): HandlerCtx.scala:13/27, JsonRpcEndpoint.scala:5/54, JsonRpcMethod.scala:17/19/21, JsonRpcRequest.scala:11/18, UnknownMethodPolicy.scala:3 all carry per-declaration `// flow-allow:` rationales. PASS.
- INV-007 (PUBLIC marker coverage): all 14 kyo/*.scala files in HEAD (excluding kyo/internal/) carry a top-of-file `// flow-allow: PUBLIC <rationale>` marker. JsonRpcRequest.scala carries the "PUBLIC wire-shape pair" marker as a placeholder ; its content is Phase-2 dissolved. PASS.
- PUBLIC marker rationale quality: 13/13 rationales cite a concrete user-facing entry point (Config field name, function signature, or test reference). None are vague or copy-pasted. Examples checked: CancellationPolicy, JsonRpcId, MessageGate, JsonRpcMethod, UnknownMethodPolicy. PASS.
- IdStrategyEngine relocation faithfulness: kyo/internal/IdStrategyEngine.scala matches plan code block at 05-plan.md:19-42 verbatim (modulo the path-comment header noted at NOTE-1). PASS.
- JsonRpcCodec sub-symbol relocation: `cdpReservedKeys` correctly relocated as `private val` inside `internal.JsonRpcCodecImpl` (visible from anonymous-class bodies). PASS.
- IdStrategy companion fully removed (since mkNextId was its only member) per plan AFTER block at 05-plan.md:76-85. PASS.
- 8b state preserved as required for Phase 2 (PASS, see NOTE-2).
- No source-tree em-dashes, no other LLM-tells in modified files. PASS.

## Summary of audit gaps the verify gates did not catch

The phase-1-verify report (FAIL with 3-file scope creep) correctly flagged the surface-level scope creep on the three internal/test files, but did NOT flag the much larger sweep on JsonRpcEndpointImpl.scala (~140 additions), JsonRpcError.scala (11 ascriptions), or JsonRpcCodecImpl.scala (6 Option-interop rationales). The class-C judgment gap is structural: the verify plan-diff gate checks "are all modified files in the authorized list?" but not "are the modifications inside each authorized file limited to what the plan describes?" That is a meta-skill observation worth feeding into a /mature-skill cycle for flow-verify if the supervisor wants per-file diff-scoping coverage in the future.
