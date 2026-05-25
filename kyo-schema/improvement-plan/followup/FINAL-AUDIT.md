# Final audit, kyo-schema followup campaign

## Test execution per platform

| Aggregate | succeeded | failed | pending | Suites | wall |
|---|---:|---:|---:|---:|---|
| `kyo-schema/test` (JVM) | 1761 | 0 | 0 | 17 | 5s |
| `kyo-schemaJS/clean; kyo-schemaJS/test` | 1750 | 0 | 0 | 16 | 84s |
| `kyo-schemaNative/clean; kyo-schemaNative/test` | 1750 | 0 | 0 | 16 | 124s |
| `kyo-data/test` (cross-aggregate) | 3697 | 0 | 3 | 32 | 55s |

Pending in kyo-data: 3 (pre-existing, not introduced by this campaign; the only kyo-data touch was Phase 4's narrowed TypeBounds peel in TagMacro).

## Commit chain (1c000ba7b..HEAD, 14 followup commits)

```
1f79144b6 [schema] rename / fold ad-hoc test files to topic-based convention      (Phase 14)
44e6bd367 [schema] Schema.derived[A & B] via IntersectionMacro …                  (Phase 13)
cc1a84468 [schema] delete drift-guard infrastructure                               (Phase 12)
3ee893628 [schema] eliminate hardcoded type lists + delete PlatformSymbols hook   (Phase 11)
39e895a52 [schema] UnionMacro: delegate leg dispatch to sealedWriteBody …         (Phase 10)
2877e694c [schema] PlatformSchemas: promote URI and Locale to shared; …           (Phase 9)
bec92bbba [schema] delete KeyCodec; route Map[K, V] via Dict array-of-pairs       (Phase 8)
0227fe64b [schema] arraySchema / arraySeqSchema / queueSchema / … as .transform   (Phase 7)
539f6a490 [schema] internal _fieldNameMap: Map[Int, String] -> Dict[Int, String]  (Phase 5)
02c05d3b3 [data] narrow TagMacro TypeBounds peel to Java <FromJavaObject> only    (Phase 4)
9109ba26f [schema] drop redundant trailing () after discard(r.skip()) …           (Phase 3)
26ecf23db [schema] remove // cast: / // Unsafe: / // @unchecked: …                (Phase 2)
08c499893 [schema] strip dev-process references …                                  (Phase 1)
30ec730d4 [schema] bug-fix campaign + gap-fill: 5 bugs, 17 phases, cross-platform (prior campaign squash)
```

Phase 6 was decided-skipped (no commit; rationale below).

Diff stats vs prior squash base: `48 files changed, 6515 insertions(+), 2148 deletions(-)`.

## Plan compliance per phase

| Phase | Commit | Plan-spec matches | Notes |
|---|---|---|---|
| 1 | 08c499893 | YES | Dev-process refs stripped; remaining `Phase N` strings are SchemaSerializer state-machine variable + a meaningful 1-line history note in SchemaTest 5330 + a Phase-13 fixture header comment (all legitimate, not provenance). |
| 2 | 26ecf23db | YES | Grep `// cast:` / `// Unsafe:` / `// @unchecked:` in main = 0. |
| 3 | 9109ba26f | YES | Trailing `()` after `discard(r.skip())` removed. |
| 4 | 02c05d3b3 | YES | TagMacro TypeBounds peel narrowed; kyo-data 3697/0 pass. |
| 5 | 539f6a490 | YES | `_fieldNameMap` uses `kyo.Dict[Int, String]` at FocusMacro.scala:375 and :434. |
| 6 | n/a | DECIDED-SKIPPED | Plan documented the call: collection givens already take `using Frame`; remaining `Frame.internal` sites (22 occurrences across Schema.scala, IntersectionMacro.scala, SerializationMacro.scala) are inside macro-emitted code where the call site's `Frame` isn't reachable. Skipping is in line with plan caveat; the count of 22 is concentrated in macro emission, ZERO in the new collection givens. |
| 7 | 0227fe64b | YES | array/arraySeq/queue/sortedSet/sortedMap pairs now `.transform` delegations. |
| 8 | bec92bbba | YES | KeyCodec.scala + KeyCodecTest.scala deleted; `mapSchema` delegates via `dictSchema[K,V].transform`. Bonus: latent dictSchema readFn bug fix (missing `hasNextElement` calls). |
| 9 | 2877e694c | YES | URI + Locale promoted to shared `JavaPlatformSchemas`; URL/InetAddress/Path/File/Currency remain JVM-only with documented table. |
| 10 | 39e895a52 | YES | UnionMacro delegates leg dispatch to `sealedWriteBody` via VariantInfo. |
| 11 | 3ee893628 | YES — biggest architectural change. `isSerializableType` deleted; `MacroUtils.{platform,collection,optional,map,base,extended}PrimitiveSymbols` deleted; `PlatformSymbols.scala` deleted from all 3 platforms; new `PrimitiveKindFor[T]` typeclass at Structure.scala:522 with 20 givens; `MacroUtils.containerSymbolsFromSchema` walks Schema companion at macro time. Grep for old helpers: 0 hits. |
| 12 | cc1a84468 | YES | Drift-guard infra (MacroUtilsDrift{Macro,Test}, SerializationMacroDrift{Macro,Test}) absent on disk. |
| 13 | 44e6bd367 | YES — newest feature. IntersectionMacro.scala present; FocusMacro AndType branch dispatches via `IntersectionMacroProxy.derive[A]` at FocusMacro.scala:674; ExpandMacro AndType branch at ExpandMacro.scala:47 with flattenAndType helper at :139. Reuses SerializationMacro infrastructure as specified. Tests round-trip Named & Aged via anonymous synthesis. |
| 14 | 1f79144b6 | YES (one minor deviation) | UnionTest renamed to UnionMacroTest; NestedTransformTest + CompositionMatrixTest folded into SchemaTest; JavaEnumTest folded; CodecJvmTest retained. **Deviation**: UnionMacroTest stayed at `kyo/UnionMacroTest.scala` (package `kyo`) rather than moving to `internal/UnionMacroTest.scala` (package `kyo.internal`). Justified: top-level `case class Foo / Bar / Holder` fixtures must live at `kyo` package for derivation macros to see them. Naming convention (test name is prefix-+`Test` of source `UnionMacro.scala`) is still satisfied. |

## Cross-cutting checks

- `git status --short`: clean working tree (no untracked files).
- `Frame.internal` count in `kyo-schema/shared/src/main/`: **22** across 3 files (Schema.scala, IntersectionMacro.scala, SerializationMacro.scala) — all in macro-emitted code per Phase 6 decision; ZERO in collection givens.
- Hardcoded type-list helpers (`isSerializableType`, `MacroUtils.{platform,collection,optional,map,base,extended}PrimitiveSymbols`): **ZERO hits confirmed**.
- `KeyCodec` in `kyo-schema/{shared,jvm}/src/main/`: **ZERO hits confirmed**.
- `// cast:` / `// Unsafe:` / `// @unchecked:` comments in shared main: **ZERO hits confirmed**.
- `Phase N` / `// Added Phase` dev-refs in code/tests: scoped to 3 legitimate occurrences:
  - `SchemaSerializer.scala:322-326` — state-machine variable doc (allowed).
  - `SchemaTestData.scala:105` — `// Intersection-type test fixtures (Phase 13)` — one fixture header comment. Minor; not provenance leak. **NOTE**.
  - `SchemaTest.scala:5330` — multi-line historical note describing the Phase-11 behaviour change (silent-demote → loud-fail) inside a test docstring. Reads as durable history, not work-log. **NOTE**.
- `PlatformSymbols.scala` (3 platforms): **ABSENT confirmed**.
- Drift-guard infra (MacroUtilsDrift{Macro,Test}, SerializationMacroDrift{Macro,Test}): **ABSENT confirmed**.
- Test file naming compliance: every test file is a prefix-+`Test` of a source file:
  - Shared: Builder, Changeset, Codec, Compare, Convert, Focus, Json, Modify, Protobuf, Ryu, Schema, Structure → all map to `<X>.scala`. SchemaTestData is a fixture (no `Test` class). Test.scala is the base. UnionMacroTest → `internal/UnionMacro.scala`.
  - Shared/internal: FastFloat, FastFloatGo, FastFloatPow10Table → all map to corresponding internal sources.
  - JVM: CodecJvmTest → `Codec.scala` (Jvm-suffixed prefix per plan).
- `pending` markers in kyo-schema tests: **ZERO confirmed**.
- TODO / FIXME / XXX introduced in `kyo-schema/` or `kyo-data/`: **ZERO** (only match is a meta-line about TODO counts inside PROGRESS.md, expected).

## Findings

- **BLOCKER**: 0.
- **WARN**: 0.
- **NOTE**:
  1. `Frame.internal` 22-site count concentrates in macro emission paths (Schema.scala / SerializationMacro.scala / IntersectionMacro.scala). Phase 6 chose to skip these as unreachable without an AST round-trip to inject a caller `Frame`. Acceptable but worth flagging as a future-work item for a dedicated macro-Frame-threading effort.
  2. Two `Phase 13` / `Phase 11` mentions in tests (SchemaTestData.scala:105, SchemaTest.scala:5330) survived the Phase-1 sweep. SchemaTest.scala:5330's comment is a durable historical note about the behaviour change (loud-fail vs silent-demote) and reads fine; SchemaTestData.scala:105's `(Phase 13)` parenthetical is provenance-style and could be tightened to a topic-only comment, but it does not affect correctness or readability.
  3. Phase 14 left `UnionMacroTest` in package `kyo` rather than `kyo.internal` because top-level fixture case classes need that package for derivation. The naming convention (`UnionMacroTest` is `UnionMacro` + `Test`) is still upheld; only the directory placement diverged.
  4. `kyo-data` pending count (3) is pre-existing and unrelated to Phase 4's TagMacro change.

## Overall verdict

**SHIP.**

All 14 phases match plan. Four green test runs across JVM / JS / Native / kyo-data with 8958 tests passing, zero failures, zero new pendings. The two NOTEs about residual `Phase N` strings are cosmetic and amount to two lines of text; neither is a campaign provenance leak. The Phase-6 decided-skip is documented in the plan with sound rationale (macro-emitted code lacks a reachable caller `Frame`). The Phase-14 UnionMacroTest placement deviation is justified by Scala macro-visibility constraints. The campaign delivers what `execution-plan.md` promised: KeyCodec / PlatformSymbols / drift-guard infrastructure deleted, hardcoded type lists replaced by a typeclass-driven source of truth (`PrimitiveKindFor[T]` + `containerSymbolsFromSchema`), intersection-type derivation enabled, and test files conform to the topic-prefix convention. Safe to squash and push.
