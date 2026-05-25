package kyo.internal

/** Macro helper for [[MacroUtilsDriftTest]]. Lives in a separate file because Scala 3 inline-macro callers must reside
  * in a different compilation unit from the macro implementation.
  *
  * Written fresh for Phase 3 rather than reusing `SerializationMacroDriftMacro`: the latter peels parameterised givens
  * into substituted concrete types and runs them through `isSerializableType` (a black-box behavioural check). This
  * macro instead inspects the bare type constructor of each parameterised given and compares it against
  * `MacroUtils`'s symbol sets directly, which is the white-box property Phase 3 needs to lock.
  *
  * [[containerGivensNotInMacroUtils]] enumerates every parameterised `given Schema[F[_]]` / `given Schema[F[_, _]]` in
  * the `kyo.Schema` companion whose tycon `F` is NOT one of the intentionally-non-MacroUtils tycons (Tuple2..Tuple5
  * and `Tag`), then asserts `F.typeSymbol` is contained in
  * `MacroUtils.collectionSymbols ++ MacroUtils.optionalSymbols ++ MacroUtils.mapSymbols`. Monomorphic givens (e.g.
  * `Schema[String]`, `Schema[Span[Byte]]`) are skipped because their targets are primitive categories, not container
  * categories. Tuple/Tag exclusion is explicit and named in the code so the next reviewer sees the carve-out.
  *
  * [[containerSymbolsConsistent]] behaviourally verifies the Phase 3 consolidation: for every tycon `F` in
  * `MacroUtils.collectionSymbols ++ MacroUtils.optionalSymbols`, the constructed `F[String]` must pass
  * [[SerializationMacro.isSerializableType]]. This proves the gate's local `containerSymbols` set is sourced from
  * MacroUtils — if the gate were to drift back to a literal set and drop, say, `kyo.Span`, then
  * `isSerializableType[Span[String]]` would fail and so would this test.
  */
