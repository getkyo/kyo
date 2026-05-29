# Phase 1 prep

Phase name: Rewrite documentation
Files to produce: 0
Files to modify: 4
Tests: 4
Plan cites: ./05-plan.md §Phase 1

## Verbatim API signatures

This phase is doc-only (scaladoc rewrites, no signature changes). The signatures below are the subjects of the scaladoc additions; they are quoted verbatim as they exist on HEAD.

- `def open(roots: Seq[String])(using Frame): Classpath < (Sync & Async & Scope & Abort[TastyError])`
  at kyo-tasty/shared/src/main/scala/kyo/Tasty.scala:899

- `def open(roots: Seq[String], strict: Boolean)(using Frame): Classpath < (Sync & Async & Scope & Abort[TastyError])`
  at kyo-tasty/shared/src/main/scala/kyo/Tasty.scala:903

- `def openCached(roots: Seq[String], cacheDir: String)(using Frame): Classpath < (Sync & Async & Scope & Abort[TastyError])`
  at kyo-tasty/shared/src/main/scala/kyo/Tasty.scala:910

- `def findClass(fqn: String): Maybe[Symbol]`
  at kyo-tasty/shared/src/main/scala/kyo/Tasty.scala:1014
  (extension on `Classpath`; plan asks for doctest addition here)

- `def topLevelClasses: Chunk[Symbol]`
  at kyo-tasty/shared/src/main/scala/kyo/Tasty.scala:1032

- `def packages: Chunk[Symbol]`
  at kyo-tasty/shared/src/main/scala/kyo/Tasty.scala:1026

- `def apply(s: String): Name`
  at kyo-tasty/shared/src/main/scala/kyo/Tasty.scala:48
  (plan asks for doctest addition; existing scaladoc at line 47)

- `val empty: Flags`
  at kyo-tasty/shared/src/main/scala/kyo/Tasty.scala:72
  (plan asks for doctest addition)

## File anchors

- kyo-tasty/README.md (329 lines total)
  - Line 1: title `# kyo-reflect` -- rename to `# kyo-tasty`
  - Lines 9-18: first code block uses `import kyo.Reflect.*` and `Reflect.Classpath.open`, `ReflectError` -- full rewrite
  - Line 31: prose `kyo-reflect collapses all of that` -- rename
  - Lines 35-41: Core concepts paragraph mentions `Reflect.Classpath`, `Reflect.Symbol`, `Reflect.Type`, and `Reflect.Reads[A]` on line 41 -- rewrite; line 41 is the `Reflect.Reads` removal target (Q-008)
  - Lines 43-84: Use case 1 code block uses `kyo.Reflect.*`, `Reflect.Classpath.openCached`, `.kyo-reflect-cache`, `Reflect.Symbol`, `Reflect.SymbolKind.Method`, `ReflectError` -- rewrite to `Tasty.*`
  - Lines 92-116: Use case 2 code block uses `kyo.Reflect.*`, `Reflect.Classpath.openCached`, `.kyo-reflect-cache`, `ReflectError` -- rewrite
  - Lines 125-139: Use case 3 code block uses `kyo.Reflect.*`, `Reflect.Classpath.openCached`, `.kyo-reflect-cache`, `Reflect.classFqn[A]`, `ReflectError`, `Reflect.SymbolKind.Val` -- rewrite
  - Lines 155-175: Use case 4 code block uses `kyo.Reflect.*`, `Reflect.Classpath.openCached`, `.kyo-reflect-cache`, `ReflectError` -- rewrite
  - Lines 188-215: Schema-driven section (`derives Reflect.Reads`, `Reflect.Classpath.openCached`, `.kyo-reflect-cache`) -- either remove entirely (Q-008: no implementation) or mark as planned; plan says remove
  - Lines 219-252: Cross-language bridging section uses `kyo.Reflect.*`, `Reflect.symbolToRecord`, `Reflect.Type`, `ReflectError` -- rewrite
  - Line 260: standalone `Reflect.Classpath.openCached(roots, cacheDir = ".kyo-reflect-cache")` -- rewrite
  - Lines 278-283: Effect signatures block uses `ReflectError` -- rewrite to `TastyError`
  - Lines 291-296: Performance table title column `kyo-reflect` -- rename
  - Lines 299-308: Prose and table mention `Phase C`, `kyo-reflect` -- rewrite
  - Line 323: `ReflectError.NotImplemented` and `DESIGN.md Section 18` reference -- rewrite
  - Lines 325-328: Learn more section references `kyo.reflect.examples` path -- update to real examples path

