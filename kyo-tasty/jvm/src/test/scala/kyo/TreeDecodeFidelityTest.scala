package kyo

import kyo.internal.TestClasspaths

/** Fidelity tests for Tree decode: body presence and Tree tag handlers.
  *
  * Pins findings F-B-001..F-B-008 and F-E-003. All leaves are PENDING until Phase 05 un-pends them by implementing the 18+ Tree tag
  * handlers in `TreeUnpickler` that previously fell through to `Tree.Unknown`, fixing `Tree.New` arg threading, `Tree.Select` tpe,
  * TERMREFin, REPEATED/SeqLiteral, INLINED, and the GUARD-tag fix in readCaseDefGuardAndBody.
  */
class TreeDecodeFidelityTest extends Test:

    import AllowUnsafe.embrace.danger

    // F-B-001 / INV-011 leaf 1 (Phase 05): no-unknown-tree-nodes
    // Given: the real classpath loaded via TestClasspaths.withClasspath
    // When: scanning cp.allMethods.flatMap(_.body.toChunk).flatMap(treeIterator)
    // Then: post-fix zero Tree.Unknown leaves remain;
    //       before fix > 0 Tree.Unknown appeared for RECthis, IMPORTED, RENAMED,
    //       PRIVATEqualified, PROTECTEDqualified, BYNAMEtpt, BOUNDED, EXPLICITtpt,
    //       ELIDED, TYPEREF, SELFDEF, SELECTouter, REPEATED, INLINED, and category-5 tags
    // Pins: INV-011 producer (F-B-001)
    "F-B-001 / INV-011 (Phase 05): zero Tree.Unknown nodes in all method body trees" in pending

    // F-B-002 leaf 2 (Phase 05): new-carries-ctor-args
    // Given: the real classpath loaded via TestClasspaths.withClasspath;
    //        a call site new Foo(a, b) from any stdlib initializer
    // When: decoding the tree
    // Then: post-fix Tree.New args size == 2 (the ctor args are preserved);
    //       before fix the parenthesised argument list was dropped because
    //       TreeUnpickler.scala:265-267 discarded the args before the enclosing APPLY
    // Pins: F-B-002
    "F-B-002 (Phase 05): Tree.New preserves constructor argument list" in pending

    // F-B-003 leaf 3 (Phase 05): select-tpe-not-stub
    // Given: the real classpath loaded via TestClasspaths.withClasspath;
    //        the decoded tree for scala.Predef$.identity
    // When: collecting Select.tpe instances from the tree
    // Then: post-fix no element matches Wildcard(Named(<nothing>), Named(<any>));
    //       before fix every Tree.Select.tpe was that exact stub
    //       (TreeUnpickler.scala:312-325 always set tpe to a dummy Wildcard)
    // Pins: F-B-003
    "F-B-003 (Phase 05): Tree.Select.tpe is not the stub Wildcard(Nothing, Any)" in pending

    // F-B-004 leaf 4 (Phase 05): termref-not-fabricated-select
    // Given: the real classpath loaded via TestClasspaths.withClasspath;
    //        a tree containing a TERMREFin (tag 115)
    // When: pattern matching the decoded node
    // Then: post-fix matches Tree.TermRef (the dedicated new ADT case);
    //       before fix matched Tree.Select wrapping a synthetic Tree.Ident
    //       because the TERMREFin handler fabricated a Select wrapper
    // Pins: F-B-004
    "F-B-004 (Phase 05): TERMREFin decodes to Tree.TermRef, not a fabricated Tree.Select" in pending

    // F-B-005 leaf 5 (Phase 05): repeated-emits-seqliteral
    // Given: the real classpath loaded via TestClasspaths.withClasspath;
    //        a varargs call (e.g. List(1, 2, 3))
    // When: scanning the decoded tree
    // Then: post-fix exists Tree.SeqLiteral and zero Tree.Ident named "_repeated";
    //       before fix the shape was Apply(Ident("_repeated", Named(unresolved)), trees)
    //       because REPEATED was mapped to a synthetic Ident wrapper
    // Pins: F-B-005
    "F-B-005 (Phase 05): REPEATED varargs decodes to Tree.SeqLiteral, not Ident(_repeated)" in pending

    // F-B-007 leaf 6 (Phase 05): inlined-empty-becomes-unit
    // Given: the real classpath loaded via TestClasspaths.withClasspath;
    //        an inline def whose body is the unit literal (inline def x = ())
    // When: decoding the tree
    // Then: post-fix Tree.Inlined(_, _, Tree.Literal(Constant.Unit));
    //       before fix Tree.Unknown(INLINED, 0) because the INLINED handler fell through
    //       when the remaining payload was empty
    // Pins: F-B-007
    "F-B-007 (Phase 05): empty-body INLINED decodes to Tree.Inlined wrapping Tree.Literal(Unit)" in pending

    // F-A-006 leaf 7 (Phase 05): matchcase-firstclass-adt
    // Given: the real classpath loaded via TestClasspaths.withClasspath;
    //        a stdlib match-type alias that uses MATCHCASEtype (e.g. scala.Tuple.Head)
    // When: walking the decoded type
    // Then: post-fix the type tree contains Type.MatchCase and zero
    //       Applied(Named(<matchCaseSentinel>)) sentinel-wrapped shapes;
    //       before fix every MATCHCASEtype was encoded as Applied(...sentinel...) because
    //       Type.MatchCase did not exist as a first-class ADT case
    // Pins: F-A-006
    "F-A-006 (Phase 05): MATCHCASEtype decodes to Type.MatchCase first-class ADT case" in pending

    // F-E-003 leaf 8 (Phase 05): transparent-inline-body-decodes
    // Given: the real classpath loaded via TestClasspaths.withClasspath;
    //        scala.compiletime.summonInline (a transparent inline def)
    // When: decoding its body tree
    // Then: post-fix zero Tree.Unknown nodes;
    //       before fix Tree.Unknown appeared for every transparent-inline-specific tree tag
    // Pins: F-E-003
    "F-E-003 (Phase 05): transparent inline method body contains no Tree.Unknown nodes" in pending

    // F-I-004 leaf 9 (Phase 03): selectin-resolves-owner
    // Given: the real classpath loaded via TestClasspaths.withClasspath;
    //        a method body that uses a SELECTin (path-dependent type access)
    // When: decoding the tree
    // Then: post-fix the decoded Type.Named carries the resolved owner FQN
    //       (non-empty cp.symbol(named.id).canonicalFqn);
    //       before fix the owner was lost in the unknown-tag fallback in TypeUnpickler
    // Pins: F-I-004 (SELECTin handler)
    "F-I-004 (Phase 03): SELECTin decodes to a Type.Named with non-empty resolved owner FQN" in pending

end TreeDecodeFidelityTest