object MacroUtilsDriftMacro:
    import scala.quoted.*

    inline def containerGivensNotInMacroUtils: Seq[String] = ${ containerImpl }
    inline def containerSymbolsConsistent: Boolean         = ${ consistentImpl }

    private def containerImpl(using Quotes): Expr[Seq[String]] =
        import quotes.reflect.*

        val schemaSym    = TypeRepr.of[kyo.Schema.type].typeSymbol
        val givenMembers = schemaSym.declaredMethods.filter(_.flags.is(Flags.Given))

        val collectionSet = MacroUtils.collectionSymbols
        val optionalSet   = MacroUtils.optionalSymbols
        val mapSet        = MacroUtils.mapSymbols
        val knownSet      = collectionSet ++ optionalSet ++ mapSet

        // Tycons intentionally NOT in MacroUtils:
        //   - tuples live in SerializationMacro.tupleSymbols (arity-aware, distinct concept);
        //   - Tag is special-cased in the gate by symbol equality (not a container shape);
        //   - Either is special-cased in the gate as a two-arg sum and intentionally kept out of MacroUtils, which
        //     models container/optional/map kinds, not sum kinds. The Phase 3 spec adds Result to collectionSymbols
        //     but not Either; Either's container-shaped givens stay outside MacroUtils by design.
        val excludedTycons: Set[Symbol] = Set(
            TypeRepr.of[Tuple1].typeSymbol,
            TypeRepr.of[Tuple2].typeSymbol,
            TypeRepr.of[Tuple3].typeSymbol,
            TypeRepr.of[Tuple4].typeSymbol,
            TypeRepr.of[Tuple5].typeSymbol,
            TypeRepr.of[Tuple6[?, ?, ?, ?, ?, ?]].typeSymbol,
            TypeRepr.of[Tuple7[?, ?, ?, ?, ?, ?, ?]].typeSymbol,
            TypeRepr.of[Tuple8[?, ?, ?, ?, ?, ?, ?, ?]].typeSymbol,
            TypeRepr.of[Tuple9[?, ?, ?, ?, ?, ?, ?, ?, ?]].typeSymbol,
            TypeRepr.of[Tuple10[?, ?, ?, ?, ?, ?, ?, ?, ?, ?]].typeSymbol,
            TypeRepr.of[Tuple11[?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?]].typeSymbol,
            TypeRepr.of[Tuple12[?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?]].typeSymbol,
            TypeRepr.of[Tuple13[?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?]].typeSymbol,
            TypeRepr.of[Tuple14[?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?]].typeSymbol,
            TypeRepr.of[Tuple15[?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?]].typeSymbol,
            TypeRepr.of[Tuple16[?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?]].typeSymbol,
            TypeRepr.of[Tuple17[?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?]].typeSymbol,
            TypeRepr.of[Tuple18[?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?]].typeSymbol,
            TypeRepr.of[Tuple19[?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?]].typeSymbol,
            TypeRepr.of[Tuple20[?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?]].typeSymbol,
            TypeRepr.of[Tuple21[?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?]].typeSymbol,
            TypeRepr.of[Tuple22[?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?]].typeSymbol,
            TypeRepr.of[kyo.Tag].typeSymbol,
            TypeRepr.of[Either].typeSymbol
        )

        // Peel Schema[T] from a return type, accepting refinements like `Schema[T] { type Focused = ... }`.
        def peel(ret: TypeRepr): Option[TypeRepr] =
            ret.dealias match
                case AppliedType(_, List(target)) => Some(target)
                case Refinement(parent, _, _)     => peel(parent)
                case _                            => None

        // True if the AppliedType has at least one free type-parameter argument (i.e. is a "parameterised" container
        // shape, not a monomorphic instance like Span[Byte]).
        def hasFreeTypeParamArg(args: List[TypeRepr]): Boolean =
            args.exists {
                case t @ TypeRef(_, _) => t.typeSymbol.isTypeParam
                case _                 => false
            }

        val unmatched: List[String] = givenMembers.flatMap { m =>
            m.tree match
                case d: DefDef =>
                    peel(d.returnTpt.tpe).flatMap {
                        case AppliedType(tycon, args) if hasFreeTypeParamArg(args) =>
                            val tyconSym = tycon.typeSymbol
                            if excludedTycons.contains(tyconSym) then None
                            else if knownSet.contains(tyconSym) then None
                            else Some(s"${m.name}: tycon ${tycon.show} not in MacroUtils sets")
                        case _ => None
                    }
                case _ => None
        }
        Expr.ofSeq(unmatched.map(Expr(_)))
    end containerImpl

    private def consistentImpl(using Quotes): Expr[Boolean] =
        import quotes.reflect.*
        given CanEqual[Symbol, Symbol] = CanEqual.derived

        // Behaviourally verify the Phase 3 consolidation: each tycon in `MacroUtils.collectionSymbols ++ optionalSymbols`
        // must be the tycon of a parameterised given on the `Schema` companion, AND `isSerializableType` must accept
        // that given's target type (with its free type parameters substituted by String). This proves the gate's local
        // `containerSymbols` set accepts every MacroUtils entry. Constructing the AppliedType from the given's actual
        // return-type tycon (rather than synthesising one from `sym.typeRef`) is necessary because some entries are
        // opaque types (e.g. `kyo.Span`) whose synthetic re-construction does not match the gate's tycon comparison.
        val stringTpe = TypeRepr.of[String]
        val expected  = MacroUtils.collectionSymbols ++ MacroUtils.optionalSymbols
        val resultSym = TypeRepr.of[kyo.Result].typeSymbol

        val schemaSym    = TypeRepr.of[kyo.Schema.type].typeSymbol
        val givenMembers = schemaSym.declaredMethods.filter(_.flags.is(Flags.Given))

        def peel(ret: TypeRepr): Option[TypeRepr] =
            ret.dealias match
                case AppliedType(_, List(target)) => Some(target)
                case Refinement(parent, _, _)     => peel(parent)
                case _                            => None

        def substitute(tpe: TypeRepr): TypeRepr =
            tpe match
                case t @ TypeRef(_, _) if t.typeSymbol.isTypeParam => stringTpe
                case AppliedType(tc, args)                         => AppliedType(tc, args.map(substitute))
                case other                                         => other

        // Collect every parameterised given's target type, keyed by tycon symbol.
        val givenTargets: Map[Symbol, TypeRepr] = givenMembers.flatMap { m =>
            m.tree match
                case d: DefDef =>
                    peel(d.returnTpt.tpe).flatMap {
                        case at @ AppliedType(tc, _) => Some(tc.typeSymbol -> substitute(at))
                        case _                       => None
                    }
                case _ => None
        }.toMap

        val ok = expected.forall { sym =>
            givenTargets.get(sym) match
                case Some(target) =>
                    val accepted = SerializationMacro.isSerializableType(target)
                    if !accepted then
                        report.warning(s"isSerializableType rejected ${target.show} (tycon=${sym.name})")
                    accepted
                case None =>
                    report.warning(s"No parameterised Schema given found for MacroUtils tycon ${sym.name}")
                    false
            end match
        }
        Expr(ok)
    end consistentImpl
end MacroUtilsDriftMacro
