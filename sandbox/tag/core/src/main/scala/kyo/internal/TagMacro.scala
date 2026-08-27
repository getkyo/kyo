package kyo.internal

import kyo.*
import kyo.Tag.*
import kyo.Tag.internal.*
import kyo.Tag.internal.Type.*
import kyo.Tag.internal.Type.Entry.*
import scala.annotation.tailrec
import scala.collection.immutable
import scala.collection.immutable.HashMap
import scala.quoted.{Type as SType, *}

private[kyo] object TagMacro:
    // Per-compilation-run memo of the derived static encoding. The cache key is the
    // dealiased type's normalized `show` string concatenated with the source-position
    // offsets of its class symbols (`symPositions`): the `show` alone is not sufficient
    // because two distinct same-name local types (e.g. two `class Test` definitions in
    // different test blocks) can produce identical `show` strings; appending each class
    // symbol's definition-site position makes the key unique across those cases. Two
    // types that are genuinely identical share every symbol and every position, so their
    // keys match; two that share only source names but differ in their definitions carry
    // different positions, so their keys diverge. deriveDB is a pure function of the
    // TypeRepr, so the same type always derives the same encoded string: caching the
    // encoded form across identical types within one run cannot change the emitted
    // constant. Only the STATIC (dynamicDB-empty) case is memoized, the case that emits
    // a pure string literal; the Dynamic-fallback case carries per-expansion Expr trees
    // that are not reusable across call sites; on a miss the encoding is derived and
    // stored. The macro object is a per-run singleton (the same lifecycle Frame's
    // per-file memo relies on), so a key collision across runs is impossible and a miss
    // simply derives the encoding.
    @volatile private var encodedCache: Map[String, String] = Map.empty

    def deriveImpl[A: SType](allowDynamic: Boolean)(using Quotes): Expr[String | Tag.internal.Dynamic] =
        import quotes.reflect.*
        // Collect source-position offsets of every class symbol in the type tree,
        // recursively walking into applied type arguments and the components of
        // intersection/union types. The show string alone is not sufficient to
        // distinguish locally-defined classes with the same source name (e.g.
        // multiple `class Test` definitions with different variances, or matching
        // trait names in different test blocks). Appending the position of each
        // class symbol's definition makes the key structurally unique: two types
        // that are genuinely the same share every symbol, so their keys match;
        // two types that share source names but differ in their definitions carry
        // different definition positions, so their keys diverge. Only locally defined
        // symbols need the position: everything else is already disambiguated by the
        // fully qualified path `show` prints. A symbol read from a class file carries no
        // source position, and asking one for its position makes the compiler answer with
        // a defaulted offset 0 and report a spurious "Missing symbol position ... This is
        // a compiler bug" warning under -Xcheck-macros, so the position is consulted only
        // for term-owned symbols, the locally defined ones, which are always compiled from
        // source in the current run.
        @tailrec def isLocallyDefined(sym: Symbol): Boolean =
            val owner = sym.maybeOwner
            if owner.isNoSymbol then false
            else if owner.isClassDef || owner.isPackageDef then isLocallyDefined(owner)
            else true
        end isLocallyDefined
        def symPositions(t: TypeRepr): String =
            val sym = t.typeSymbol
            val symPart =
                if sym.isNoSymbol || !isLocallyDefined(sym) then ""
                else sym.pos.map(p => "@" + p.start).getOrElse("")
            val children = t match
                case AndType(a, b) => List(a, b)
                case OrType(a, b)  => List(a, b)
                case _             => t.typeArgs
            children.map(symPositions).mkString("") + symPart
        end symPositions
        val normTpe = TypeRepr.of[A].dealiasKeepOpaques.simplified.dealiasKeepOpaques
        val typeKey = normTpe.show + symPositions(normTpe)
        // The key describes the type, not the point the macro runs at, and inside an opaque type's
        // own scope the same type derives a different tag. Reading a cached encoding there would
        // skip the scope check, and writing one would hand a rewritten encoding to call sites that
        // meant the underlying type. Such scopes are rare, so deriving fresh in them costs nothing.
        val opaqueScope = transparentOpaques
        if opaqueScope.isEmpty then
            encodedCache.get(typeKey) match
                case Some(hit) =>
                    return Expr(hit)
                case None => ()
            end match
        end if
        val (staticDB, dynamicDB) = deriveDB[A](resolveSurface(TypeRepr.of[A], opaqueScope))
        val encodedStr            = Tag.internal.encode(staticDB)
        val encoded               = Expr(encodedStr)
        if dynamicDB.isEmpty then
            if opaqueScope.isEmpty then encodedCache = encodedCache.updated(typeKey, encodedStr)
            encoded
        else if !allowDynamic && FindEnclosing.isInternal then
            val missing =
                dynamicDB.map {
                    case (_, (tpe, _)) =>
                        tpe.show
                }
            report.errorAndAbort(
                s"Dynamic tags aren't allowed in the kyo package for performance reasons. Please modify the method to take an implicit 'Tag[${TypeRepr.of[A].show}]'. Dynamic types: ${missing.mkString(", ")}."
            )
        else
            val reifiedDB =
                dynamicDB.foldLeft('{ Map.empty[Entry.Id, Tag[Any]] }) {
                    case (map, (id, (_, tag))) =>
                        '{ $map.updated(${ Expr(id) }, $tag) }
                }
            '{ Tag.internal.Dynamic($encoded, $reifiedDB) }
        end if
    end deriveImpl

    /** Opaque types whose alias the compiler substitutes at the expansion point.
      *
      * An opaque type is transparent in the template that declares it and in its companion object,
      * and opaque everywhere else. Only the owner chain of the splice separates the two: comparing
      * or dealiasing types in a macro sees through opacity wherever it is asked, so those report
      * what the underlying type is, never whether this site is allowed to know. An enclosing
      * template contributes all of its opaque members; a package contributes only the ones named
      * after an enclosing object, which is the companion case.
      */
    private def transparentOpaques(using Quotes): List[(quotes.reflect.Symbol, quotes.reflect.TypeRepr)] =
        import quotes.reflect.*
        val chain = Iterator.iterate(Symbol.spliceOwner)(_.owner).takeWhile(sym => !sym.isNoSymbol).toList
        // Only a template or a package can declare an opaque type, and asking anything else for its
        // members is not merely wasted work: the splice owner is often a definition whose own type
        // is still being inferred, and listing its members forces it and fails the compilation with
        // a cyclic reference.
        val declaring      = chain.filter(owner => owner.isClassDef || owner.isPackageDef)
        val enclosingNames = chain.map(_.name).toSet
        declaring.flatMap { owner =>
            val opaques = owner.declaredTypes.filter(_.flags.is(Flags.Opaque))
            val visible = if owner.isPackageDef then opaques.filter(sym => enclosingNames.contains(sym.name)) else opaques
            visible.map(sym => sym -> sym.typeRef.dealias)
        }.distinctBy(_._1)
    end transparentOpaques

    /** Matches a type against an opaque type's underlying, treating the underlying's own type
      * parameters as holes, and returns what each was bound to.
      *
      * Inside the scope the compiler substitutes the underlying for the opaque type, so a
      * `Maybe[Int]` written there reaches the macro as `Absent | Present[Int]`. Recognizing it
      * again means matching that against `Absent | Present[A]` and reading back `A = Int`. Unions
      * and intersections carry no order, so their members are paired by search rather than by
      * position.
      */
    private def bindUnderlying(using
        Quotes
    )(
        underlying: quotes.reflect.TypeRepr,
        node: quotes.reflect.TypeRepr
    ): Option[List[quotes.reflect.TypeRepr]] =
        import quotes.reflect.*
        underlying match
            // The constructor itself, used unapplied. An opaque type in a higher-kinded position
            // collapses to its underlying constructor rather than to an applied type, so there are
            // no arguments at this node to read back.
            case lambda: TypeLambda if lambda =:= node => Some(Nil)

            case lambda: TypeLambda =>
                val holes = Array.fill[Option[TypeRepr]](lambda.paramNames.size)(None)

                def holeIndex(pattern: TypeRepr): Option[Int] =
                    pattern match
                        case ref: ParamRef if ref.binder.equals(lambda) => Some(ref.paramNum)
                        case _                                          => None

                def unify(pattern: TypeRepr, value: TypeRepr): Boolean =
                    holeIndex(pattern) match
                        case Some(i) =>
                            holes(i) match
                                case None =>
                                    holes(i) = Some(value)
                                    true
                                case Some(bound) => bound =:= value
                        case None =>
                            (pattern, value) match
                                case (AppliedType(patternCon, patternArgs), AppliedType(valueCon, valueArgs))
                                    if patternArgs.size == valueArgs.size =>
                                    patternCon =:= valueCon && patternArgs.lazyZip(valueArgs).forall(unify)
                                case (AndType(_, _), AndType(_, _)) =>
                                    unifyMembers(flattenAnd(pattern).toList, flattenAnd(value).toList)
                                case (OrType(_, _), OrType(_, _)) =>
                                    unifyMembers(flattenOr(pattern).toList, flattenOr(value).toList)
                                case _ => pattern =:= value

                def unifyMembers(patterns: List[TypeRepr], values: List[TypeRepr]): Boolean =
                    patterns.size == values.size && {
                        def search(remaining: List[TypeRepr], available: List[TypeRepr]): Boolean =
                            remaining match
                                case Nil => available.isEmpty
                                case pattern :: rest =>
                                    available.indices.exists { i =>
                                        val snapshot = holes.clone()
                                        if unify(pattern, available(i)) && search(rest, available.patch(i, Nil, 1)) then true
                                        else
                                            Array.copy(snapshot, 0, holes, 0, holes.length)
                                            false
                                        end if
                                    }
                        search(patterns, values)
                    }

                Option.when(unify(lambda.resType, node) && holes.forall(_.isDefined))(holes.toList.flatten)
            case simple =>
                Option.when(simple =:= node)(Nil)
        end match
    end bindUnderlying

    /** Rewrites the surface of a type so opaque types the compiler substituted away are named
      * again, or refuses the derivation when nothing says which type was meant.
      *
      * Inside an opaque type's scope the compiler substitutes the underlying type for the opaque one
      * wherever it has to infer, so a type reaching the macro as `Int` may have been written `X`.
      * Nothing in the type says which, and the two encode to different tags, so guessing would
      * silently produce a tag that disagrees with every derivation outside the scope.
      *
      * Only the surface is examined: the root and, recursively, its type arguments, the members of
      * its intersections and unions, and the bounds of a wildcard argument. That is the part the
      * author wrote or inference produced for them. The structure the encoder later walks into, a
      * class's parents and an opaque type's own bounds, belongs to those types rather than to this
      * call site, so an `Int` found there is `Chunk`'s business rather than a substituted `X`.
      *
      * A `Tag` for the opaque type declared in scope settles an ambiguous node: it is the author
      * stating what that type means here. The node is then encoded as the opaque type, which is
      * byte-identical to what every call site outside the scope derives. The declared tag is only
      * consulted, never embedded, so this costs no allocation and stays a compile-time constant.
      */
    private def resolveSurface(using
        Quotes
    )(
        root: quotes.reflect.TypeRepr,
        scope: List[(quotes.reflect.Symbol, quotes.reflect.TypeRepr)]
    ): quotes.reflect.TypeRepr =
        import quotes.reflect.*
        if scope.isEmpty then root
        else
            def candidates(node: TypeRepr): List[(Symbol, TypeRepr)] =
                // A node already spelled as an opaque type says which type it means. Asking whether
                // it equals some underlying would answer yes, since type comparison in a macro sees
                // through opacity, and the derivation of the declared tag itself is such a node.
                if node.typeSymbol.flags.is(Flags.Opaque) then Nil
                else
                    scope.flatMap { (sym, underlying) =>
                        bindUnderlying(underlying, node).map { args =>
                            sym -> (if args.isEmpty then sym.typeRef else sym.typeRef.appliedTo(args))
                        }
                    }

            def hasDeclaredTag(opaqueRef: TypeRepr): Boolean =
                // A type constructor cannot be the argument of Tag, so an unapplied one is probed
                // applied to its parameters' upper bounds. That finds the same declaration an
                // applied occurrence of it would, which is what the author wrote it to say.
                val probe =
                    opaqueRef.dealias match
                        case lambda: TypeLambda if opaqueRef.typeArgs.isEmpty =>
                            opaqueRef.appliedTo(lambda.paramBounds.map {
                                case TypeBounds(_, upper) => upper
                            })
                        case _ => opaqueRef
                probe.asType match
                    case '[t] => Expr.summon[Tag[t]].isDefined
            end hasDeclaredTag

            def rewrite(node: TypeRepr): TypeRepr =
                candidates(node) match
                    case Nil                                                => descend(node)
                    case (_, opaqueRef) :: Nil if hasDeclaredTag(opaqueRef) => opaqueRef
                    case (sym, _) :: Nil =>
                        report.errorAndAbort(
                            s"Cannot derive a Tag for ${root.show}: inside the scope of opaque type ${sym.fullName} the " +
                                s"compiler replaces ${sym.name} with ${node.show} before this macro runs, so nothing here " +
                                s"says whether ${node.show} means ${sym.name} or ${node.show}, and the two need different " +
                                s"tags. Move this derivation out of ${sym.name}'s scope, or, if ${node.show} always means " +
                                s"${sym.name} throughout that scope, declare `given Tag[${sym.name}] = Tag.derive[${sym.name}]` " +
                                s"alongside the opaque type to say so."
                        )
                    case many =>
                        report.errorAndAbort(
                            s"Cannot derive a Tag for ${root.show}: ${node.show} is the underlying type of " +
                                s"${many.map(_._1.fullName).mkString(" and ")}, all of them transparent here, so no " +
                                s"declaration can say which one it means. Move this derivation out of their scope."
                        )

            def descend(node: TypeRepr): TypeRepr =
                node match
                    case AndType(a, b) => AndType(rewrite(a), rewrite(b))
                    case OrType(a, b)  => OrType(rewrite(a), rewrite(b))
                    case AppliedType(tycon, args) if args.nonEmpty =>
                        AppliedType(tycon, args.map(arg => rewrite(arg.dealiasKeepOpaques.simplified)))
                    // A wildcard argument reaches the macro as bare bounds, and the encoder keeps
                    // its upper bound, so a substitution hiding in there reaches the encoding.
                    case TypeBounds(low, high) => TypeBounds(rewrite(low), rewrite(high))
                    case _                     => node

            rewrite(root.dealiasKeepOpaques.simplified)
        end if
    end resolveSurface

    private def deriveDB[A: SType](using
        q: Quotes
    )(root: q.reflect.TypeRepr): (Map[Type.Entry.Id, Type.Entry], Map[Type.Entry.Id, (q.reflect.TypeRepr, Expr[Tag[Any]])]) =
        import quotes.reflect.*
        var nextId  = 0
        var seen    = Map.empty[TypeRepr | Symbol, (TypeRepr, String)]
        var static  = HashMap.empty[Type.Entry.Id, Type.Entry]
        var dynamic = HashMap.empty[Type.Entry.Id, (TypeRepr, Expr[Tag[Any]])]

        def visit(t: TypeRepr): Type.Entry.Id =

            val tpe = t.dealiasKeepOpaques.simplified.dealiasKeepOpaques
            val key =
                tpe.typeSymbol.isNoSymbol match
                    case true => tpe
                    case false =>
                        seen.get(tpe.typeSymbol) match
                            case None                      => tpe.typeSymbol
                            case Some((t, _)) if t =:= tpe => tpe.typeSymbol
                            case _                         => tpe
            if seen.contains(key) then
                seen(key)._2
            else
                val id = nextId.toString
                nextId += 1
                seen += key -> (tpe, id)

                def loop(tpe: TypeRepr): Entry =
                    tpe match
                        case tpe if tpe =:= TypeRepr.of[Any]     => AnyEntry
                        case tpe if tpe =:= TypeRepr.of[Nothing] => NothingEntry
                        case tpe if tpe =:= TypeRepr.of[Null]    => NullEntry

                        case tpe @ AndType(_, _) =>
                            IntersectionEntry(Span.from(flattenAnd(tpe).map(visit)))

                        case tpe @ OrType(_, _) =>
                            UnionEntry(Span.from(flattenOr(tpe).map(visit)))

                        case tpe @ ConstantType(const) =>
                            LiteralEntry(visit(tpe.widen), const.value.toString())

                        case TypeLambda(names, bounds, body) if body.typeSymbol.equals(tpe.typeSymbol) =>
                            loop(body.dealias.simplified)

                        case TypeLambda(names, bounds, body) =>
                            val params = names.map(_.toString)
                            val lowerBounds = bounds.map {
                                case TypeBounds(low, high) => visit(low)
                            }
                            val higherBounds = bounds.map {
                                case TypeBounds(low, high) => visit(high)
                            }
                            LambdaEntry(
                                Span.from(params),
                                Span.from(lowerBounds),
                                Span.from(higherBounds),
                                visit(body)
                            )

                        case tpe if tpe.typeSymbol.isClassDef =>
                            val symbol = tpe.typeSymbol
                            val name   = symbol.fullName
                            val params = tpe.typeArgs.map(visit)
                            val variances =
                                symbol.declaredTypes.flatMap { v =>
                                    if !v.isTypeParam then None
                                    else if v.paramVariance.is(Flags.Contravariant) then Present(Variance.Contravariant)
                                    else if v.paramVariance.is(Flags.Covariant) then Present(Variance.Covariant)
                                    else Present(Variance.Invariant)
                                    end if
                                }
                            require(
                                params.size == variances.size,
                                s"Found ${params.size} type parameters but ${variances.size} variances. TypeRepr: ${tpe.show}"
                            )
                            ClassEntry(
                                name,
                                Span.from(variances),
                                Span.from(params),
                                Span.from(immediateParents(tpe).map(visit))
                            )

                        case applied if applied.typeSymbol.flags.is(Flags.Opaque) && applied.typeSymbol.isTypeDef =>
                            val name = applied.typeSymbol.fullName
                            // The arguments belong to the applied node. The declaration's bounds are the
                            // same tree for every application, so reading anything positional off them
                            // describes the declaration rather than this type.
                            val args = applied.typeArgs
                            applied.typeSymbol.tree.asInstanceOf[TypeDef].rhs.asInstanceOf[TypeTree].tpe match
                                case TypeBounds(lower, upper) =>
                                    // A parameterized opaque type's bounds are type lambdas carrying the
                                    // declared variances. An undeclared lower bound stays a bare Nothing,
                                    // so the upper bound is the one that always has them.
                                    val variances =
                                        upper match
                                            case lambda: TypeLambda if lambda.paramVariances.size == args.size =>
                                                lambda.paramVariances.map { v =>
                                                    if v.is(Flags.Contravariant) then Variance.Contravariant
                                                    else if v.is(Flags.Covariant) then Variance.Covariant
                                                    else Variance.Invariant
                                                }
                                            case _ => args.map(_ => Variance.Invariant)
                                    // The bounds describe the type constructor, so they only describe this
                                    // type once its arguments are substituted in.
                                    def instantiate(bound: TypeRepr) =
                                        bound match
                                            case lambda: TypeLambda if args.nonEmpty && lambda.paramNames.size == args.size =>
                                                lambda.appliedTo(args)
                                            case other => other
                                    require(
                                        args.size == variances.size,
                                        s"Found ${args.size} type parameters but ${variances.size} variances. TypeRepr: ${applied.show}"
                                    )
                                    OpaqueEntry(
                                        name,
                                        visit(instantiate(lower)),
                                        visit(instantiate(upper)),
                                        Span.from(variances),
                                        Span.from(args.map(visit))
                                    )
                            end match

                        // A Java wildcard type argument (e.g. `Comparable<? extends T>`) surfaces
                        // through `typeArgs` as a bare `TypeBounds`. It is not a proper type, so
                        // it must not reach `asType`/`summon`/`<:<` (those crash the compiler).
                        // Encode it as its upper bound: a deterministic, sound representative for
                        // subtyping purposes, consistent with how the macro already collapses
                        // type-lambda bounds (see the `TypeLambda` case above).
                        case TypeBounds(_, high) =>
                            loop(high.dealiasKeepOpaques.simplified.dealiasKeepOpaques)

                        case tpe =>
                            tpe.asType match
                                case '[t] =>
                                    Expr.summon[Tag[t]] match
                                        case Some(tag) =>
                                            dynamic = dynamic.updated(id, tpe -> '{ $tag.asInstanceOf[Tag[Any]] })
                                            null
                                        case None =>
                                            report.errorAndAbort(s"Please provide an implicit kyo.Tag[${tpe.show}] parameter.")

                val entry = loop(tpe)
                if entry != null then
                    static = static.updated(id, loop(tpe))
                id
            end if
        end visit

        discard(visit(root))
        (static, dynamic)
    end deriveDB

    private def immediateParents(using Quotes)(tpe: quotes.reflect.TypeRepr): List[quotes.reflect.TypeRepr] =
        import quotes.reflect.*
        val all = tpe.baseClasses.tail.map(tpe.baseType)
        all.filter { parent =>
            !all.exists { otherAncestor =>
                !otherAncestor.equals(parent) && otherAncestor.baseClasses.contains(parent.typeSymbol)
            }
        }
    end immediateParents

    private def flattenAnd(using q: Quotes)(tpe: q.reflect.TypeRepr): Seq[q.reflect.TypeRepr] =
        import quotes.reflect.*
        def loop(tpe: TypeRepr): Seq[TypeRepr] =
            tpe match
                case AndType(a, b) => loop(a) ++ loop(b)
                case tpe           => Seq(tpe)
        loop(tpe).sortBy(_.show)
    end flattenAnd

    private def flattenOr(using q: Quotes)(tpe: q.reflect.TypeRepr): Seq[q.reflect.TypeRepr] =
        import quotes.reflect.*
        def loop(tpe: TypeRepr): Seq[TypeRepr] =
            tpe match
                case OrType(a, b) => loop(a) ++ loop(b)
                case tpe          => Seq(tpe)
        loop(tpe).sortBy(_.show)
    end flattenOr

end TagMacro
