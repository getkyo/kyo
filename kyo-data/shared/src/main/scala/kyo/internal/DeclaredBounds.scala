package kyo.internal

import scala.quoted.*

/** Reads the bounds a type member declares, the same way in every build.
  *
  * A macro that needs an opaque or abstract type's bounds has two ways to ask for them, and only one of them is stable. `Symbol.tree`
  * returns the retained source declaration when the build sets `-Yretain-trees` and a declaration the compiler fabricates from the symbol
  * info when it does not, and for a type member those two carry different things: the fabricated one carries the bounds, the real one
  * carries the right-hand side as written, which for an opaque type is its alias and never mentions the bounds at all. A macro reading that
  * tree therefore answers differently, or crashes, depending on a flag the library does not control.
  *
  * The bounds live on the symbol, so they are read here through the node's own prefix instead. The prefix is part of the question: a member
  * of a path-dependent type declares different bounds under different prefixes, so the node's own is the only one that describes this type.
  *
  * One case has no prefix to ask, a type local to a term, and there the declaration tree is read. That read is sound for exactly the reason
  * the general one is not: the compiler keeps the trees a term owns whether or not the flag is set, so both modes see the same declaration.
  * The `declaration` comment below carries the detail.
  */
private[kyo] object DeclaredBounds:

    /** The bounds the type member `node` refers to declares, or `None` if `node` does not refer to one. */
    def apply(using q: Quotes)(node: q.reflect.TypeRepr): Option[q.reflect.TypeBounds] =
        import quotes.reflect.*

        val symbol = node.typeSymbol

        def prefixOf(current: TypeRepr): Option[TypeRepr] =
            current match
                case AppliedType(constructor, _) => prefixOf(constructor)
                // A wildcard argument bounded by the member, `? <: X`, carries its symbol on the upper bound.
                case TypeBounds(_, high) => prefixOf(high)
                case ref: TypeRef        => Some(ref.qualifier)
                case _                   => None

        // A type local to a term, a method's type parameter being the case that reaches here, has no prefix
        // to be a member of, so the symbol is all there is to ask and its declaration is read directly. That
        // read is the one that does not depend on the flag: the compiler retains the trees of everything a
        // term owns either way (`Symbols.retainsDefTree` is true whenever the owner is a term), so a real
        // declaration comes back in both modes and spells its bounds as a `TypeBoundsTree`.
        //
        // A fabricated declaration is still matched, for the case this is ever reached by a symbol whose
        // trees the compiler did not keep. That one spells the bounds as a plain `TypeTree` over the symbol
        // info rather than as a `TypeBoundsTree`, so it needs its own case. The same case rejects a retained
        // declaration whose right-hand side is an alias, since an alias is not a `TypeBounds`.
        def declaration: Option[TypeBounds] =
            symbol.tree match
                case TypeDef(_, TypeBoundsTree(low, high)) => Some(TypeBounds(low.tpe, high.tpe))
                case TypeDef(_, tpt: TypeTree) =>
                    tpt.tpe match
                        case bounds: TypeBounds => Some(bounds)
                        case _                  => None
                case _ => None

        def member(prefix: TypeRepr): Option[TypeBounds] =
            prefix.memberType(symbol) match
                case bounds: TypeBounds => Some(bounds)
                case _                  => None

        if !symbol.exists || !symbol.isTypeDef then None
        else
            prefixOf(node) match
                case Some(NoPrefix()) | None => declaration
                case Some(prefix)            => member(prefix)
        end if
    end apply

    /** The upper bound of the type member `node` refers to, as it constrains `node` itself, or `None` when there is none to look through:
      * `node` is not a type member, or its upper bound is `Any` and so says nothing about what it holds.
      *
      * A parameterized member declares its bounds as type lambdas, which describe the constructor rather than this application of it, so
      * they only describe `node` once its own arguments are substituted in.
      */
    def upper(using q: Quotes)(node: q.reflect.TypeRepr): Option[q.reflect.TypeRepr] =
        import quotes.reflect.*
        val args = node.typeArgs
        def instantiate(bound: TypeRepr) =
            bound match
                case lambda: TypeLambda if args.nonEmpty && lambda.paramNames.size == args.size => lambda.appliedTo(args)
                case other                                                                      => other
        apply(node).map(bounds => instantiate(bounds.hi)).filterNot(_ =:= TypeRepr.of[Any])
    end upper

end DeclaredBounds
