# Final audit

## Test execution results

| Platform | Suites | Tests run | Succeeded | Failed | Pending | Outcome |
|---|---|---|---|---|---|---|
| `kyo-schema/test` (JVM) | 23 | 1758 | 1758 | 0 | 0 | PASS |
| `kyo-schemaJS/test` (after clean) | 21 | 1748 | 1746 | **2** | 0 | **FAIL** |
| `kyo-schemaNative/test` (after clean) | 21 | 1748 | 1746 | **2** | 0 | **FAIL** |
| `kyo-data/test` | 32 | 3693 | 3693 | 0 | 3 (pre-existing) | PASS |

JVM summary line: `Tests: succeeded 1758, failed 0, canceled 0, ignored 0, pending 0`.
JS summary line: `Tests: succeeded 1746, failed 2, canceled 0, ignored 0, pending 0`.
Native summary line: `Tests: succeeded 1746, failed 2, canceled 0, ignored 0, pending 0`.
kyo-data summary line: `Tests: succeeded 3693, failed 0, canceled 0, ignored 0, pending 3` (3 pending are pre-existing, not introduced by this campaign — TagMacro change in `cc62abd3e` did not regress kyo-data).

Note: The first JS/Native attempts surfaced a stale sbt incremental cache (`NoClassDefFoundError: kyo/internal/FocusMacro$`). A `clean` then full compile+test confirmed the genuine state; the two real failures persist after clean.

The two failures on both JS and Native are identical, both in `CodecTest`:
- `Phase 11: ZoneId round-trip` -- `java.time.zone.ZoneRulesException: Unknown time-zone ID: America/Los_Angeles`
- `Phase 11: ZonedDateTime DST fall-back round-trip` -- `DateTimeParseException: Text '2024-11-03T01:30:00-07:00[America/Los_Angeles]' could not be parsed, unparsed text found at index 25`

## Phase compliance summary

| Phase | Commit | Status | Notes |
|---|---|---|---|
| 1 | `cdf05c809` | PASS | `isSerializableType` extended; SerializationMacroDriftTest covers. |
| 2 | `d34fe0660` | PASS | `Structure.PrimitiveKind` includes Instant/Duration/Frame/Text. |
| 3 | `a03102bfb` | PASS | MacroUtils consolidation; MacroUtilsDriftTest covers. |
| 4 (a) | `db1bcc601` | PASS | Field-level transform-aware dispatch landed pre-campaign. |
| 4b | `ff3669abc` | PASS | Collection givens routed through `SchemaSerializer`. |
| 4c | `2ba83a153` | PASS | Sealed-trait variant codec dispatch; closed the legacy pending leaf. |
| 5 | `ec01fad89` | PASS | Protobuf discriminator decode. |
| 6 | `74b7000bd` | PASS | CompositionMatrixTest exists; exposed the Phase 4b bug as designed. |
| 7 | `f127c1028` | PASS | `KeyCodec[K]` typeclass; KeyCodecTest exists. |
| 8 | `60042adf0` | PASS | Generic `Map[K, V]` via KeyCodec, array-of-pairs fallback. |
| 9 | `bf3274df9` | PASS | Six cross-platform string-transform givens. |
| 10 | `14c70b53a` | PASS | Seven JVM-only string-transform givens. |
| 11 | `9e52f0012` | **FAIL on JS/Native** | tzdata claim in commit message wrong (see Findings). |
| 12 | `eafa22904` | PASS | Tuple1, Tuple6..Tuple22. |
| 13 | `598ba3d37` | PASS | Array + ArraySeq + Queue + SortedSet + SortedMap. |
| 14 | `8766af3be` | PASS | Java enum derivation; JavaEnumTest in `jvm/`. |
| 15 | `bb3bdb7fd` | PASS | UnionMacro.scala is a full implementation (297 lines), not a stub. Implements OrType flattening, dedup, degenerate detection, leg-name dispatch (write `isInstanceOf` chain, read wrapper-object parse), routes through `SchemaSerializer.writeTo`/`readFrom` so per-leg transforms compose with `.discriminator(name)`. UnionTest.scala exists. |
| 16 | `f2873dfc6` | PASS | Intersection rejection with clear error. |
| 17 | `9626b3484` | PASS | Scaladoc additions. |

All 17 phases (plus 4b and 4c) have commits. Spot-check of Phase 1, 6, 11, 14, 15: commits exist, files exist, source matches plan intent.

## Cross-cutting checks