- kyo-tasty/DESIGN.md (1549 lines total)
  - Line 1: title `# kyo-reflect Design` -- rename to `# kyo-tasty Design`
  - Lines 9-34: Section 1 (`## 1. Goals and Non-Goals`) -- rename header to `## 1. Goals`; split perf-target goals (line 18: "Materially better cold-load"; line 19: "Better warm performance"; line 20: `derives Reflect.Reads`) into a new `## 1a. Performance targets` section; remove `derives Reflect.Reads` goal (no implementation per Q-008)
  - Line 16: `Abort[ReflectError]` in Goals -- rename to `Abort[TastyError]`
  - Line 20: `derives Reflect.Reads` goal -- remove (moves out of Goals; Q-008)
  - Line 40: performance table header `kyo-reflect target` -- rename
  - Lines 51-95: Section 3 Architectural Overview ASCII diagram uses `kyo.Reflect`, `kyo.Reflect.Reads`, `kyo.internal.reflect` -- rename
  - Lines 101-104: Section 4 Module Layout lists `kyo-reflect/`, `Reflect.scala`, `ReflectError.scala` -- rename
  - Line 138: `Reads.scala` in module layout -- remove or annotate as planned
  - Line 869: `## 13. Reflect.Reads Derivation Macro` section header -- rename (the section covers design for Phase 6; the section itself stays but with updated header and no `Reflect.*` in prose)
  - Lines 1369-1371: versioning paragraph uses `kyo-reflect` -- rename
  - Lines 1377-1416: Section 18 phased plan uses `kyo-reflect-bench`, `kyo-reflect-fixtures`, `kyo-reflect` -- rename
  - Lines 1473-1549: Sections 22, 23, 24, 25 use `kyo-reflect` throughout (locked-decision record at line 1475; siblings section) -- rename; note the locked decision at line 1475 says `Module name kyo-reflect (replaces kyo-tasty). LOCKED.` -- this locked decision must itself be updated to reflect the current name is `kyo-tasty`

- kyo-tasty/shared/src/main/scala/kyo/Tasty.scala (lines with phase comments)
  - Line 78: `// Phase 0 flags (bits 0-15)` -- rewrite to `// Core access flags (bits 0-15)`
  - Line 95: `// Phase 3 flags (bits 16+)` -- rewrite to `// Extended modifier flags (bits 16+)`
  - Line 124: `// Phase 10 flag (bit 44): identifies symbols decoded from Scala 2 pickles embedded in classfiles.` -- rewrite to `// Scala 2 origin flag (bit 44): identifies symbols decoded from Scala 2 pickles embedded in classfiles.`
  - Line 505: `// Write-once slots populated during classpath orchestration (Phase 3 / Phase 5).` -- rewrite, drop phase references
  - Line 589: `// Resolving accessors (return TastyError.NotImplemented in Phase 0).` -- rewrite, drop phase reference
  - Line 602: `*   Implemented in v2 Phase 5. Populated eagerly during Pass 1 / mergeResults. Pure in v3 Phase 3.` -- rewrite per plan to `*   Populated eagerly during cold-load mergeResults; readable as a pure accessor thereafter.`
  - Line 826: `/** The complete Symbol.Origin ADT. Phase 5 adds JavaOrigin construction sites; the ADT itself is sealed here. */` -- rewrite, drop Phase 5 reference
  - Line 1012: `* whatever heap state is there (closed-state enforcement is Body-only, Phase 4).` -- rewrite per plan to `* (closed-state enforcement is Symbol.body only).`
  - Line 1036: `* Pure accessor: reads from immutable error state populated after Phase C. Empty for clean classpaths.` -- rewrite, drop Phase C reference
  - Lines 895-911: Classpath.open/openCached scaladoc -- add effect-row rationale paragraph (Sync/Async/Scope/Abort explanation) per plan
  - Lines 47-64: Name.apply / Name extension -- add doctest per plan
  - Line 72: Flags.empty -- add doctest per plan

