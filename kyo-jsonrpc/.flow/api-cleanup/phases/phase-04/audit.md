# Phase 04 post-commit audit

Commit: `0d8c6da8f` "[jsonrpc] Phase 04: Config alignment (default, require, fluent setters)"
Audit date: 2026-05-30
Audit basis:
- `kyo-jsonrpc/.flow/api-cleanup/design/02-design.md` §10 "Config alignment"
- `kyo-jsonrpc/.flow/api-cleanup/design/05-plan.md` Phase 4
- `kyo-jsonrpc/.flow/api-cleanup/phases/phase-04/decisions.md`
- `kyo-jsonrpc/.flow/api-cleanup/phases/phase-04/verify.md`
- `git show 0d8c6da8f`

## Summary

- BLOCKER: 0
- WARN: 0
- NOTE: 3

The phase landed exactly the kyo-http-style Config alignment the design called for. The one deliberate deviation (dropped `requestTimeout < Duration.Zero` guard) is sound — kyo's `Duration` opaque type clamps negative inputs to `Duration.Zero` at construction (`kyo-data/.../Duration.scala:60`), so the guard would be permanently dead. Decision 2 documents this with the correct rationale, and Decision 3 substitutes two positive boundary-acceptance tests in its place.

## Check matrix

| # | Check | Status | Evidence |
|---|---|---|---|
| 1 | 9 fluent setters present with bare-name convention | PASS | `JsonRpcEndpoint.scala:415-424` defines `codec`, `cancellation`, `progress`, `unknownMethod`, `gate`, `maxInFlight`, `requestTimeout`, `idStrategy`, `progressResetsTimeout`. No `withX` prefixes. Matches `HttpServerConfig` template per Decision 4. |
| 2 | `derives CanEqual` on Config | PASS | `JsonRpcEndpoint.scala:414` `... ) derives CanEqual:`. Test "Config.default == Config.default (CanEqual derivation)" exercises it. |
| 3 | `val default: Config` in companion | PASS | `JsonRpcEndpoint.scala:427` `val default: Config = Config()`. Test "Config.default equals Config()" verifies it. |
| 4 | `def require(c: Config)` validates `maxInFlight > 0` and throws `IllegalArgumentException` | PASS | `JsonRpcEndpoint.scala:429-434`: pattern `case Present(n) if n <= 0 => throw new IllegalArgumentException(s"maxInFlight must be > 0, got $n")`. Test "Config.require throws on maxInFlight <= 0" asserts the throw and the `"maxInFlight"` substring in the message. |
| 5 | `init` calls `Config.require(config)` at start; default-arg uses `Config.default` | PASS | `JsonRpcEndpoint.scala:436` `config: Config = Config.default`; line 438 `Config.require(config)` is the first statement before the `internal.engine.JsonRpcEndpointImpl.init` chain. Early-throw before suspension matches the precedent in Decision 6. |
| 6 | The 8 new tests assert concrete values (not just types or non-emptiness) | PASS | All 8 tests assert specific values: `cfg.codec eq JsonRpcCodec.Cdp` (identity), `cfg.idStrategy == JsonRpcEndpoint.IdStrategy.SequentialInt` (concrete value), `cfg.maxInFlight == Present(10)` (concrete wrapped value), `Config.default == Config()` (equality), `e.getMessage.contains("maxInFlight")` (substring of thrown message). The two `succeed`-tail tests for Duration.Zero / Duration.Infinity require `Config.require` to return without throwing — meaningful negative assertions. |
| 7 | Dropped `requestTimeout < Duration.Zero` guard is documented and correct | PASS | Decision 2 (and verify.md B2) document the rationale; verified at `kyo-data/shared/src/main/scala/kyo/Duration.scala:60` `def fromNanos(value: Long): Duration = if value <= 0 then Duration.Zero else value`. No `Duration` value smaller than `Duration.Zero` is constructible. The guard would be dead code. |
| 8 | No FP-discipline regressions | PASS | Verify.md confirms fp-discipline grep hits at lines 237, 243, 249, 259 and 382 are PRE-EXISTING in committed HEAD; phase 04 introduced none. Manual review of the diff (the only added body is the 9 one-line `copy()` setters and a non-effectful `def require` with a plain `throw`) shows no new violations. |

## NOTE findings

- **NOTE-1: Setter test coverage is partial (3 of 9 setters round-tripped).** Verify.md B3 already flagged this. Round-trip tests cover `codec`, `idStrategy`, and `maxInFlight`. The other 6 (`cancellation`, `progress`, `unknownMethod`, `gate`, `requestTimeout`, `progressResetsTimeout`) follow the identical `def name(x: T): Config = copy(name = x)` (or `copy(name = Present(x))`) template and compile-check through the shared run, so defect risk is low. Not worth a follow-up commit; consider a single parameterised setter-coverage test if the file is revisited.

- **NOTE-2: `Config.require` does its validation by `throw`, not by an effect.** Decision 6 records the rationale (matches the early-exit-before-suspension precedent; the spec asked for a plain `def require(c: Config): Unit`). This is consistent with how `HttpServerConfig.require` is invoked at server-init time. It does mean `init` is no longer purely effectful when called with a hostile `Config` — a caller passing `maxInFlight = Present(-1)` will see a synchronous `IllegalArgumentException` thrown from `init` before any suspension. That is desirable here (config errors are programming errors), but downstream documentation in the README (if any) should mention this contract.

- **NOTE-3: Field defaults retained on the primary constructor.** Decision 1 records the rationale (removing defaults would break existing callers like `JsonRpcEndpoint.Config(cancellation = Absent)` in the test suite; `HttpServerConfig` also retains primary-constructor defaults despite §10's "drop primary-constructor defaults" wording). This is a deliberate and documented deviation from the bullet-1 phrasing of design §10, consistent with the actual kyo-http template behaviour. Not a finding requiring action; flagged so future audits do not misread the design wording.

## Exit code: 0