| Check | Result | Evidence |
|---|---|---|
| `pending` / `ignore` markers in tests | **PASS** | grep returned no matches. |
| `???` placeholders in production | **PASS** | grep returned no matches. |
| `TODO` markers in `kyo-schema/{shared,jvm}/src/` | **PASS** | grep returned no matches. |
| Global mutable singletons / ConcurrentHashMap | **PASS** | grep returned no matches. |
| `AllowUnsafe` usage | PASS | none. |
| `Frame.internal` usage | **WARN** | 19 sites in `Schema.scala` (Phase 4b collection-given write/read paths) plus 2 in `UnionMacro.scala`. Project policy ([feedback_no_unsafe]) forbids `Frame.internal` outside justified bridging. The Phase 4b refactor introduced these to thread transform-aware dispatch through synthesised lambdas that have no caller Frame; this is the same bridging pattern as the kyo-net pool, but no `// Unsafe:` comments were added documenting the choice. |

## Drift-guard status

`sbt 'kyo-schema/testOnly *DriftTest'` -> `Tests: succeeded 6, failed 0, canceled 0, ignored 0, pending 0`. Both `SerializationMacroDriftTest` (4 tests) and `MacroUtilsDriftTest` (2 tests) green. Drift guards remain effective.

## PROGRESS.md observation

`kyo-schema/improvement-plan/PROGRESS.md` STALE. All 17 rows still say `pending` with empty `Commit` and `Notes` columns. The table was never updated as phases landed. Documentation cleanup item, not a code defect.

## Untracked artifacts

`git status` reports only one untracked path: `kyo-schema/improvement-plan/` (the planning docs themselves: PROGRESS.md, STEERING.md, analysis.md, execution-plan.md, HANDOFF-bug-d.md). Matches expectation. Working tree otherwise clean.

## Findings

### BLOCKER 1 -- Phase 11 java.time givens fail on JS and Native

File: `kyo-schema/shared/src/test/scala/kyo/CodecTest.scala` (Phase 11 tests).
Commit `9e52f0012` message claims: "All 8 compile on JVM, JS, and Native -- scala-java-time provides default tzdata covering common IANA zones (America/Los_Angeles etc. used in tests). No platform shadow needed."

This is false. On both JS and Native, two tests throw:
- `ZoneId.of("America/Los_Angeles")` -> `ZoneRulesException: Unknown time-zone ID`
- `ZonedDateTime.parse("...[America/Los_Angeles]")` -> `DateTimeParseException ... unparsed text found at index 25` (i.e. parser cannot consume the `[America/Los_Angeles]` zone region)

Root cause: scala-java-time on JS/Native ships only fixed-offset zones by default; regional IANA zones require the `scala-java-time-tzdb` artifact. Either the build needs the tzdb dependency on JS/Native, or the affected tests must use only fixed-offset zones, or platform-specific test shadows must be added.

This was caught only by re-running the JS/Native suites here -- not during the original Phase 11 work, suggesting Phase 11 was validated on JVM only. Anti-reward-hacking applies: do not delete the tests, fix the artifact / platform wiring.

### WARN 1 -- Phase 4b introduces `Frame.internal` without `// Unsafe:` annotations

Files: `kyo-schema/shared/src/main/scala/kyo/Schema.scala` (19 sites, lines 1450-1816 region). Project rule ([No AllowUnsafe](feedback_no_unsafe.md)): avoid `Frame.internal`; when bridging requires it, mark each site with a `// Unsafe:` comment. The 19 new sites are in collection-given `serializeWrite`/`serializeRead` paths where there is no caller Frame to propagate. UnionMacro adds 2 more. Add `// Unsafe:` comments at each site (or a single comment per region) explaining the bridging.

### NOTE 1 -- PROGRESS.md table never updated

Documentation hygiene only. Update the table or delete the file.

### NOTE 2 -- Stale incremental cache hides correct state

First JS/Native attempts failed with a confusing `NoClassDefFoundError: kyo/internal/FocusMacro$` until `clean` was run. Builds that touched the FocusMacro/UnionMacro source ordering may have left the sbt incremental store in a corrupt state. Consider an `sbt clean` between full-platform validations during macro-heavy campaigns.

### NOTE 3 -- Phase 4 spans three commits

Phase 4 is not a single commit -- it's `db1bcc601` (pre-existing field dispatch) + `ff3669abc` (4b, collection givens) + `2ba83a153` (4c, sealed-trait variants). PROGRESS.md should reflect this when it is updated.

## Overall verdict

**NOT GREEN.** The 17-phase campaign is structurally complete (all phases committed, drift guards green, no placeholders, no global state, no AllowUnsafe), and the JVM suite is fully green at 1758 tests. However, **Phase 11 ships two tests that fail on both JS and Native** because the commit's tzdata claim is incorrect. This is a real cross-platform regression introduced by the campaign and must be fixed before any push (per the rule that cross-platform modules must pass on all platforms, not just JVM). Also: clean up the stale PROGRESS.md and add `// Unsafe:` comments at the 21 `Frame.internal` sites introduced in 4b/4c and Union.
