# Phase 07 audit

Time: 2026-05-30T21:30Z
HEAD: 8162e5152
Phase commit: 8162e5152
Plan cites: ./design/realignment-plan.md §Phase G (line 393)
Design cites: ./design/realignment-plan.md §Resolutions #1 (line 448)

## Test count

| Leaf | Status | Notes |
|---|---|---|
| 1: Hoist 7 nested types to top-level (JsonRpc prefix) | PRESENT_STRICT | All 7 files created at `kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpc{CancellationPolicy,ProgressPolicy,UnknownMethodPolicy,IdStrategy,ExtrasEncoder,Framer,WireTransport}.scala`; each has scaladoc, `package kyo`, JsonRpc prefix |
| 2: Update all references in main + tests + downstream | WEAKENED | Main src clean save 1 stale @see reference (JsonRpcId.scala:18); 67 test references still use companion-alias forms (`JsonRpcHandler.IdStrategy.SequentialLong` etc.); 12 references in kyo-jsonrpc-http and kyo-browser also via aliases |
| 3: `+Out` covariance on Pending | PRESENT_STRICT | `JsonRpcHandler.scala:168`: `final class Pending[+Out] private[kyo]`; Out only appears as `val result: Out < (Async & Abort[...])` (covariant position) |
| 4: `JsonRpcCodec.default` preset | PRESENT_STRICT | `JsonRpcCodec.scala:34`: `val default: JsonRpcCodec = Strict2_0`; scaladoc references `JsonRpcHandler.Config.default` for alignment |
| 5: `JsonRpcMessageGate.noop` | PRESENT_STRICT | Phase-06 carry-over, confirmed at `JsonRpcMessageGate.scala:48` |
| 6: `JsonRpcRoute.apply` -> `request` rename | PRESENT_STRICT | `JsonRpcRoute.scala:123` defines `def request`; `JsonRpcRoute.scala:132` `inline def apply` delegates to `request[In, Out](name)(handler)`; both compile and resolve |

## CONTRIBUTING.md violations

None observed in the 7 new files:
- Each has `package kyo`, scaladoc (12-23 lines, within the 8-35 guideline).
- Public APIs have explicit return types (verified across all 7 files).
- No `protected`; `private[kyo]` used correctly on `JsonRpcUnknownMethodPolicy` smart-constructor and `JsonRpcHandler.Pending`.
- `Frame` propagated through public Kyo APIs (e.g. `JsonRpcFramer.frame`/`parse`, `JsonRpcWireTransport.send`/`incoming`/`close`).
- Kyo types used: `Maybe` (not `Option`), `Chunk` (not `List`), `Result` not seen used here, `CanEqual` derived where appropriate.

## Unsafe markers

No new `AllowUnsafe` sites introduced in this phase (verified by grep across the 7 new files and the 2 modified-with-aliases files; only `Sync.defer` boundaries, no Unsafe layer changes).

## Cross-platform consistency

- Platforms checked: jvm (impl-compile-jvm-001.log [success]), js + native compile not run this phase per the plan (Phase H is the cross-platform gate).
- Per-platform deltas: none expected; the hoisted files live in `shared/src/main`; no platform-specific source touched.

## Naming convention compliance

- 7 hoisted types each carry the `JsonRpc` prefix mandated by Resolution #1 of the plan.
- `JsonRpcExtrasEncoder` is an opaque type with companion factory carve-out (`apply`, `empty`, `const`, `resolve` extension) per FLOW Decision #30(b).
- Match against kyo-http pattern (HttpFilter, HttpStatus, HttpMethod etc.) confirmed.

## Steering deviation

`git diff --name-only HEAD~1 HEAD` matches `files_produced` (7 new) + `files_modified` (14 src) per the plan slice. No off-plan source touched. The `.flow/api-realignment/phases/phase-06/audit.md` shipped in the same commit is acceptable carry-forward of the prior phase's audit artifact.

## Anti-flakiness measures

- 189/189 tests passed; no new tests required for the hoist (pure rename, behavior preserved).
- No `Thread.sleep`, no `synchronized`, no blocking primitives introduced.

## Architecture substitution check