- kyo-tasty/shared/src/main/scala/kyo/internal/tasty/classfile/ClassfileUnpickler.scala
  - Line 19: `Unresolved parent types: super class (if any) followed by implemented interfaces. Phase 7 resolver replaces with real symbols.` -- rewrite to remove "Phase 7 resolver" reference; replace with architectural description
  - Line 25: `The TypeArena passed in; included for Phase 7 merge.` -- rewrite to remove "Phase 7 merge" reference

## Edge cases and gotchas

- kyo-ts uses `tastyquery.Symbols.*`, not `kyo.Tasty.*`. The rename does NOT cascade into kyo-ts source.
  Cited at kyo-tasty/audit-fixes/research-findings/Q-006.md (kyo-ts out-of-scope verdict):
  `import tastyquery.Symbols.*` at /Users/fwbrasil/workspace/kyo/.claude/worktrees/quirky-pondering-wadler/kyo-ts/jvm/src/main/scala/kyo/gen/Main.scala:1-5

- `.kyo-reflect-cache` appears only in README.md and DESIGN.md (doc files in scope). The production example files (`CodegenExample.scala`, `IdeHoverExample.scala`, `JavaScalaBridgeExample.scala`, `RuntimeReflectionExample.scala`) already use `.kyo-tasty-cache`:
  at kyo-tasty/shared/src/main/scala/kyo/tasty/examples/IdeHoverExample.scala:34, 66, 77
  at kyo-tasty/shared/src/main/scala/kyo/tasty/examples/CodegenExample.scala:31
  at kyo-tasty/shared/src/main/scala/kyo/tasty/examples/JavaScalaBridgeExample.scala:23
  at kyo-tasty/shared/src/main/scala/kyo/tasty/examples/RuntimeReflectionExample.scala:19, 32
  No production code has `.kyo-reflect-cache`; only doc files need the string replacement.

- The plan's `before` for ClassfileUnpickler.scala line 19 says `// Phase C: classfile attribute decoder` but the actual file has a `@param parents` doc comment: `Phase 7 resolver replaces with real symbols.` (line 19) and `included for Phase 7 merge.` (line 25). There is no `// Phase C:` standalone comment in this file. The impl agent must target lines 19 and 25 (both are `@param` doc lines inside `ClassfileResult`), not a standalone comment at the file top.
  Cited at kyo-tasty/shared/src/main/scala/kyo/internal/tasty/classfile/ClassfileUnpickler.scala:14-26

- DESIGN.md Section 22, decision 7 at line 1475 reads `Module name kyo-reflect (replaces kyo-tasty). LOCKED.` This is a historical record of the naming decision that was later reversed. During the rename sweep this line must be updated to reflect the current actual name (`kyo-tasty`), not silently left as a contradiction.
  Cited at kyo-tasty/DESIGN.md:1475

- DESIGN.md Section 13 header at line 869 is `## 13. Reflect.Reads Derivation Macro`. The section contains design pseudocode for a planned macro (Phase 6). The section stays in DESIGN.md (it is design documentation, not a claim that the API exists). The header must be renamed from `Reflect.Reads` to `Tasty.Reads` (or `## 13. Reads Derivation Macro`); prose references inside Section 13 must change `Reflect.Reads` to `Tasty.Reads`.
  Cited at kyo-tasty/DESIGN.md:869

- `Reflect.Reads` in README.md appears in two places: line 41 (aspirational typeclass mention, to be removed per Q-008) and lines 188-215 (schema-driven projection section using `derives Reflect.Reads`, also aspirational). Both must be removed. The `derives Reflect.Reads` code examples in the README cannot compile (no such typeclass in Tasty.scala), which would fail test 4 (README doctest extraction compiles).
  Cited at kyo-tasty/README.md:41 and kyo-tasty/README.md:188-215
  Confirmed no implementation at kyo-tasty/audit-fixes/research-findings/Q-008.md

