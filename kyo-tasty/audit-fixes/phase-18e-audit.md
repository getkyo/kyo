# Phase 18e Audit

## Summary

PASS with one WARN and three NOTEs. The three substantive additions (IMPORT, EXPORT,
ANNOTATION) are correctly implemented and structurally symmetric. The INV-005
zero-Unknown claim is technically sound but the corpus is limited to two small synthetic
fixtures. The decisions doc contains a minor internal contradiction. No blockers.

---

## Findings

### 1. INV-005 corpus substance - WARN

Test 18e-3 sweeps only `someObjectTasty` (674 bytes) and `plainClassTasty` (509 bytes).
The full fixture library in `Embedded.scala` has 10 TASTy byte arrays; 8 were not
included in the sweep. None of the fixtures are compiled from production kyo-core or
kyo-stdlib source. The zero-Unknown result is valid for those two fixtures, but the
decisions doc itself notes that the count reaches zero precisely because the fixtures
do not emit macro tags (QUOTE/SPLICE), type-lambda tags (TYPELAMBDAtype/LAMBDAtpt),
or bounded given selectors at the body level. A fixture with `import foo.{given SomeType}`
would exercise IMPORTED + BOUNDED (tag 102) inside the selector loop. BOUNDED is a
category-3 tag, not a modifier (tags 1-59) and not IMPORTED (75), so it falls into the
else-branch and is consumed by `readTree`. This is correct for cursor integrity but the
`readImportSelectors` scaladoc does not mention BOUNDED. The sweep should be extended
in Phase 22 to cover at least the remaining 8 embedded fixtures and, ideally, a fixture
with a `given` import. Route to Phase 22 (corpus expansion).

### 2. Import / Export / AnnotationNode ADT placement - NOTE

`Import`, `Export`, and `AnnotationNode` are appended at the tail of the Tree ADT,
just before `Unknown`. `PackageDef` is at line 514; `Import` and `Export` are at
lines 604-607. Package-level statement nodes (PackageDef, Import, Export) could be
grouped for readability. The current placement is not incorrect -- the trailing
position follows the established pattern for cases added in later phases -- but
locality to `PackageDef` would improve navigation. Low priority; route to a future
doc-sweep phase.

### 3. AnnotationNode naming - OK

`Tree.Annotation` would conflict in unqualified contexts: `Tasty.Annotation` is a
`final class` at the top level of `object Tasty`, and `Tree` is also nested inside
`object Tasty`. Code matching on tree nodes after `import kyo.Tasty.*` would see both
`Annotation` and `Tree.Annotation` in scope, making `case _: Annotation =>` ambiguous.
`AnnotationNode` resolves this cleanly. The ScalaDoc on the case class calls it
"In-tree annotation node (ANNOTATION tag)" which is concise and accurate. The decisions
doc section "Tree ADT additions" explains the rationale. Naming is sound.

### 4. readImportSelectors helper correctness - OK with NOTE

The helper correctly handles:
- IMPORTED (75) + optional RENAMED (76): reads nameRef, builds `Tree.Ident`, skips
  renamed-to name.
- Modifier tags (1-59): consumed with a `readByte`, with an extra `skipOneTree` for
  PRIVATEqualified and PROTECTEDqualified.
- Unexpected content (catch-all): consumed via `readTree(view, ctx)` to maintain cursor
  integrity. This covers BOUNDED (102, used in `import foo.{given T}`), which appears
  after the IMPORTED nameRef for a bounded given selector. BOUNDED falls through to the
  catch-all correctly; cursor integrity is maintained.

One NOTE: the scaladoc says "Wildcards and omit-selectors that dotty may emit as bare
IMPORTED with a wildcard name are handled the same way" but does not mention BOUNDED
(the actual wire encoding for `import foo.{given T}`). Route to Phase 21d (doc sweep).

### 5. EXPORT vs IMPORT structural symmetry - OK

Both EXPORT (177) and IMPORT (132) arms call `readImportSelectors` without duplication.
The two arms are identical in structure (readEnd, readTree for qual, readImportSelectors,
goto, return new case class). No copy-paste drift.

### 6. Plan vs reality drift recording - OK with NOTE

The decisions doc explicitly classifies all category-5 tags into KEEP/REPLACE/ADD with
per-entry rationale. The 18 catch-all KEEP entries are each labeled "skip+Unknown
acceptable" with a semantic reason (type-position, macro, type-only), so the "21
already covered" summary in the commit message is transparently supported.

One NOTE: the "IMPORT selectors" section of the decisions doc is internally
contradictory. The first bullet says "Each selector is represented as Tree.Unknown
(plain skip)" and "represented as Chunk.empty for now." The next bullet says "each
selector is a Tree.Ident built from the IMPORTED nameRef." The code builds `Ident`
nodes, not `Unknown`; the first sentence describes a discarded earlier design. The
contradiction is cosmetic (code is correct) but a reader following the doc would be
confused. Route to Phase 21d doc sweep.

### 7. Code quality - OK

No em-dashes, no semicolons as statement separators, no `asInstanceOf`, no
`Option`/`Some`/`None` (the pre-existing `Some`/`None` at lines 831-832 and 868-869
are outside the diff). `mutable.ArrayBuffer` in `readImportSelectors` uses the
file-level `import scala.collection.mutable`, consistent with the rest of the file.
`Chunk.from(buf.toSeq)` matches the pattern used in `readUnapplyParts` and
`readDefDefParamsAndTpt`. No default parameters introduced.

---

## Recommendations

- WARN (INV-005 corpus, route Phase 22): extend the zero-Unknown sweep to all 10
  embedded fixtures and add a fixture with a `given` import to exercise BOUNDED inside
  `readImportSelectors`.
- NOTE (ADT placement, route doc sweep): consider grouping `PackageDef`, `Import`, and
  `Export` together in the Tree ADT for locality.
- NOTE (scaladoc gap, route Phase 21d): `readImportSelectors` scaladoc should mention
  BOUNDED as the wire encoding for bounded given selectors.
- NOTE (decisions doc inconsistency, route Phase 21d): the "IMPORT selectors" section
  describes both `Tree.Unknown` (old design) and `Tree.Ident` (final design); the stale
  sentence should be removed.
