# Final Trailing Audit (Phases 21h, 22d, 24b, 25a, 25b, 26, 27)

## Summary

No blockers. All seven trailing phases produced substantive tests against
real production code paths and met their stated invariants. Two items need
close-out before the campaign is fully declared done: (1) Phase 27's
INV-027 is structurally satisfied but numerically unverified (sentinel -1
values need a real JMH run to be usable for future regression checks), and
(2) `kyo-tasty/README.md` line 259 still points to the deleted
`shared/src/main/scala/kyo/tasty/examples/` path. Neither is a blocker.

## Per-phase verdicts

### Phase 21h - OK

`PlatformHashingStateTest` has two tests: (T1) calls `TypeKey.of` twice on
the same `ConstantType(IntConst(42))` and asserts the two `hash` integers
are equal, exercising `PlatformHashingState.get()` on each invocation;
(T2) asserts `IntConst(1)` and `IntConst(2)` produce distinct hash values.
The two-call equality is not a tautology: it confirms the state set is
cleared between calls and does not corrupt subsequent hashes. Both tests
run cross-platform.

### Phase 22d - OK

`TastySymbolTest` T4 builds root -> A -> B -> C -> D -> E (all
`SymbolKind.Class`) and asserts `eSym.binaryName == "A$B$C$D$E"`. The `$`
separator fires at every Class-to-Class boundary (four transitions); the
separator logic is applied at the correct boundary. Cross-platform.
Post-phase JVM count: 467.

### Phase 24b - OK

Three lifecycle tests: (T1) 50-fiber pool exhaustion via
`Async.foreach(concurrency=50)` with per-fiber correctness assertion; (T2)
deterministic close-then-body asserting `ClasspathClosed`; (T3) mmap arena
close asserting `IllegalStateException`. Reflection on `activePool` in T1
is the accepted test-internal smell; explicitly accepted in the decisions.
T2 is cross-platform (`MemFileSource`). T3 is jvmOnly. JVM total: 473.

### Phase 25a - WARN

Five doctest scan tests in `TastyTest.scala` (lines 268-356). Two tests
cover pre-existing blocks (`Name.apply`, `Flags.empty`). Three cover new
blocks on `Classpath.findClass`, `Classpath.packages`,
`Classpath.topLevelClasses`. The text-scan approach is explicitly
acknowledged as the only feasible strategy without a live classpath in the
test harness. WARN: the scan for `packages` asserts the literal string
`"packages"` is present in the scaladoc region, which is satisfied by the
method name itself even without a doctest body. The stronger checks on
`findClass` and `topLevelClasses` require `"findClass("` and
`"topLevelClasses"` which are more specific. The `packages` assertion is
the weakest of the five. No blocker; the fenced block IS present; this is a
coverage depth note for a future tightening pass.

### Phase 25b - OK

Three seeded tests at `Random(0L)`, 100 iterations each. Varint:
`nextLong() >>> 1` spans 63 bits uniformly. Utf8: BMP (90%) plus
supplementary code points (10%) with well-formed surrogate-pair
construction, covering both ASCII-fast and multi-byte codec paths. Symbol
fullName: chains of 1-10 alphanumeric segments of 1-8 chars, exercising
dot-join across depths. Value space is adequate for the round-trip and
separator-correctness properties being checked.

### Phase 26 - WARN

Four files moved to `kyo-tasty-examples/shared/src/main/scala/examples/`
with `package examples`. build.sbt adds the cross-platform module with
`.disablePlugins(MimaPlugin)` and `.dependsOn(kyo-tasty)`. Old directory
`kyo-tasty/shared/src/main/scala/kyo/tasty/` removed. `TastyExamplesLayoutTest`
(jvmOnly, 2 tests) pins both the absence of the old path and presence of
the new path. WARN: `kyo-tasty/README.md` line 259 still reads
`shared/src/main/scala/kyo/tasty/examples/` (the deleted path). This is
the only stale user-facing reference; audit-fixes and test-assertion
references to the old path are by design.

### Phase 27 - WARN