- `Tasty.scala` has additional phase references at lines 505, 826, and 1036 that the plan's primary change list at lines 78/95/124/589/602/1012 does not enumerate, but the test scenario 3 (INV-021) scans for `// Phase [0-9CB]` regex. Lines 505, 826, and 1036 use `Phase [0-9]` in prose inside `/* */` doc comments, not as `// Phase [0-9CB]` standalone comments. They still fail the spirit of INV-021; the plan at lines 171-188 does include them. Impl agent must cover all nine phase-referencing lines in Tasty.scala, not just the three that match `// Phase [0-9CB]`.
  Cited at kyo-tasty/shared/src/main/scala/kyo/Tasty.scala:78, 95, 124, 505, 589, 602, 826, 1012, 1036

- README.md line 328 references `shared/src/main/scala/kyo/reflect/examples/` as the examples path. The actual examples path is `kyo-tasty/shared/src/main/scala/kyo/tasty/examples/` (confirmed by directory listing). This path reference must be updated.
  Cited at kyo-tasty/README.md:328 and kyo-tasty/shared/src/main/scala/kyo/tasty/examples/ (actual directory)

## Test-data suggestions

N/A (doc-only phase; no Scala test code added beyond TastyTest.scala stubs). The four test scenarios are grep/string-count based (test 1, 2, 3) or doctest-compile based (test 4). For test 4, every fenced `scala` block in the rewritten README must use only APIs that exist in `Tasty.scala` and are reachable via `import kyo.*`. The schema-driven / `derives Tasty.Reads` blocks must be removed (no implementation) to avoid doctest compile failures.

## Anti-flakiness deltas

N/A (doc-only phase; no new test code that could flake). The verification command runs `TastyTest` only, which does file I/O and string matching. No timing, fiber ordering, or resource-cleanup hazards in a doc-consistency test.

## Cross-platform notes

- platforms: [jvm, js, native]
- No platform-specific source changes. All four modified files are either doc (README.md, DESIGN.md) or in `shared/src/main/scala/`. TastyTest.scala lives in `shared/src/test/scala/kyo/` and runs on all three platforms via the verification command's JVM target plus the JS/Native equivalents listed in the plan.
- The `ClassfileUnpickler.scala` being in `shared/` means the phase-comment fixes apply to all three platforms automatically.

## Concerns

- **Plan's ClassfileUnpickler before/after is stale**: The plan's `files_modified` entry for `ClassfileUnpickler.scala` says `before: "// Phase C: classfile attribute decoder"` but no such line exists in the current file. The file contains `@param parents Phase 7 resolver replaces with real symbols.` (line 19) and `@param arena The TypeArena passed in; included for Phase 7 merge.` (line 25). The impl agent must target these two `@param` doc lines. The plan's before/after snippet is wrong. Concern: the impl agent should not search for the nonexistent `// Phase C:` pattern; it should use the actual lines 19 and 25. No escalation needed; this is a factual correction the impl agent can apply directly.
  Cited at kyo-tasty/shared/src/main/scala/kyo/internal/tasty/classfile/ClassfileUnpickler.scala:14-26

- **DESIGN.md locked decision contradicts rename**: Section 22 decision 7 at line 1475 says `Module name kyo-reflect (replaces kyo-tasty). LOCKED.` Leaving this unchanged after the rename sweep would create a document that first renames everything to `kyo-tasty` then contradicts itself in the locked decisions. The impl agent must update this decision entry to reflect the actual current state: the module is named `kyo-tasty`. This is in scope for Phase 1 (it is a doc rewrite).
  Cited at kyo-tasty/DESIGN.md:1475

- **README schema-driven section is all-or-nothing**: Lines 188-215 show a full code example using `derives Reflect.Reads`. Because `Reflect.Reads` does not exist, the example cannot compile (test 4). The entire section must be removed (not just the `derives` clause), or replaced with a forward-looking note without a compilable code block. The plan says remove it. Removing lines 188-215 also removes the `kyo.Record` cross-language bridging section (lines 219-252), which uses `Reflect.symbolToRecord` -- also unimplemented. The impl agent should remove both sections or clearly mark them as not compilable (by using non-fenced prose or `text` blocks instead of `scala` fences). This is within Phase 1 scope.
  Cited at kyo-tasty/README.md:188-252 and kyo-tasty/audit-fixes/research-findings/Q-008.md
