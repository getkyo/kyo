# Phase 18b + 18c Combined Audit

## Summary

Both phases are structurally correct and land clean. Phase 18b's semantic shift from cache-lookup to structural Shared node is sound; the tree-walking helpers in TreeUnpicklerTest do not handle Tree.Shared, creating a latent gap that is acceptable for now because the helpers are test-only and Shared nodes appear only in type position during annotation decode. Phase 18c correctly reclassified nine of eleven tags from category-3 to category-5; the plan pseudocode was wrong and the agent fixed it. One WARN surfaces for TYPEBOUNDS: the absent-hi sentinel is Tree.Unknown(0,0) rather than the lo-alias-returns-self logic used by TypeUnpickler. One NOTE on ANNOTATEDtype split: the pre-existing arm did produce Tree.Annotated for both tags; the split is correct and no external consumers pattern-match Tree.Annotated.

---

## Phase 18b findings

### 1. SHAREDterm semantic change - WARN

Before Phase 18b, SHAREDterm dereferenced the treeAddrCache and returned the cached node inline. Now it returns Tree.Shared(addr), a leaf. The three test helpers findLiteral, findIf, and findMatch (TreeUnpicklerTest.scala lines 91-116) each have a wildcard final arm that returns Absent/None for any unrecognised case -- including Tree.Shared. If a real-world annotation tree contains a SHAREDterm back-reference to a Literal or If node, the helpers will silently miss it.

The impact is bounded: these helpers are test-internal, not public API. Production consumers receive Tree.Shared and must resolve it themselves, which is the intended design for a structural rather than resolving decoder. The decisions doc explicitly documents the behaviour change. Acceptable for now; route a reminder to add Shared-resolution helpers when consumer tests are added in Phase 22+.

### 2. Constant inline arms - OK

The BYTEconst..STRINGconst arms are present in decodeTreeTag at lines 206-222 of TreeUnpickler.scala. Each calls the correct low-level reader (readNat, readInt, readLongNat) and wraps the result in Tasty.Tree.Literal(Tasty.Constant.XxxConst(...)). The shape produced matches the plan's Tasty.Tree.Literal(Constant.IntConst(7)) shape exactly. Constant.fromTastyTag is correctly NOT called here; it is an effectful async API unsuitable for the synchronous decoder. The decision not to add a new Literal arm in Phase 18b is sound because the arms were already present.

---

## Phase 18c findings

### 3. Category miscategorization - OK

The plan called all 11 tags category-3 (tag + sub-AST). The agent reclassified correctly. Cross-referencing TastyFormat.scala tag values against the TypeUnpickler decode arms confirms all nine (SUPERtype=158, REFINEDtype=159, APPLIEDtype=161, TYPEBOUNDS=163, ANNOTATEDtype=153, ANDtype=165, ORtype=167, MATCHtype=190, FLEXIBLEtype=193) are above firstLengthTreeTag=128 and use view.readEnd(). TypeUnpickler.scala uses readEnd() for every one of them (verified at lines 444-504 and 483-488). RECtype=100 and BYNAMEtype=93 are below firstLengthTreeTag and correctly treated as category-3. The agent fixed a genuine planning error.

### 4. ANNOTATEDtype split - OK

Confirmed via git show of the pre-18b state: the combined arm `case TastyFormat.ANNOTATEDtype | TastyFormat.ANNOTATEDtpt` returned Tree.Annotated for both tags. After the split, ANNOTATEDtype returns Tree.AnnotatedType and ANNOTATEDtpt retains Tree.Annotated. A search over the shared source tree finds Tree.Annotated pattern-matched only in TreeUnpickler.scala itself (line 588, the ANNOTATEDtpt arm) and TypeArena.scala (lines 62 and 182, which match on Tasty.Type.Annotated -- a different type-level case, not Tree.Annotated). No consumer pattern-matches Tree.Annotated as a Tree node from the decode output. The split is clean.

### 5. List-boundary derivation - OK

APPLIEDtype (TreeUnpickler.scala line 633) reads `val end = view.readEnd()` before the tycon, then calls `readTreesUntil(view, end, ctx)`. MATCHtype (line 657) reads `val end = view.readEnd()` before bound and scrutinee, then calls `readTreesUntil(view, end, ctx)`. Both derive end from readEnd(), not from view.position. The decisions doc explicitly documents that view.position was the wrong plan pseudocode value. Correct.

---

## Cross-cutting

### 6. Tree ADT growth - NOTE

Total Tree case count is now 45 (grep confirms 45 `final case class.*extends Tree` lines). Through phases 18a/b/c the additions are Modifier, Shared, and 11 type-form cases. No case names collide with existing ones. One naming asymmetry is visible: term-level tree cases use short names (Apply, Match, If, Block) while the new type-form cases use fully qualified names (AppliedType, MatchType, AndType). This is intentional disambiguation since AppliedType and Match would collide with pre-existing term cases or cause confusion. The coexistence of Annotated (ANNOTATEDtpt) and AnnotatedType (ANNOTATEDtype) follows the same pattern and is the right call.

TYPEBOUNDS warrants a separate note: when hi is absent (alias-only bounds), TreeUnpickler inserts Tree.Unknown(0, 0) as the hi field. TypeUnpickler, by contrast, returns the lo type directly without wrapping. The Tree.TypeBounds case therefore always has two children even in alias position, which forces consumers to check for the Unknown(0,0) sentinel. This is a minor semantic quirk that should be documented on the TypeBounds case class, or the case should be split into a two-field and a one-field variant in a future phase.

### 7. Code quality - OK

No em-dashes, no semicolons used as statement separators, no asInstanceOf, no Option/Some/None, no default parameters in either diff. The only possible concern is the view.position < end guard in TYPEBOUNDS, which mirrors TypeUnpickler's boundary guard exactly and is correct.

---

## Recommendations

- NOTE (test helpers, route Phase 22+): findLiteral/findIf/findMatch do not traverse Tree.Shared. If production annotation trees use SHARED back-references wrapping Literal nodes, these helpers will miss them. Add a Shared-resolution step or a deref helper when consumer tests are added.
- NOTE (TypeBounds sentinel, route future Tree ADT phase): Tree.TypeBounds always has two children; hi=Unknown(0,0) is the alias-only sentinel. Add a ScalaDoc note to the TypeBounds case class, or split into TypeBounds(lo, hi) and TypeAlias(alias) variants to match dotty's representation.
- NOTE (decisions doc, low priority): phase-18a-decisions.md still shows a `= 0L` default on byteOffset that was not carried into the final code. Patch during the next decisions-doc sweep.
