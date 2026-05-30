# Phase 22a-c Combined Audit

## Summary

PASS with two WARNs. The three phases are coherent and internally consistent. No
BLOCKERs. The two WARNs are pre-existing architectural decisions (MUTF-8 approximation,
Zip64 message coupling) that are correctly documented in decisions logs; neither
introduces a regression or a new defect.

---

## Findings

### 1. Phase 22a overlong-null contract - WARN

The Utf8.decode path (StandardCharsets.UTF_8) IS used to decode classfile
CONSTANT_Utf8 entries. The call chain is:

    ConstantPool.utf8() -> CpEntry.Utf8Lazy.decode(interner)
                        -> Interner.intern(bytes, offset, length)
                        -> Utf8.decode(copiedBytes, 0, length)  [line 92, Interner.scala]

Java classfiles use Modified-UTF-8 (MUTF-8) for CONSTANT_Utf8 entries, which encodes
U+0000 as [0xC0, 0x80] rather than the zero byte. The pure-UTF-8 decoder silently
replaces that sequence with two U+FFFD characters instead of U+0000.

This is a real semantic divergence for any classfile string containing an embedded null.
The phase-22a-decisions.md acknowledges it as a "known approximation that works for
all well-formed class names in practice". That is accurate: JVM class and member names
cannot legally contain U+0000, so the risk is confined to pathological constant pool
strings (e.g., string constants in code, not type/method names). No production test
failure is expected.

The test (Test 20) correctly documents the actual behavior. The WARN is because the
divergence is a classfile path (not only a TASTy path), which the original audit
question asked to surface. It is a pre-existing architectural decision, not a
regression introduced by Phase 22a.

### 2. Phase 22b MaxDepth bounds - OK

Both branches are covered. Test 6 (TypeArenaTest) builds MaxDepth+1 = 1025 levels of
Applied nesting and asserts DepthExceededException is thrown during merge. Test 7 builds
MaxDepth-1 = 1023 levels and asserts no exception. Test 8 (added in Phase 22b) repeats
the same boundary check for the Rec arm of internRec specifically, at exactly 1023 Rec
wrappers. The MaxDepth value is 1024 (TypeArena.scala line 116). The boundary semantics
are: depth >= MaxDepth throws; depth == MaxDepth-1 succeeds. Both arms are explicitly
exercised. OK.

### 3. Phase 22b RecThis leaf invariant - WARN

The production code correctly treats RecThis as a leaf in both recurse and internRec
(TypeArena.scala line 38: `case Tasty.Type.RecThis(_) => t`). This prevents infinite
structural recursion when a Rec type contains a RecThis self-reference.

The invariant is NOT documented with an inline comment in TypeArena.scala. The match arm
appears without explanation. A future refactor that adds structural recursion into
RecThis.rec would silently break cycle safety. The test (Test 9, TypeArenaTest) exercises
this path and would catch a regression, but the production code itself gives no hint that
the leaf treatment is intentional rather than accidental. A brief comment at the RecThis
match arm would prevent misunderstanding. This is a documentation gap, not a defect.

### 4. Phase 22b budget paths coverage - OK

Both budget termination paths are now covered:

- Phase 15 Test 12: calls Subtyping.isSubtype directly with budget=0, which returns
  Unknown immediately at the `if budget <= 0 then Unknown` guard. Exercises the
  zero-budget early exit without any recursive unfolding.

- Phase 22b Test 14 (SubtypeTest): builds a 66-level Rec chain and calls t.isSubtypeOf(t)
  with the default budget=64. The phase-22b-decisions.md confirms each Rec unfolding
  decrements budget by 1 via `isSubtype(subUnfolded, supUnfolded, cp, budget - 1)`. After
  64 unfolds the budget reaches 0 mid-chain and Unknown is returned. This exercises real
  recursive traversal termination, distinct from the zero-budget shortcut.

Both paths are covered. OK.

### 5. Phase 22c Zip64 assertion brittleness - NOTE

The test P22c-T1 asserts `reason.contains("3000000000")`. This is a contract on the
exact string representation of the 64-bit offset value in the MalformedSection reason.
If the production code ever changes to include a derived field (e.g., a segment boundary
computed from the offset) rather than the raw offset, the test breaks without any
semantic change in the detection logic.

The assertion is the correct approach for proving 64-bit field reading (vs 32-bit
truncation), and the decisions doc explains the rationale clearly. The brittleness is
acceptable given the test's purpose, but the tight coupling to the error message format
is worth flagging. NOTE level only; no change required.

### 6. Phase 22c JMOD placeholder - NOTE

P22c-T3 is `Sync.defer(succeed)` with no meaningful assertion. The deferral is
documented in phase-22c-decisions.md with the concrete list of production changes
required (detectJMOD magic, prefixOffset threading through findEocd, readCenLocation,
listEntries, and JarMappedReader). The placeholder satisfies the no-scope-cuts policy
for production-structural work in a tests-only phase. NOTE level; acceptable.

### 7. Code quality across all 3 phases - OK with NOTE

No em-dashes, asInstanceOf, Option/Some/None, default parameters, or explicit return
statements in any of the three phases. Maybe/Present/Absent conventions followed
throughout.

Semicolons appear in Phase 22c's P22c-T1 and P22c-T2 byte-initialization lines (e.g.,
`zip64EocdRec(0) = 0x50; zip64EocdRec(1) = 0x4b; ...`). This pattern is consistent
with the pre-existing P04a-T2 test in the same file, which uses the same style for
byte-array initialization. It is idiomatic for dense byte-layout code where individual
assignments have no independent logical meaning. Stylistically borderline but consistent
with the established file convention.

The SubtypeTest comment on line 199 uses a semicolon inside a comment sentence (not
a statement separator), which is acceptable.

---

## Recommendations

- Add a one-line comment to TypeArena.scala at the `case Tasty.Type.RecThis(_) => t`
  match arm explaining that RecThis is treated as a structural leaf to break
  Rec/RecThis cycles during merge. This prevents the invariant from being accidentally
  removed in a future refactor.

- Consider extracting a helper method for writing Zip64 EOCD and locator bytes in
  JarCentralDirectoryTest (P22c-T1 and P04a-T2 share nearly identical byte-layout
  code). Not a defect; a cleanup opportunity for a future test-debt phase.

- The MUTF-8 approximation in ConstantPool/Interner is a known limitation. If future
  work adds support for classfiles with embedded-null strings (e.g., Kotlin
  string constants), the Utf8.decode call site in Interner.scala should be updated
  to use DataInputStream.readUTF or a dedicated MUTF-8 decoder.
