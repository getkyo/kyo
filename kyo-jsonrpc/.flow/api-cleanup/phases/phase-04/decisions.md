# Phase 4 decisions

Decision 1: Kept primary-constructor defaults on `Config` case class
Rationale: Removing defaults would break existing callers like `JsonRpcEndpoint.Config(cancellation = Absent)` throughout the test suite. The `HttpServerConfig` template itself retains defaults on constructor parameters while also providing a `val default`. Design §10 says "drop primary-constructor defaults" but the template contradicts this; keeping them is both backward-compatible and consistent with the template's actual implementation.
Time: 2026-05-30T13:57:00Z

Decision 2: Dropped `requestTimeout < Duration.Zero` guard from `Config.require`
Rationale: kyo's `Duration` opaque type clamps at `Duration.Zero` via `fromNanos`; negative durations cannot be constructed. The guard would be permanently dead code. Keeping dead unreachable guards is misleading; the `maxInFlight <= 0` guard is retained because `Int` values can legitimately be zero or negative. Design §10 specified "require guards on maxInFlight and requestTimeout" but the requestTimeout guard is not physically achievable in kyo's type system.
Time: 2026-05-30T13:58:00Z

Decision 3: Replaced impossible negative-requestTimeout test with Duration.Zero and Duration.Infinity acceptance tests
Rationale: Follows from Decision 2. A test that can never trigger (`-1.millis` resolves to `Duration.Zero` at the opaque-type level) would provide false assurance. Two positive acceptance tests covering the boundary values are more informative.
Time: 2026-05-30T13:58:30Z

Decision 4: Fluent setter method names are bare (e.g., `codec`, `idStrategy`) matching kyo-http convention, not `withCodec` / `withIdStrategy`
Rationale: Design §10 and plan phase 4 explicitly cite the kyo-http template which uses bare names. Steering §template = kyo-http confirms this.
Time: 2026-05-30T13:57:00Z

Decision 5: `Maybe`-typed fields (`cancellation`, `progress`, `gate`, `maxInFlight`) take the bare inner type in their setter and wrap in `Present`
Rationale: Plan phase 4 spec states "Maybe-typed fields take bare values and wrap in Present". Consistent with `HttpServerConfig.cors(c: Cors)` which does `copy(cors = Present(c))`.
Time: 2026-05-30T13:57:00Z

Decision 6: `Config.require` is a plain `def` that throws `IllegalArgumentException` (not a kyo effect)
Rationale: Plan and impl prompt specify `def require(c: Config): Unit`. Matching `HttpServerConfig` which has no equivalent but `Kyo.service` init patterns use plain throws for config errors. The `init` method calls `Config.require` before the kyo effect chain, consistent with the pattern of early-exit validation before suspension.
Time: 2026-05-30T13:57:00Z
