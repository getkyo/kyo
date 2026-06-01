package kyo

import kyo.internal.TestClasspaths

/** Fidelity tests for Method.declaredType, paramLists, and return type correctness.
  *
  * Pins findings F-A-001..F-A-005, F-A-007, and INV-005. All leaves are PENDING until Phase 03 and Phase 04 un-pend them by fixing
  * `AstUnpickler.readDefDefReturnType` (consume full METHODtype lambda), `TypeUnpickler.decodeMethodType` / `decodePolyType`, THIS-type
  * decode, and RECthis late-resolution.
  */
class MethodSignatureFidelityTest extends Test:

    import AllowUnsafe.embrace.danger

    // F-A-001 / INV-005 leaf 1 (Phase 04): no-unresolved-return-types
    // Given: the real classpath loaded via TestClasspaths.withClasspath
    // When: iterating cp.allMethods.flatMap(_.declaredType.toChunk) and checking each head
    // Then: post-fix every Type's head is not Named(SymbolId(-1)) i.e. !_.show.startsWith("<unresolved>");
    //       before fix scala.Predef$.identity, scala.Option.isEmpty, kyo.Chunk$.apply, etc. all
    //       return Present(Named(SymbolId(-1))) with show == "<unresolved>"
    // Pins: INV-005 producer; F-A-001, F-A-002
    "F-A-001 / INV-005 (Phase 04): no method declaredType resolves to Named(SymbolId(-1))" in pending

    // F-A-003 leaf 2 (Phase 04): identity-has-typelambda
    // Given: the real classpath loaded via TestClasspaths.withClasspath
    // When: inspecting cp.findSymbol("scala.Predef$").get.findMember("identity").get.asInstanceOf[Symbol.Method].declaredType
    // Then: post-fix the type is Type.TypeLambda whose tparam name is "A" and whose inner is a
    //       Type.MethodType with one param of type A and result A;
    //       before fix it was Present(Named(SymbolId(-1)))
    // Pins: F-A-003
    "F-A-003 (Phase 04): Predef.identity.declaredType is Type.TypeLambda(A => (x: A) => A)" in pending

    // F-A-004 leaf 3 (Phase 04): function-types-materialize
    // Given: the real classpath loaded via TestClasspaths.withClasspath
    // When: iterating cp.allMethods.flatMap(_.declaredType.toChunk) and collecting Type.Function instances
    // Then: post-fix the size is >= 100 (real stdlib has >= 100 Function1-typed members);
    //       before fix size was 0 because every TYPEREF resolved to the unresolved sentinel
    //       and APPLIEDtpt routed to the unknown-tag fallback instead of the type decoder
    // Pins: F-A-004
    "F-A-004 (Phase 04): at least 100 Type.Function instances appear across all method signatures" in pending

    // F-A-005 leaf 4 (Phase 04): this-type-resolves
    // Given: the real classpath loaded via TestClasspaths.withClasspath;
    //        a class with an inner this.foo reference (kyo.Chunk uses this pattern)
    // When: walking the body's type positions and collecting Type.ThisType instances
    // Then: post-fix every ThisType.id resolves via cp.symbol(id) to a Class/Trait/Object symbol
    //       whose name matches the enclosing class;
    //       before fix at least one ThisType referenced a sentinel named "this-unknown"
    // Pins: F-A-005
    "F-A-005 (Phase 04): every Type.ThisType resolves to a real Class/Trait/Object symbol" in pending

    // F-A-007 leaf 5 (Phase 04): rec-no-placeholder-names
    // Given: the real classpath loaded via TestClasspaths.withClasspath
    //        (scala.collection.IterableOps uses recursive types)
    // When: scanning cp.symbols.map(_.name.asString)
    // Then: post-fix zero names start with "rec@";
    //       before fix multiple "rec@<addr>" synthetic symbols appeared because
    //       TypeUnpickler.RECthis produced a fresh makeUnresolvedSym per address
    // Pins: F-A-007
    "F-A-007 (Phase 04): no symbols with name starting rec@ in cp.symbols" in pending

    // INV-012 leaf 6 (Phase 04): sentinel-count-decreased
    // Given: the real classpath loaded via TestClasspaths.withClasspath
    // When: computing cp.symbols.filter(_.id.value == -1).map(_.name.asString).toSet.size
    // Then: post-fix the set size is strictly less than at the Phase 01 commit;
    //       Phase 11 brings the count to <= 3; before fix up to 11 distinct fabricated names
    // Pins: INV-012 partial (F-G-007 partial)
    "INV-012 partial (Phase 04): SymbolId(-1) sentinel count decreased from Phase 01 baseline" in pending

    // F-A-001 leaf 7 (Phase 04): method-type-preserves-param-names
    // Given: the real classpath loaded via TestClasspaths.withClasspath; scala.Option.fold
    // When: inspecting .declaredType
    // Then: post-fix the type is a Type.TypeLambda wrapping a Type.MethodType whose paramNames chunk
    //       equals Chunk(Name("ifEmpty"), Name("f"));
    //       before fix .declaredType was the placeholder Named(SymbolId(-1))
    // Pins: F-A-001 (preservation of param names through the lambda walk)
    "F-A-001 (Phase 04): scala.Option.fold.declaredType preserves param names (ifEmpty, f)" in pending

    // Regression PIN leaf 8 (Phase 04): nullary-method-still-decodes
    // Given: the real classpath loaded via TestClasspaths.withClasspath;
    //        scala.Option.isDefined (nullary method, no value params)
    // When: inspecting .declaredType
    // Then: post-fix the type is Type.Named for scala.Boolean;
    //       the nullary code path must survive the Phase 04 readDefDefReturnType refactor
    //       without regression; before fix the prior path DID return the correct leaf type
    // Pins: regression PIN around the nullary path in the new readDefDefReturnType
    "regression PIN (Phase 04): scala.Option.isDefined.declaredType returns Type.Named(scala.Boolean)" in pending

    // F-A-001 / INV-005 leaf (Phase 03 partial): applied-tpt-yields-applied-type
    // Given: the real classpath loaded via TestClasspaths.withClasspath;
    //        a method whose return type uses an APPLIEDtpt at wire level (e.g. List[Int])
    // When: decoding the return type
    // Then: post-fix the decoded Type is Type.Applied(Type.Named(<List>), Chunk(Type.Named(<Int>)));
    //       before fix the decoded Type is the placeholder Named(SymbolId(-1)) because
    //       APPLIEDtpt (tag 162) short-circuited into the unknown-tag fallback in TypeUnpickler
    // Pins: F-I-004 plus F-A-001 partial
    "F-I-004 / F-A-001 (Phase 03): APPLIEDtpt-encoded return type decodes to Type.Applied" in run {
        val cp = TestClasspaths.withClasspath()
        cp.map: classpath =>
            // Collect all declared types from the loaded classpath.
            val allDeclaredTypes = classpath.symbols.flatMap: sym =>
                sym match
                    case s: Tasty.Symbol.Method => s.declaredType.toList
                    case s: Tasty.Symbol.Val    => s.declaredType.toList
                    case s: Tasty.Symbol.Var    => s.declaredType.toList
                    case s: Tasty.Symbol.Field  => s.declaredType.toList
                    case _                      => Nil
            // Post-fix: APPLIEDtpt tags now decode to Type.Applied, so there should be
            // many Type.Applied instances across method return types and field types.
            val appliedTypes = allDeclaredTypes.collect:
                case t: Tasty.Type.Applied => t
            assert(
                appliedTypes.size > 0,
                s"Expected Type.Applied instances from APPLIEDtpt decoding, but found 0. " +
                    s"This means APPLIEDtpt still routes to the unknown-tag fallback."
            )
            // Verify at least one has a non-sentinel base type.
            val validApplied = appliedTypes.filter:
                case Tasty.Type.Applied(Tasty.Type.Named(id), _) => id.value != -1
                case _                                           => true
            assert(
                validApplied.size > 0,
                "Every Type.Applied still has a Named(-1) base; APPLIEDtpt tycon decode failed"
            )
            succeed
    }

end MethodSignatureFidelityTest
