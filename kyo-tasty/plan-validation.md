# Plan Validation: execution-plan-perf.md

Plan reviewed: `/Users/fwbrasil/workspace/kyo/.claude/worktrees/cached-inventing-quasar/kyo-reflect/execution-plan-perf.md`
Reviewed at: 2026-05-26T (review session)
Profile baseline: 55 ms cold-load median, 57 ms snapshot median (full classpath: 121 jars, 5,949 TASTy files, 34,580 class files)
Verification report: `kyo-reflect/PERF-VERIFICATION.md`

## Verdict

**FAIL**: The plan has solid grounding (cited locations all match real source) but contains 9 blocking items, mostly localized: four implicit deferred-decision phrases ("Async.gather (or equivalent)", ZipFile vs newFileSystem alternative, producer fiber topology alternative, "Pass1Result / FileResult (whichever)"), one type-shape mismatch (Phase 4's claim that 4 maps live in `FileResult` is wrong; `addrMap` lives only in `Pass1Result` + `TastyOrigin._addrMap`), missing exact path for the JS FileSource modify target, unenumerated Phase 6 callers, and unaddressed Scala 2.12-vs-Scala-3 cross-call for Phase 7's sbt plugin. Most are small fixes; Phase 7's cross-call is the only one that may require design rework.

## Rule-by-rule findings

### Rule 1: Every open requirement mapped

**PASS with one minor gap.** Each FEASIBLE verification-table item is wired to a phase. The NEEDS-DESIGN items are addressed with concrete approaches (Channel streaming → Phase 3 with `streamUntilClosed`; `addrMap.toMap` → Phase 4). BLOCKED items (Async.foreach batching, mmap JAR bytes) are properly excluded.

Coverage table:

| Verification item (PERF-VERIFICATION.md §) | Verdict | Phase | Notes |
|---|---|---|---|
| §1: Single-pass JAR enumeration (multi-suffix `list`) | FEASIBLE | Phase 1 | Direct mapping |
| §1: Two-pass `collectTastyFiles`+`collectModuleInfoFiles` merge | FEASIBLE | Phase 1 | Lines 90-91 collapse to one walk |
| §2: NativeFileSource has no JAR support | FEASIBLE | Phase 1 (Native modify) | Plan adds multi-suffix override |
| §3: DigestComputer third JAR walk | FEASIBLE | Phase 2 | mtime+size for jar roots |
| §4: ClasspathOrchestrator sequential phases | NEEDS DESIGN | Phase 3 | Channel pipeline |
| §5: `readType` fresh HashMap | NEEDS DESIGN | Excluded ("Considered") | Hot path uses `DecodeSession` |
| §6: PositionsUnpickler Integer boxing | FEASIBLE | Phase 5 | `IntMap` |
| §7: Interner pre-sizing | FEASIBLE | Phase 6 | `initialShardCapacity` param |
| §7: Interner Arrays.copyOf | NEEDS DESIGN | Excluded ("Considered") | Indirectly via Phase 6 |
| §8: Channel close semantics | NEEDS DESIGN | Phase 3 | `streamUntilClosed` cited |
| §9: Snapshot embedded-digest re-verification | NEEDS DESIGN | Excluded ("Considered") | Out of scope (correctness only) |
| §10: sbt plugin pattern | FEASIBLE | Phase 7 | Mirrors `kyo-compat` |
| §11: `.toMap` HAMT conversions in AstUnpickler | FEASIBLE | Phase 4 | Lines 153/176/177/178 |
| §13: Async.foreach already batches | BLOCKED | Excluded ("Considered") | Explicit |

**Gap:** PERF-VERIFICATION.md §11 lists `commentsBySymbol` and `positionsBySymbol` as additional `FileResult` map fields (ClasspathOrchestrator.scala lines 56-57). The plan does not say whether these are intentionally excluded from Phase 4 or simply not addressed. NON-BLOCKING (PERF-VERIFICATION does not call them out as bottlenecks), but worth a one-line note in "Considered but not included".

### Rule 2: No priority language

**PASS.** No banned phrases found. Phrases scanned for: "high priority", "low priority", "first", "most important", "if time permits", "tier 1/2", "critical path", "nice-to-have", "priority", "importance", "urgency", "tier". Phase ordering in the dependency graph is justified by data dependencies (e.g., Phase 6 needs "the entry count produced in Phase 1"), not by importance.

### Rule 3: Concrete content per phase

**MOSTLY PASS** with two blocking gaps.

| Phase | files-produce | files-modify | tests numbered | API signature-level | Verification command |
|---|---|---|---|---|---|
| 1 | Y (2) | Y (5, but one cited by glob) | Y (T1-T6 + T7-T10) | Y | Y |
| 2 | Y (none) | Y (1) | Y (T14-T18) | Y | Y |
| 3 | Y (none) | Y (1) | Y (T1-T8) | Y | Y |
| 4 | Y (none) | Y (3) | Y (T1-T3) | Y | Y |
| 5 | Y (none) | Y (4) | Y (T1) | Y | Y |
| 6 | Y (none) | Y (2) | Y (T1-T3) | Y | Y |
| 7 | Y (4) | Y (1) | Y (1 scripted) | Y | Y |
| 8 | Y (1) | Y (none) | Y (full suite) | Y | Y |

**Gaps:**
- Phase 1 modifies `kyo-reflect/js/src/main/scala/kyo/internal/reflect/query/*.scala` with a wildcard. Verified: the file is `JsFileSource.scala`. Rule 3 requires "exact path". **BLOCKING** (one-line fix).
- Phase 6 line 258: "Update all existing callers of `new Interner(...)` in the codebase to pass an explicit `initialShardCapacity` argument" without enumerating callers. PERF-VERIFICATION.md §7 confirms one call site (`ClasspathOrchestrator.scala:103`); other callers (test files, fixtures) are unstated. **BLOCKING** (one-line fix).

### Rule 4: No vague phrasings

**FAIL.** Specific findings:

1. **Phase 3 line 144:** "All three stages are gathered via `Async.gather` (or equivalent)." `Async.gather` exists (`kyo-core/.../Async.scala:311` verified). The "(or equivalent)" is a deferred dispatch decision. **BLOCKING.**
2. **Phase 1 line 50:** "Opens the JAR via `java.util.zip.ZipFile` and reads raw CEN bytes, **or** uses `java.nio.file.FileSystems.newFileSystem(path, Map.empty)` to iterate entries without `JarFile$JarFileEntry` allocation." Two design alternatives left to the implementer. **BLOCKING.**
3. **Phase 3 line 141:** "one fiber per root group (or one fiber total draining all roots sequentially)" — undecided producer topology. Affects T6's queue-depth invariant. **BLOCKING.**
4. **Phase 4 line 180:** "(whichever intermediate type holds these maps)" — undecided dispatch between `Pass1Result` and `FileResult`. **BLOCKING** (see also Rule 9).
5. **Phase 6 test T1 line 265:** "verified by checking no `grow` calls — use a subclass or spy, or verify via a count of entries successfully interned without observable `Entry[]` reallocation" — verification mechanism left as OR list. **BLOCKING.**

No other banned phrases ("TBD", "polish", "investigate further", "for now", "out of scope" without explicit user accept, "simpler", "edge case", "probably not needed", "we can revisit") were found.

### Rule 5: Deviations explicit

**PASS.** "Considered but not included" lists six exclusions with stated reasons citing PERF-VERIFICATION.md:
- TypeUnpickler.readType per-call HashMaps (§5)
- Interner `Arrays.copyOf` (§7)
- Async.foreach fiber batching (§13)
- mmap of JAR bytes (COLD-LOAD-PROFILE-FULL.md Findings)
- Snapshot embedded-digest re-verification (§9)
- Per-file path string deduplication via Interner (addressed indirectly in Phase 1)

### Rule 6: Test scenarios specific

**MOSTLY PASS.** Two-leaf sample per phase:

- **Phase 1 T5:** "Large JAR with more than 500 entries: list returns all matching entries without missing any (verified by count and spot-checking 5 known entry names)." Specific. PASS.
- **Phase 1 T6:** "Non-JAR file path: `Abort[ReflectError]` is raised, not an unchecked exception." Specific. PASS.
- **Phase 2 T16:** "a jar whose file mtime is bumped (via `Files.setLastModifiedTime`) produces a different digest on the next `compute` call." Specific (anti-flakiness gap noted under Rule 9). PASS.
- **Phase 2 T17:** Jar size change produces different digest. Specific. PASS.
- **Phase 3 T6:** "entry channel does not grow unboundedly... peak `entryCh` queue depth never exceeds capacity (`decodeConcurrency * 4 + decodeConcurrency`)." Specific (observation mechanism unstated; see Rule 9). PASS for spec, NON-BLOCKING for mechanism.
- **Phase 3 T8:** "decoder concurrency respects the `concurrency` parameter: with `concurrency = 2` and 100 entries, exactly 2 decoder fibers are spawned." Specific spec; observation mechanism unstated. NON-BLOCKING.
- **Phase 4 T2:** "`mergeResults` (or the Phase 3 incremental merger)..." — OR introduces a Phase-3-outcome-dependent dispatch. VAGUE.
- **Phase 5 T1:** "PositionsUnpickler.readSync with an `IntMap` addrMap containing 10,000 entries returns correct position mappings for all 10,000 entries." Specific. PASS.
- **Phase 6 T1:** Verification strategy left as OR list. VAGUE (already counted under Rule 4).

### Rule 7: Phase ordering dependency-justified

**PASS.** Every non-leaf phase cites a specific API/type/file it depends on:

| Phase | Depends on | Stated reason |
|---|---|---|
| 1 | nothing | "first phase" |
| 2 | Phase 1 | "for `source.list` multi-suffix API and the Phase A single-walk result" |
| 3 | Phases 1, 2 | "entry enumeration now produces entries via a single walk" + "digest no longer blocks on a separate enumeration pass" |
| 4 | Phase 3 | "the streaming merger receives `FileResult` values one at a time" |
| 5 | Phase 1 | "no structural dependency; can execute after Phase 1 commits" |
| 6 | Phase 1 | "entry count produced during Phase A single-pass enumeration is required to compute the `sizeHint`" |
| 7 | Phases 1-6 | "the plugin calls `Reflect.Classpath.openCached`, which benefits from all prior optimizations" |
| 8 | Phases 1-7 | "cannot begin until Phases 1-7 are all committed" |

The Phase 4 → Phase 5 supersession (mutable.HashMap → IntMap for the `addrMap` field) is explicitly documented in Phase 5.

### Rule 8: Zero open items

**PARTIAL FAIL.** No banned literal phrases ("TBD", "open question", "the impl agent will decide") in summary/plan body. However, Rule 4 found four implicit deferred-decision phrases ("or equivalent", "ZipFile or newFileSystem", "one fiber per root or one fiber total", "whichever intermediate type"). These violate the spirit of Rule 8 even with no banned literal.

Plan does not state what happens if Phase 8 misses acceptance targets (25 ms cold, 5 ms snapshot). NON-BLOCKING (supervisor escalation is implicit).

### Rule 9: Implicit open items hunt

| Failure mode | Verdict | Detail |
|---|---|---|
| Deferred decisions | **PRESENT** | Five OR-style undecided dispatches (counted under Rule 4) |
| Missing platform paths | **PARTIAL** | Phase 1 JS modify uses `*.scala` glob; actual file is `JsFileSource.scala` (verified). Phase 6 does not state whether Interner needs JS/Native consideration; `Interner.scala` is in `shared/` so should be the same, but unstated. |
| Vague test verifications | **PRESENT** | Phase 3 T8 (no fiber-count observation mechanism); Phase 6 T1 (OR-list verification strategy); Phase 4 T2 (OR-dispatched assertion target). |
| Missing error-path tests | **PARTIAL** | Phase 1 T6 covers non-JAR error. Phase 2 has no test for unreadable / corrupt jar in `compute`. Phase 5 has no malformed-addrMap case. Phase 7 scripted test has no failure-mode coverage. |
| Missing anti-flakiness measures | **PRESENT** | Phase 2 T16/T17 mutate filesystem mtime/size with no resolution-aware delay (HFS+ = 1s, APFS = ns, ext4 = ns, NTFS = 100ns; CI may use a low-precision filesystem). Phase 3 T6 measures "peak queue depth" — no specified observation mechanism (race-prone unless single-threaded sampling). Phase 6 T1 ("no `grow` calls") is implicitly race-prone if Interner is touched concurrently in the test. |
| Forward-reference compile-isolation | **CLEAN** | Phase 4 sets `TastyOrigin._addrMap` to `mutable.HashMap[Int, Reflect.Symbol]` while `PositionsUnpickler.addrMap` stays at `Map[Int, Reflect.Symbol]` (unchanged in Phase 4). Since `mutable.HashMap[K,V] <: Map[K,V]` in Scala, this compiles. Plan does not state the subtype-soundness reasoning explicitly but it is correct. |
| Test count contradictions | **CLEAN** | Phase 1 declares 6 + 4 = 10 tests, all enumerated. Phase 4 declares 3 tests for 3 files modified; coverage is minimal but counts are consistent. |
| Public API contradictions | **CLEAN** | All declared API changes (FileSource.list 2-arg variant, Interner constructor, KyoReflectPlugin keys) match the modify sections. |
| Channel mutex hotspot contingency | **PRESENT but as risk-note** | "Channel mutex hotspot risk" section lists shard-by-jar OR LinkedBlockingQueue as contingencies. Acceptable for a contingency (Phase 8 reveals whether it triggers), but the contingency itself is an OR. If Phase 8 reveals the hotspot, the impl agent must pick — not a deferral if framed as Phase 8 follow-up. |
| ZIP CEN parsing edge cases | **PARTIAL** | Plan addresses Zip64 (lines 50, 388) and multi-disk JARs (line 50 → Abort). Plan does NOT address: ZIP signature validation (PK headers), CEN signature mismatch handling, general-purpose-bit-3 (data descriptor) entries, UTF-8 vs CP437 entry names (bit 11). T6 only covers "non-JAR file"; no malformed-CEN-JAR test. **BLOCKING.** |
| sbt plugin platform constraints | **PARTIAL** | Plan notes "plugin runs on JVM only (sbt runs on JVM)" (line 390). Plan does NOT address the Scala 2.12-vs-3 mismatch: `kyo-compat` is Scala 2.12 (sbt 1.x); `kyo-reflect` is Scala 3. Phase 7 says "the plugin depends on `kyo-reflect`" but does not say how a Scala 2.12 plugin invokes Scala 3 `Reflect.Classpath.openCached`. Options: (a) sbt 2.x Scala-3 plugin, (b) cross-build, (c) reflective dispatch into a Scala-3 classloader, (d) fork a JVM. **BLOCKING.** |
| mmap snapshot reader interaction with Phase 1 | **CLEAN** | Phase 1 does not change snapshot file format. |
| Migration concerns (digest semantic change) | **PRESENT, undocumented** | Phase 2 changes `DigestComputer.compute` so jar roots hash `(jar-path, jar-mtime, jar-size)` instead of `(per-entry-path, per-entry-mtime, per-entry-size)`. Existing `.krfl` snapshots become unreachable (filename keyed by digest changes; `openCachedImpl` at `Reflect.scala:880` compares by filename only, so old snapshots stay on disk but never hit). Auto-discard via cold-load + re-snapshot is correct behavior. Plan does not document this. NON-BLOCKING if intentional; should be one-line note in Phase 2 rationale. |

## File:line citation spot-check

| Cited location | Plan claim | Actual file:line | Match |
|---|---|---|---|
| `JvmFileSource.scala:71` | `list(dir, suffix)` entry | `def list(dir: String, suffix: String)...` at 71 | YES |
| `JvmFileSource.scala:145-156` | `listJarEntries` body | `private def listJarEntries(...) =` at 145, ends 156 | YES |
| `JvmFileSource.scala:146` | `new JarFile(jarPath)` | `val jar = new JarFile(jarPath)` at 146 | YES |
| `JvmFileSource.scala:149` | `jar.entries().asIterator()` | matches at 149 | YES |
| `JvmFileSource.scala:151` | `s"$jarPath!/${entry.getName}"` | matches at 151 | YES |
| `DigestComputer.scala:67` | `compute` entry | matches at 67 | YES |
| `DigestComputer.scala:82` | `computeParanoid` | matches at 82 | YES |
| `DigestComputer.scala:96` | `collectStats` | matches at 96 | YES |
| `DigestComputer.scala:106` | `collectFiles` | matches at 106 | YES |
| `ClasspathOrchestrator.scala:90-91` | `collectTastyFiles` + `collectModuleInfoFiles` chain | matches at 90-91 | YES |
| `ClasspathOrchestrator.scala:95-113` | `runPhaseAB` | def at 95, body to 113 | YES |
| `ClasspathOrchestrator.scala:103` | `new Interner(128)` | matches at 103 | YES |
| `ClasspathOrchestrator.scala:272-419` | `mergeResults` | def at 273; line 272 is the doc comment. Off by one. | CLOSE; NON-BLOCKING |
| `AstUnpickler.scala:153` | `addrMap.toMap` | `val finalAddrMap = addrMap.toMap` at 153 | YES |
| `AstUnpickler.scala:176` | parentsBySymbol toMap | matches at 176 | YES |
| `AstUnpickler.scala:177` | childrenByOwner toMap | matches at 177 | YES |
| `AstUnpickler.scala:178` | typeBySymbol toMap | matches at 178 | YES |
| `PositionsUnpickler.scala:58` | `readSync` def | matches at 58 | YES |
| `PositionsUnpickler.scala:107` | `addrMap.get(curIndex)` | matches at 107 | YES |
| `Interner.scala:19` | `initialCapacity = 16` | `private val initialCapacity = 16` at 19 | YES |
| `Interner.scala:22` | "use at line 22" | line 22 is `Array.tabulate(numShards)(_ =>`; actual use of `initialCapacity` is at line 23 inside the tabulate body | OFF BY ONE; NON-BLOCKING |
| `Channel.scala:217` | `close` | matches at 217 | YES |
| `Channel.scala:287` | `stream` | matches at 287 | YES |
| `Channel.scala:298` | `streamUntilClosed` | matches at 298 | YES |
| `build.sbt:1275` | `kyo-compat` SbtPlugin | matches at 1275 | YES |

**Additional shape check:** Phase 4 says "Update the `FileResult` case-class field types for the four maps (whichever fields correspond to lines 153, 176, 177, 178 of AstUnpickler)." Verified: `FileResult` (`ClasspathOrchestrator.scala:48-58`) has 9 fields including `parentsBySymbol`, `childrenByOwner`, `typeBySymbol`, `commentsBySymbol`, `positionsBySymbol`, BUT NO `addrMap`. `addrMap` (line 153) lives in `Pass1Result` (`AstUnpickler.scala:58-66`) at field `addrMap: Map[Int, Reflect.Symbol]` and is stored into `TastyOrigin._addrMap`. Phase 4's "four maps in FileResult" overstates by one and conflates `Pass1Result` with `FileResult`. **BLOCKING** — Phase 4 must enumerate which fields change in which case class.

## Blocking issues (must fix before approval)

1. **Phase 3 line 144 "Async.gather (or equivalent)":** Commit to `Async.gather` (verified at `kyo-core/.../Async.scala:311`). Remove the "(or equivalent)".

2. **Phase 1 line 50 ZipFile vs FileSystems.newFileSystem alternative:** Pick one. Recommendation: `java.util.zip.ZipFile` (lower-level, no JarFile$JarFileEntry, predictable allocation; `FileSystems.newFileSystem` opens a `ZipFileSystem` which itself allocates entry objects).

3. **Phase 3 line 141 producer topology:** Commit to "one fiber per root group" or "one fiber draining all roots sequentially". The choice affects T6's `decodeConcurrency * 4 + decodeConcurrency` queue-depth invariant.

4. **Phase 4 line 180 Pass1Result vs FileResult disambiguation:** Enumerate field changes per case class:
   - `Pass1Result` (`AstUnpickler.scala:58`): `addrMap`, `parentsBySymbol`, `childrenByOwner`, `typeBySymbol`.
   - `FileResult` (`ClasspathOrchestrator.scala:48`): `parentsBySymbol`, `childrenByOwner`, `typeBySymbol` (no `addrMap`).
   - `TastyOrigin._addrMap` (`TastyOrigin.scala`): separate field.
   Phase 4 must list which field in which type changes from `Map[K, V]` to `mutable.HashMap[K, V]`.

5. **Phase 1 JS file modify cited by wildcard:** Replace `kyo-reflect/js/src/main/scala/kyo/internal/reflect/query/*.scala` with the exact path `kyo-reflect/js/src/main/scala/kyo/internal/reflect/query/JsFileSource.scala` (verified to exist).

6. **Phase 6 callers unenumerated:** State explicitly: "The only production caller is `ClasspathOrchestrator.scala:103` (per PERF-VERIFICATION.md §7). Test callers will be added in this phase per the test plan below." Or enumerate test callers needing update.

7. **Phase 6 test T1 verification strategy:** Pick one mechanism. Recommendation: extend `Interner` with a package-private `growCount: AtomicInteger` for test-only observation; assert `growCount.get() == 0` after the test.

8. **Phase 7 Scala 2.12 vs Scala 3 cross-call:** `kyo-compat` uses Scala 2.12 (sbt 1.x). `kyo-reflect` is Scala 3. The plan must state how `KyoReflectPlugin` (Scala 2.12) invokes `Reflect.Classpath.openCached` (Scala 3). Pick one: (a) target sbt 2.x and publish as Scala 3, (b) use Java-level reflection from the plugin into a separately-launched Scala 3 process, (c) cross-build `kyo-reflect` to a 2.12-callable façade. Option (b) (fork JVM) is the most common pattern but adds startup cost; option (a) drops sbt 1.x users. This is the central design fork for Phase 7.

9. **Phase 1 ZIP CEN parsing test surface:** Add tests for malformed-CEN cases. Suggested additions to `JarCentralDirectoryTest.scala`:
   - T11: JAR with corrupted End-Of-Central-Directory signature returns `Abort[ReflectError]`.
   - T12: JAR with entries whose general-purpose-bit-3 (data descriptor present) is set still enumerates correctly (or fails with `Abort` if unsupported).
   - T13: Empty JAR (only EOCD record, zero entries) returns empty Chunk without throwing.
   - T14: JAR with UTF-8 entry names (general-purpose bit 11 set) decodes entry names correctly.

## Non-blocking suggestions

1. **Phase 2 snapshot-invalidation note:** Add one line: "Pre-existing `.krfl` snapshots become unreachable because the digest semantics change for jar roots; the next load triggers cold-load + re-snapshot. Cache invalidation is intentional and acceptable."

2. **Phase 3 T8 fiber-count observation:** State the mechanism (e.g., `AtomicInteger` incremented from the decoder body).

3. **Phase 4 T2 "or the Phase 3 incremental merger" wording:** Drop the OR; by the time Phase 4 lands, Phase 3 has committed (per dependency graph), so the merger IS the incremental merger. Just say "the merger".

4. **Phase 2 T16/T17 anti-flakiness:** Add `Thread.sleep(1100)` between the original `compute` and the mutation, or use a filesystem-precision-aware delay. Alternatively, use `Files.setLastModifiedTime` with a clearly-different value (e.g., +1 hour) so resolution rounding cannot mask the change.

5. **`commentsBySymbol` and `positionsBySymbol`:** Phase 4 should explicitly state whether these `FileResult` fields are intentionally excluded (they are not called out in PERF-VERIFICATION.md as bottlenecks, but they share the `.view.mapValues(identity).toMap` pattern in nearby code).

6. **Phase 5 CommentsUnpickler:** Line 226's "If `CommentsUnpickler` receives an `addrMap` parameter, change its type" should state the verified outcome, not a conditional.

7. **`Interner.scala:22` off-by-one:** Use of `initialCapacity` is at line 23 inside the `Array.tabulate` body started at line 22.

8. **`ClasspathOrchestrator.scala:272` off-by-one:** `mergeResults` def starts at line 273; line 272 is the comment.

9. **Phase 7 scripted test failure mode:** Add one failure-mode scripted test (e.g., missing kyo-reflect dep produces a useful error).

10. **Phase 4 minimal test count:** 3 tests cover 3 files. Consider adding a `TastyOrigin._addrMap` lazy-decode behavioral test under the new field type.

## Summary

The plan is well grounded; spot-checks of 25 cited file:line locations match (two minor off-by-ones, one wildcard path). The eight-phase dependency graph is internally coherent, the Phase 4 → Phase 5 supersession is explicitly documented, and excluded items are tracked with PERF-VERIFICATION.md citations. The 9 blocking items are all localized: 4 are simple deferred-decision wordings to commit one direction; 2 are exact-path / caller-enumeration omissions; 1 is a real type-shape mismatch in Phase 4's modify list (Pass1Result vs FileResult); 1 is a missing ZIP-edge-case test surface; 1 (Phase 7 Scala 2.12-vs-3 cross-call) may require a design decision. Residual risk after fixing the blockers: the Channel mutex hotspot (contingency exists as a risk-note, but the contingency itself is an OR) and the Phase 8 acceptance targets (25 ms / 5 ms) lacking a fallback procedure if missed. Plan should be approved only after blocking items 1-9 are addressed.
