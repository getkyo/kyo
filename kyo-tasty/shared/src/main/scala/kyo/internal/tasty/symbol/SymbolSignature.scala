package kyo.internal.tasty.symbol

import kyo.*
import kyo.Tasty
import kyo.Tasty.Name.asString

/** Computes a human-readable signature string for a Symbol.
  *
  * Dispatches on the Symbol subtype and assembles the signature from its parts. Called by `Symbol.signature(using cp)`.
  */
private[kyo] object SymbolSignature:

    def compute(sym: Tasty.Symbol, cp: Tasty.Classpath)(using Frame): String < Sync =
        Sync.Unsafe.defer:
            computeUnsafe(sym, cp)

    private def computeUnsafe(sym: Tasty.Symbol, cp: Tasty.Classpath)(using AllowUnsafe): String =
        given Tasty.Classpath = cp
        sym match
            case m: Tasty.Symbol.Method       => methodSig(m)
            case c: Tasty.Symbol.Class        => classlikeSig("class", c)
            case e: Tasty.Symbol.EnumCase     => classlikeSig("enum case", e)
            case t: Tasty.Symbol.Trait        => classlikeSig("trait", t)
            case o: Tasty.Symbol.Object       => classlikeSig("object", o)
            case v: Tasty.Symbol.Val          => s"val ${v.simpleName}${typeAscription(v.declaredType)}"
            case v: Tasty.Symbol.Var          => s"var ${v.simpleName}${typeAscription(v.declaredType)}"
            case f: Tasty.Symbol.Field        => s"field ${f.simpleName}${typeAscription(f.declaredType)}"
            case t: Tasty.Symbol.TypeAlias    => s"type ${t.simpleName} = ${t.body.map(renderType).getOrElse("<absent>")}"
            case t: Tasty.Symbol.OpaqueType   => s"opaque type ${t.simpleName} = ${t.body.map(renderType).getOrElse("<absent>")}"
            case t: Tasty.Symbol.AbstractType => s"type ${t.simpleName}"
            case t: Tasty.Symbol.TypeParam    => t.simpleName
            case p: Tasty.Symbol.Parameter    => s"${p.simpleName}: ${p.declaredType.map(renderType).getOrElse("<absent>")}"
            case p: Tasty.Symbol.Package      => s"package ${p.simpleName}"
            case _                            => s"<unknown ${sym.simpleName}>"
        end match
    end computeUnsafe

    private def methodSig(m: Tasty.Symbol.Method)(using cp: Tasty.Classpath)(using AllowUnsafe): String =
        val tps =
            if m.typeParamIds.isEmpty then ""
            else m.typeParamIds.flatMap(id => cp.symbol(id).map(_.simpleName).toList).mkString("[", ", ", "]")
        val plists = m.paramListIds.map: pl =>
            pl.flatMap: pid =>
                cp.symbol(pid).map: p =>
                    p match
                        case param: Tasty.Symbol.Parameter =>
                            s"${param.simpleName}: ${param.declaredType.map(renderType).getOrElse("<absent>")}"
                        case other => other.simpleName
                    end match
                .toList
            .mkString("(", ", ", ")")
        .mkString
        val rt = m.declaredType match
            case Maybe.Present(Tasty.Type.Function(_, result)) => s": ${renderType(result)}"
            case Maybe.Present(t)                              => s": ${renderType(t)}"
            case Maybe.Absent                                  => ""
        s"def ${m.simpleName}$tps$plists$rt"
    end methodSig

    private def classlikeSig(kw: String, c: Tasty.Symbol.ClassLike)(using cp: Tasty.Classpath)(using AllowUnsafe): String =
        val tps =
            if c.typeParamIds.isEmpty then ""
            else c.typeParamIds.flatMap(id => cp.symbol(id).map(_.simpleName).toList).mkString("[", ", ", "]")
        val parentSyms = c.parentTypes.flatMap:
            case Tasty.Type.Named(pid) => cp.symbol(pid).toList
            case _                     => Nil
        val parents =
            if parentSyms.isEmpty then ""
            else " extends " + parentSyms.map(_.simpleName).mkString(", ")
        s"$kw ${c.simpleName}$tps$parents"
    end classlikeSig

    private def typeAscription(t: Maybe[Tasty.Type])(using Tasty.Classpath)(using AllowUnsafe): String = t match
        case Maybe.Present(tp) => s": ${renderType(tp)}"
        case Maybe.Absent      => ""

    private def renderType(t: Tasty.Type)(using cp: Tasty.Classpath)(using AllowUnsafe): String =
        // Cover every composite Type ADT case that Tasty.typeShow covers. The shapes mirror
        // Scala source syntax (Function => "A => B", Applied => "F[X, Y]", Tuple => "(A, B)").
        import Tasty.Name.asString
        t match
            case Tasty.Type.Named(id) =>
                cp.symbol(id).map(s => cp.fullNameUnsafe(s).asString).getOrElse("<unresolved>")
            case Tasty.Type.Any     => "Any"
            case Tasty.Type.Nothing => "Nothing"
            case Tasty.Type.Function(params, result) =>
                val ps =
                    if params.length == 1 then renderType(params.head)
                    else params.map(renderType).mkString("(", ", ", ")")
                s"$ps => ${renderType(result)}"
            case Tasty.Type.ContextFunction(params, result) =>
                val ps =
                    if params.length == 1 then renderType(params.head)
                    else params.map(renderType).mkString("(", ", ", ")")
                s"$ps ?=> ${renderType(result)}"
            case Tasty.Type.Applied(base, args) =>
                s"${renderType(base)}[${args.map(renderType).mkString(", ")}]"
            case Tasty.Type.Tuple(elems) =>
                elems.map(renderType).mkString("(", ", ", ")")
            case Tasty.Type.Array(elem) =>
                s"Array[${renderType(elem)}]"
            case Tasty.Type.AndType(l, r)   => s"${renderType(l)} & ${renderType(r)}"
            case Tasty.Type.OrType(l, r)    => s"${renderType(l)} | ${renderType(r)}"
            case Tasty.Type.ByName(body)    => s"=> ${renderType(body)}"
            case Tasty.Type.Repeated(elem)  => s"${renderType(elem)}*"
            case Tasty.Type.ConstantType(c) => c.show
            // Residual ADT cases (Refinement, MatchType, Skolem, etc.) fall through to toString unchanged.
            case _ => t.toString
        end match
    end renderType

end SymbolSignature
