package kyo.internal.tasty.symbol

import kyo.*
import kyo.Tasty
import kyo.Tasty.Name.asString

/** Computes a human-readable signature string for a Symbol.
  *
  * Dispatches on the Symbol subtype and assembles the signature from its parts. Called by `Symbol.signature(using cp)`.
  */
private[kyo] object SymbolSignature:

    def compute(sym: Tasty.Symbol, cp: Tasty.Classpath): String =
        given Tasty.Classpath = cp
        sym match
            case m: Tasty.Symbol.Method       => methodSig(m)
            case c: Tasty.Symbol.Class        => classlikeSig("class", c)
            case t: Tasty.Symbol.Trait        => classlikeSig("trait", t)
            case o: Tasty.Symbol.Object       => classlikeSig("object", o)
            case v: Tasty.Symbol.Val          => s"val ${v.simpleName}${typeAscription(v.declaredType)}"
            case v: Tasty.Symbol.Var          => s"var ${v.simpleName}${typeAscription(v.declaredType)}"
            case f: Tasty.Symbol.Field        => s"field ${f.simpleName}${typeAscription(f.declaredType)}"
            case t: Tasty.Symbol.TypeAlias    => s"type ${t.simpleName} = ${renderType(t.body)}"
            case t: Tasty.Symbol.OpaqueType   => s"opaque type ${t.simpleName} = ${renderType(t.body)}"
            case t: Tasty.Symbol.AbstractType => s"type ${t.simpleName}"
            case t: Tasty.Symbol.TypeParam    => t.simpleName
            case p: Tasty.Symbol.Parameter    => s"${p.simpleName}: ${renderType(p.declaredType)}"
            case p: Tasty.Symbol.Package      => s"package ${p.simpleName}"
            case u: Tasty.Symbol.Unresolved   => s"<unresolved ${u.simpleName}>"
        end match
    end compute

    private def methodSig(m: Tasty.Symbol.Method)(using Tasty.Classpath): String =
        val tps =
            if m.typeParams.isEmpty then ""
            else m.typeParams.map(_.simpleName).mkString("[", ", ", "]")
        val plists = m.paramLists.map: pl =>
            pl.map(p => s"${p.simpleName}: ${renderType(p.declaredType)}").mkString("(", ", ", ")")
        .mkString
        val rt = m.returnType match
            case Maybe.Present(t) => s": ${renderType(t)}"
            case Maybe.Absent     => ""
        s"def ${m.simpleName}$tps$plists$rt"
    end methodSig

    private def classlikeSig(kw: String, c: Tasty.Symbol.ClassLike)(using Tasty.Classpath): String =
        val tps =
            if c.typeParams.isEmpty then ""
            else c.typeParams.map(_.simpleName).mkString("[", ", ", "]")
        val parents =
            if c.parents.isEmpty then ""
            else " extends " + c.parents.map(_.simpleName).mkString(", ")
        s"$kw ${c.simpleName}$tps$parents"
    end classlikeSig

    private def typeAscription(t: Maybe[Tasty.Type])(using Tasty.Classpath): String = t match
        case Maybe.Present(tp) => s": ${renderType(tp)}"
        case Maybe.Absent      => ""

    private def renderType(t: Tasty.Type)(using cp: Tasty.Classpath): String =
        t match
            case Tasty.Type.Named(id) => cp.symbol(id).fullNameString
            case _                    => t.toString

end SymbolSignature