- Design intent (Resolution #1, line 448-450): hoist 9 Phase-03 nested types back to top-level with `JsonRpc` prefix, mirroring kyo-http verbatim.
- HEAD reality: 7 hoisted (the 2 remaining — `JsonRpcMessageGate` from Phase 06, plus `JsonRpcId`-and-others already top-level — are accounted for separately).
- Verdict: MATCH.

The backward-compat aliases in `JsonRpcHandler.scala:175-206` and `JsonRpcTransport.scala:28-41` are explicitly sanctioned by decisions.md Decision 2 and Decision 7. They are transitional shims, not architecture substitutions: the canonical name is the top-level `JsonRpc<X>` and the alias is a `type X = JsonRpc<X>` + `val X: JsonRpc<X>.type = JsonRpc<X>` re-export. The aliases delegate, do not duplicate definitions, and their scaladoc consists of a single `@see` link — they do not compete with the canonical types for documentation real estate.

## Documentation drift

- Scaladoc additions: 7 new top-level files have 12-23 lines of scaladoc each (within the CONTRIBUTING 8-35 line guideline). Each cites `JsonRpcHandler.Config` or `JsonRpcTransport.fromWire` as the pairing-point, providing readers a path back to usage.
- Beyond plan intent: no. The scaladoc content is a re-organization of what was already attached to the nested classes in Phase 03, plus the `@see` cross-references the hoist now warrants.

## Findings (categorized)

### BLOCKER

None. The phase delivered every plan item, tests are 189/189, compile is green on all three modules.

### WARN

1. **Test call-sites use compat-alias paths exclusively** — `kyo-jsonrpc/shared/src/test/` has 67 references to forms like `JsonRpcHandler.IdStrategy.SequentialLong` and `JsonRpcHandler.ExtrasEncoder.empty` (sample: `JsonRpcHandlerIdStrategyTest.scala:10,23,38,50`; `JsonRpcHandlerExtrasEncoderTest.scala:12,13,21,32`). The aliases resolve and tests pass, but the new top-level names are not exercised by the test suite at all. This is a stylistic concern, not a correctness concern: if the aliases are intended as the stable surface, the test suite should document that; if the aliases are transitional, the test suite should migrate to the canonical names before the aliases are dropped. **Recommendation**: queue a follow-up sweep for Phase 08 prep (or `end/sweep-naming.md`) that migrates the 67 test call-sites + 12 downstream caller sites to the canonical `JsonRpc<X>` names, leaving aliases in place for one release as a deprecation window. The decision to keep or drop the aliases longer-term is an API stability call belonging to the README/release-notes work, not the implementation.

2. **`JsonRpcId.scala:18` scaladoc references the alias path** — already flagged in pulse-1; the `@see [[JsonRpcHandler.ExtrasEncoder]]` should be `@see [[JsonRpcExtrasEncoder]]` to point at the canonical name. Trivial doc hygiene; group with the sweep above.

### NOTE

1. **Aliases are a smell only if they outlive their transitional purpose.** Two-name visibility (`JsonRpcHandler.IdStrategy` vs `JsonRpcIdStrategy`) creates a search/discoverability tax for new users reading IDE autocomplete. The decisions.md framing ("backward-compat") implies aliases are transitional; if the intent is permanent dual visibility, README and scaladoc on the aliases should say so explicitly. End-of-campaign cleanup decision-point.

2. **Hoist scaladoc quality is uniformly good.** Each of the 7 new files names its presets, points to its config seam (`JsonRpcHandler.Config.<field>` or `JsonRpcTransport.fromWire`), and uses `@see` cross-references. No drift relative to Phase-06's pattern.

3. **No em-dashes detected in any changed file at HEAD.** Only match in the broader tree is the prior phase's audit.md — out of scope for this phase.

4. **`JsonRpcRoute.apply` alias is structurally distinct from the type aliases above.** It is an `inline def` delegating to `request`; it disappears at call site after inlining. The cost-benefit is different from the type-alias case (zero IDE-completion tax once inlined, vs the type aliases which remain visible). The `apply` keep-or-drop call is independent of the type-alias keep-or-drop call.

## Routing

- BLOCKER findings: none; SLOT-A launch of Phase 08 is not halted.
- WARN findings: TaskCreate for Phase 08 prep input — (a) sweep test + downstream call-sites to canonical `JsonRpc<X>` names; (b) fix `JsonRpcId.scala:18` `@see`.
- NOTE findings: TaskCreate for `end/sweep-naming.md` or `end/dev-strip-report.md` cleanup — document the alias retention policy in README before final release; revisit whether the type aliases should be deprecated or kept as a stable secondary surface.
