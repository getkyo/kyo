# Open question resolutions

Stage 1 of the kyo-tasty audit-fix /flow campaign. The 9 open questions
in 02-design.md are resolved as follows. Five (Q-002, Q-005, Q-006, Q-007,
Q-008) were research-knowable and resolved by sonnet flow-research-item
agents; four (Q-001, Q-003, Q-004, Q-009) were value-underdetermined and
settled by safety-first research per decisions-resolved.md.

## Q-001 Subtyping under-determination signal

**Resolution:** Use `enum SubtypeVerdict { Sub, NotSub, Unknown }` for the return type of `Type.isSubtypeOf`.

**Source:** decisions-resolved.md (Fork 1), safety lens.

**Brief rationale:** `Maybe[Boolean]` re-creates the exact bug the change is meant to fix; the idiomatic recovery `.getOrElse(false)` silently collapses `Unknown` back to "not a subtype". A tri-state enum forces the caller to pattern-match all three cases, and Scala 3 exhaustiveness checks catch any forgotten arm at the use site. The enum is also self-documenting (`SubtypeVerdict.Unknown` vs `Absent`), so a reader does not have to look up what `Absent` means in this context. Existing `Maybe[Boolean]` uses in the codebase (`kyo-schema/.../Json.scala:152,161`, `kyo-stats-otlp/.../OTLPModel.scala:151`) carry a different semantic ("absent = not specified by user"); reusing the type here would overload its meaning. Kyo already has many small public tri-state enums (`SymbolKind`, `Constant`, `Access`, `OS`), so adding one more is precedented.

**Design impact:** the API surface entry for Subtyping must reflect `SubtypeVerdict`, not `Maybe[Boolean]`. INV candidates that referenced `Maybe[Boolean]` for under-determination need to switch to `SubtypeVerdict.Unknown`.

## Q-002 ZLIB cross-platform path

