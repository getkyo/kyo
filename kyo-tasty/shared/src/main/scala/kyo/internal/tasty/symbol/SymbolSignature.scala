package kyo.internal.tasty.symbol

import kyo.*
import kyo.Tasty
import kyo.Tasty.Name.asString

/** Computes a human-readable signature string for a Symbol.
  *
  * Dispatches on the Symbol subtype and assembles the signature from its parts. Called by `Classpath.signature` and
  * by companion shortcuts that need the code-form representation of a symbol.
  *
  * All methods in this object are pure: no effects, no AllowUnsafe requirement. The data needed for signature
  * computation (owner chain, full names) is accessed via the pure `Classpath` API.
  */
private[kyo] object SymbolSignature:

    /** Compute the signature of `symbol` as a Scala-syntax string, wrapped in Sync for backward compatibility.
      *
      * Delegates to `computePure`. Callers that already hold a `Classpath` and do not need the effect
      * wrapping can call `computePure` directly.
      */
    def compute(symbol: Tasty.Symbol, classpath: Tasty.Classpath)(using Frame): String < Sync =
        Sync.defer {
            computePure(symbol, classpath)
        }

    /** Pure form of signature computation. No effects, no AllowUnsafe requirement.
      *
      * All type resolution delegates to `classpath.computeFullName`, which is itself pure (no AllowUnsafe).
      */
    def computePure(symbol: Tasty.Symbol, classpath: Tasty.Classpath): String =
        given Tasty.Classpath = classpath
        symbol match
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
        end match
    end computePure

    private def methodSig(m: Tasty.Symbol.Method)(using classpath: Tasty.Classpath): String =
        val tps =
            if m.typeParamIds.isEmpty then ""
            else m.typeParamIds.flatMap(id => classpath.symbol(id).map(_.simpleName).toList).mkString("[", ", ", "]")
        val plists = m.paramListIds.map { pl =>
            pl.flatMap { pid =>
                classpath.symbol(pid).map { p =>
                    p match
                        case param: Tasty.Symbol.Parameter =>
                            s"${param.simpleName}: ${param.declaredType.map(renderType).getOrElse("<absent>")}"
                        case other => other.simpleName
                    end match
                }.toList
            }.mkString("(", ", ", ")")
        }.mkString
        val rt = m.declaredType match
            case Maybe.Present(Tasty.Type.Function(_, result)) => s": ${renderType(result)}"
            case Maybe.Present(t)                              => s": ${renderType(t)}"
            case Maybe.Absent                                  => ""
        s"def ${m.simpleName}$tps$plists$rt"
    end methodSig

    private def classlikeSig(kw: String, c: Tasty.Symbol.ClassLike)(using classpath: Tasty.Classpath): String =
        val tps =
            if c.typeParamIds.isEmpty then ""
            else c.typeParamIds.flatMap(id => classpath.symbol(id).map(_.simpleName).toList).mkString("[", ", ", "]")
        val parentSyms = c.parentTypes.flatMap {
            case Tasty.Type.Named(pid) => classpath.symbol(pid).toList
            case _: Tasty.Type.TermRef | _: Tasty.Type.Applied | _: Tasty.Type.TypeLambda |
                _: Tasty.Type.Function | _: Tasty.Type.ContextFunction | _: Tasty.Type.Tuple |
                _: Tasty.Type.ByName | _: Tasty.Type.Repeated | _: Tasty.Type.Array |
                _: Tasty.Type.Refinement | _: Tasty.Type.Rec | _: Tasty.Type.RecThis |
                _: Tasty.Type.AndType | _: Tasty.Type.OrType | _: Tasty.Type.Annotated |
                _: Tasty.Type.ConstantType | _: Tasty.Type.ThisType | _: Tasty.Type.SuperType |
                _: Tasty.Type.ParamRef | _: Tasty.Type.Wildcard | _: Tasty.Type.Skolem |
                _: Tasty.Type.MatchType | _: Tasty.Type.FlexibleType | _: Tasty.Type.MatchCase |
                _: Tasty.Type.TypeRef | _: Tasty.Type.Bounds | Tasty.Type.Nothing | Tasty.Type.Any =>
                Nil
        }
        val parents =
            if parentSyms.isEmpty then ""
            else " extends " + parentSyms.map(_.simpleName).mkString(", ")
        s"$kw ${c.simpleName}$tps$parents"
    end classlikeSig

    private def typeAscription(t: Maybe[Tasty.Type])(using Tasty.Classpath): String = t match
        case Maybe.Present(tp) => s": ${renderType(tp)}"
        case Maybe.Absent      => ""

    private def renderType(t: Tasty.Type)(using classpath: Tasty.Classpath): String =
        classpath.typeShow(t)
    end renderType

end SymbolSignature