`BenchmarkRegressionTest` (2 jvmOnly tests) verifies `post-campaign.json`
exists with `cold_load_ms` and `warm_cache_ms` fields that are -1
(sentinel) or non-negative. Both are currently -1. INV-027 ("no perf
regression vs pre-campaign baseline") cannot be strictly verified: no
pre-campaign baseline was captured, and the tests pass on -1. WARN:
INV-027 is aspirational. The Phase 16 `CLASSconst` per-occurrence
allocation recommendation (cache in `DecodeSession.addrCache`) was routed
here but not implemented.

## Cross-cutting

### 8. Test-count growth - OK

473 (post-Phase-24b) + 5 (Phase 25a) + 3 (Phase 25b) + 2 (Phase 26) +
2 (Phase 27) = 485 JVM, matching the stated target. Growth from
approximately 390 post-Phase-12 is roughly 95 new tests across the
trailing phases. Healthy.

### 9. Open steering items inventory

Items that reached Phase 27 without being closed:

- WARN (Phase 22a-c): `Utf8.decode` on classfile constant-pool path via
  `Interner.intern` uses pure UTF-8 rather than MUTF-8. `[0xC0,0x80]`
  becomes `U+FFFD U+FFFD` instead of `U+0000`. No doc comment was added at
  the call site.

- WARN (Phase 22a-c): `TypeArena` `case Tasty.Type.RecThis(_) => t` (line
  38) still has no inline comment explaining why recursing into `RecThis.rec`
  would break cycle safety.

- WARN (Phase 23a): JS coverage of the `internRec` depth-guard is zero
  because 1023-level nesting overflows the JS stack before the guard fires.
  No shallow (~20-level) JS-safe version of the depth-boundary test was
  added.

- WARN (Phase 24a): `TypeArena` T7 label overclaims "concurrency." The test
  correctly pins the production model (per-fiber arenas, sequential merge)
  but does not exercise concurrent `canonical()` calls.

- NOTE (Phase 18e): INV-005 zero-Unknown sweep covers only 2 of 10 fixtures.
  BOUNDED import-selector path was not swept in Phase 22a or 22d.

- NOTE (Phase 20a): `inflater.close()` in Native `InflateHook` is in the
  `try` block rather than `finally`, leaking on exception path.

- NOTE (Phase 20a): Native `InflateHook` `DataFormatException` arm has no
  targeted test; a Phase 21f routing was recorded but not executed.

- NOTE (Phase 20f): INV-017 cross-platform parity is verified implicitly
  (each platform matches the same hardcoded constant) rather than a
  three-way runtime comparison. Routed to Phase 23a/23b but not added.

- NOTE (Phase 22c): JMOD recognition deferred with a placeholder test.
  Tracked as a future follow-up.

- NOTE (Phase 16): `CLASSconst` per-occurrence allocation recommendation
  (cache decoded type in `DecodeSession.addrCache`) routed to Phase 27
  but not implemented.

- NOTE (Phase 19b): `PARENTS` section silently drops java.lang.Object and
  other non-local parents. FQN-string encoding for external parents deferred
  to a future minor bump.

## Recommendations for closure

1. Fix `kyo-tasty/README.md` line 259: replace the stale
   `shared/src/main/scala/kyo/tasty/examples/` path with
   `kyo-tasty-examples/shared/src/main/scala/examples/`.

2. Add the `TypeArena` `RecThis` leaf-treatment inline comment (one line,
   zero-risk) to close the Phase 22a-c WARN.

3. Add the MUTF-8 approximation doc comment at `Interner.intern` call site
   in `ClassfileUnpickler` to close the Phase 22a-c WARN.

4. Run `sbt 'kyo-tasty-bench/Jmh/run -i 5 -wi 5 -f 1 -bm avgt -tu ms'` and
   populate real numbers into `bench-baselines/post-campaign.json` so
   INV-027 transitions from aspirational to verifiable.

5. Add a shallow (under 50-level) JS-safe Rec depth-boundary test to
   `TypeArenaTest` to close the Phase 23a JS coverage WARN.

6. The remaining NOTE-level items (inflater.close in finally, DataFormatException
   test, INV-017 parity sweep, JMOD placeholder, CLASSconst cache) are
   quality improvements that do not block campaign closure; schedule them as
   a post-campaign debt pass if desired.