**Resolution:** Mirror the JVM `InflaterInputStream` strategy on Native (uses scala-native's libz FFI in javalib); implement an in-tree pure-Scala RFC 1950 inflate in `shared/` that the JS `InflateHook` delegates to.

**Source:** research-findings/Q-002.md.

**Brief rationale:** Scala Native already ships `java.util.zip.InflaterInputStream` as part of its javalib, backed by `scala.scalanative.ffi.zlib`, so the Native hook can mirror the JVM implementation verbatim (no pure-Scala inflate needed on Native). For Scala.js, `java.util.zip` is absent from the javalib entirely, and no maintained pure-Scala inflate library with JVM+JS+Native artifact parity exists on Scaladex or GitHub as of May 2026 (fs2 compression uses Node.js `zlib` interop, not pure Scala). The correct strategy is therefore an in-tree pure-Scala RFC 1950 inflate in `shared/` for JS to delegate to, keeping the same `inflate(Array[Byte]): Array[Byte] < (Sync & Abort[TastyError])` contract. This avoids an external dependency on an unmaintained or JS-only library, matches the in-tree posture already established for cross-platform parity, and eliminates the current `NotImplemented` failure on both JS and Native.

**Design impact:** the M5 work changes per platform. JVM and Native both use `java.util.zip.InflaterInputStream` (Native gets it via libz FFI shipped in scala-native). Only JS needs an in-tree pure-Scala RFC 1950 inflate.

## Q-003 Tree decoder coverage decomposition

**Resolution:** Split by TASTy spec category (Category 1 modifiers, Category 2 tag+Nat, Category 3 tag+AST, Category 4 tag+Nat+AST, Category 5 length-prefixed).

**Source:** decisions-resolved.md (Fork 2).

**Brief rationale:** `TastyFormat.scala` partitions the tag space into five contiguous, named numeric ranges with explicit boundary constants (`firstASTtag`, `firstLengthTreeTag`, etc.). Each sub-phase's acceptance test reduces to "no `Tree.Unknown` arm fires for any tag in this numeric range", which is a one-line property test. Semantic-group decomposition crosses these numeric boundaries (literals span Category 2 and 3), so coverage tests would need hand-maintained tag lists. Pure numeric-range decomposition is mechanically equivalent but discards the spec-aligned names that make code review tractable; the category split keeps both.

**Design impact:** M1 splits into 5 sub-phases per category (not 4 as some earlier proposals had it).

## Q-004 TastyError byteOffset enrichment scope

**Resolution:** Add `byteOffset: Long` to every malformed-section TastyError case (`MalformedSection`, `ClassfileFormatError`, `SnapshotFormatError`).

**Source:** decisions-resolved.md (Fork 3).

**Brief rationale:** Every site that constructs one of these errors already has a byte-cursor in scope, so plumbing `byteOffset` adds no new state to propagate. Audit confirmed: `JarCentralDirectory.scala` (10+ sites operating on a ByteView cursor), `ClassfileUnpickler.scala` (cursor loop), `ConstantPool.scala` (`idx` + view cursor), `ModuleInfoReader.scala` (classfile reader cursor), `Tasty.scala:190,192,730,735` (decode catch blocks with cursor available). The alternative (enrich only structured-payload cases) leaves callers debugging `"MalformedSection: jar: empty file"` with no anchor, which is the exact safety gap the enrichment is supposed to close.

**Design impact:** the L5 work updates the API surface for all three error cases, and every callsite passes the cursor.

## Q-005 Snapshot format version bump scope

**Resolution:** Minor bump (minorVersion from 2 to 3). Add-only; existing layout unchanged.

**Source:** research-findings/Q-005.md.

**Brief rationale:** The existing layout has no field widths that need to widen. Section index entries already store offset and length as 8-byte Int64LE fields (`SnapshotWriter.scala:206-208`), and within section payloads the 4-byte Int32LE count/length fields are more than adequate for parent/typeParam/declaration counts per symbol. The PARENTS and MEMBERS sections already exist as registered names in `SnapshotFormat.sectionNames` and are written as empty byte arrays today; populating them is an add-only operation. A new TYPEPARAMS section is also add-only because the reader skips unknown sections by name via the sectionMap lookup. The versioning policy at `SnapshotFormat.scala:42-44` specifies that minor bumps cover add-only sections; old snapshots load with new sections empty, matching the existing fallback at `SnapshotReader.scala:170-177` which fills `_parents`/`_typeParams`/`_declarations` with `Chunk.empty`.

**Design impact:** M4 work is add-only; old snapshots still readable; INV-003 (versioning policy) is preserved.

## Q-006 AllowUnsafe callsite proof availability

**Resolution:** Every callsite either already has a proof or can adopt `Sync.Unsafe.defer`. No callsite requires structural refactoring.

**Source:** research-findings/Q-006.md.

**Brief rationale:** Total callsite count across kyo-tasty (including tests) is 159 sites (153 accessors + 6 Symbol.position calls). kyo-flow has zero callsites; kyo-ts uses `tastyquery.Symbols.*` exclusively and is out of scope. The breakdown is 133 ALREADY-PROOF (every internal decoder module already imports `AllowUnsafe.embrace.danger` locally, and all test callsites route through `BaseKyoCoreTest.run` which imports the proof at line 8), 26 SYNC-WRAPPABLE (the four example files, three test sites already in `Sync.defer`, the six public `Classpath` extension methods, and four propagation sites in `TypeOps` and `ClasspathOrchestrator`), and 0 REFACTOR-NEEDED. No callsite exists that is unreachable by proof propagation.

**Design impact:** A4 work proceeds as planned with no downstream blast radius.

## Q-007 Classpath.open canonical and delegate

**Resolution:** The `(roots, strict)` two-arg overload is canonical. The one-arg `open(roots)` overload delegates by calling the two-arg form with `strict = false` explicit (no default-param shim, named-argument call).

**Source:** research-findings/Q-007.md.

**Brief rationale:** Both overloads already delegate to the private `openImpl`; A2 flags that the delegation relationship is not made explicit in the public surface, so the no-strict overload should call the canonical public overload directly (`open(roots, strict = false)`) instead of calling `openImpl` itself. CONTRIBUTING.md §358-§382 states "simple variants delegate to the canonical implementation, never duplicate logic" and shows the fuller-arity form as canonical with shorter-arity variants delegating into it. All three external callsites in `kyo-tasty-bench` (`TastyQueryCompareBench.scala:141`, `ColdLoadBench.scala:91`, `ColdLoadFullBench.scala:150`) use the no-strict one-argument overload; no external callsite passes an explicit `strict` value, confirming the ergonomic shorthand status of the one-arg form.

**Design impact:** A2 work is mechanical.

## Q-008 README Reflect.Reads existence

**Resolution:** Aspirational. No `Reads` typeclass exists in the kyo-tasty Scala source tree. README line 41 reference is removed during L1/L2 rewrite.

**Source:** research-findings/Q-008.md.

**Brief rationale:** No `Reads` trait, class, object, or type alias exists anywhere in the kyo-tasty source tree (shared, jvm, js, native). The only definition of `trait Reads[A]` and `object Reads` is in `DESIGN.md` at lines 715-722, where they are design-document pseudocode for a planned Phase 6 feature (`Reflect.Reads` derivation macro). The README line at 41 ("There's also `Reflect.Reads[A]`, a typeclass for projecting a `Symbol` into your own data type via `derives` clauses.") documents this unimplemented typeclass as if it already exists. During the L1/L2 README rewrite, that line must be removed or replaced with a forward-looking note. There is no alternative real name to substitute: `Tasty.scala` contains no typeclass machinery of any kind related to `Reads`.

**Design impact:** L1/L2 README rewrite scope adds the `Reads` removal.

## Q-009 kyo.tasty.examples package placement

**Resolution:** Extract to a sibling `kyo-tasty-examples` sbt module. Examples no longer ship in the main `kyo-tasty` artifact.

**Source:** decisions-resolved.md (Fork 4), safety lens.

**Brief rationale:** At the current location `kyo.tasty.examples`, a user writing `import kyo.tasty.examples.*` (or autocompleting under `kyo.tasty.*`) picks up example code that ships in the main jar. Examples exist to demonstrate internals, so any unintended re-export through the main artifact widens the public surface against the safety goal. The internal-package alternative is semantically wrong because the examples ARE meant to be user-facing demonstrations; only their distribution should be separate. The extract is precedented: `kyo-examples` already exists as a separate module with its own `package examples` (`build.sbt:163,1045`), and `kyo-tasty` already spawns sibling modules (`kyo-tasty-fixtures`, `kyo-tasty-bench` at `build.sbt:508,518`). One additional `lazy val` entry, paid once.

**Design impact:** new build.sbt entry for `kyo-tasty-examples`. Package declarations change to top-level `package examples` matching the precedent in `kyo-examples`. Tests for examples (if any) move with them.
