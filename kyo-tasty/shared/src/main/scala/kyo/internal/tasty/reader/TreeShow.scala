package kyo.internal.tasty.reader

import kyo.Tasty
import kyo.Tasty.Name.asString

/** Produces a human-readable string for a Tree node, resolving Symbols and Types via the Classpath. */
private[kyo] object TreeShow:

    private def showType(tpe: Tasty.Type, cp: Tasty.Classpath): String =
        import Tasty.Name.asString
        tpe match
            case Tasty.Type.Named(id)           => cp.symbol(id).name.asString
            case Tasty.Type.Applied(base, args) => s"${showType(base, cp)}[${args.map(showType(_, cp)).mkString(", ")}]"
            case Tasty.Type.Array(elem)         => s"${showType(elem, cp)}[]"
            case Tasty.Type.Function(ps, r, isCtx) =>
                s"(${ps.map(showType(_, cp)).mkString(", ")}) ${if isCtx then "?=>" else "=>"} ${showType(r, cp)}"
            case Tasty.Type.ContextFunction(ps, r) => s"(${ps.map(showType(_, cp)).mkString(", ")}) ?=> ${showType(r, cp)}"
            case Tasty.Type.Tuple(es)              => s"(${es.map(showType(_, cp)).mkString(", ")})"
            case Tasty.Type.Nothing                => "Nothing"
            case Tasty.Type.Any                    => "Any"
            case Tasty.Type.Unknown                => "<unknown>"
            case other                             => other.toString
        end match
    end showType

    def show(tree: Tasty.Tree, cp: Tasty.Classpath): String =
        tree match
            case Tasty.Tree.Ident(name, _)        => name.asString
            case Tasty.Tree.Select(qual, name, _) => s"${show(qual, cp)}.${name.asString}"
            case Tasty.Tree.Apply(fun, args) =>
                s"${show(fun, cp)}(${args.map(show(_, cp)).mkString(", ")})"
            case Tasty.Tree.TypeApply(fun, args) =>
                s"${show(fun, cp)}[${args.map(showType(_, cp)).mkString(", ")}]"
            case Tasty.Tree.Block(stats, expr) =>
                val parts = stats.map(show(_, cp)) :+ show(expr, cp)
                parts.mkString("{ ", "; ", " }")
            case Tasty.Tree.If(cond, thenp, elsep) =>
                s"if ${show(cond, cp)} then ${show(thenp, cp)} else ${show(elsep, cp)}"
            case Tasty.Tree.Match(sel, cases) =>
                s"${show(sel, cp)} match { ${cases.map(show(_, cp)).mkString("; ")} }"
            case Tasty.Tree.CaseDef(pat, guard, body) =>
                val guardStr = guard match
                    case kyo.Maybe.Present(g) => s" if ${show(g, cp)}"
                    case kyo.Maybe.Absent     => ""
                s"case ${show(pat, cp)}$guardStr => ${show(body, cp)}"
            case Tasty.Tree.Literal(c)       => c.show
            case Tasty.Tree.New(tpe)         => s"new ${showType(tpe, cp)}"
            case Tasty.Tree.Assign(lhs, rhs) => s"${show(lhs, cp)} = ${show(rhs, cp)}"
            case Tasty.Tree.Return(expr, _) =>
                expr match
                    case kyo.Maybe.Present(e) => s"return ${show(e, cp)}"
                    case kyo.Maybe.Absent     => "return"
            case Tasty.Tree.Throw(expr)         => s"throw ${show(expr, cp)}"
            case Tasty.Tree.Lambda(method, _)   => s"<lambda:${show(method, cp)}>"
            case Tasty.Tree.Typed(expr, tpe)    => s"(${show(expr, cp)}: ${showType(tpe, cp)})"
            case Tasty.Tree.Inlined(_, _, body) => s"<inlined:${show(body, cp)}>"
            case Tasty.Tree.Try(expr, cases, fin) =>
                val finStr = fin match
                    case kyo.Maybe.Present(f) => s" finally ${show(f, cp)}"
                    case kyo.Maybe.Absent     => ""
                s"try ${show(expr, cp)} catch { ... }$finStr"
            case Tasty.Tree.While(cond, body) => s"while ${show(cond, cp)} do ${show(body, cp)}"
            case Tasty.Tree.Bind(name, pat)   => s"${name.asString} @ ${show(pat, cp)}"
            case Tasty.Tree.Alternative(pats) => pats.map(show(_, cp)).mkString(" | ")
            case Tasty.Tree.Unapply(fun, _, pats) =>
                s"${show(fun, cp)}(${pats.map(show(_, cp)).mkString(", ")})"
            case Tasty.Tree.ValDef(sym, tpt, _) =>
                s"val ${sym.name.asString}: ${showType(tpt, cp)}"
            case Tasty.Tree.DefDef(sym, _, tpt, _) =>
                s"def ${sym.name.asString}: ${showType(tpt, cp)}"
            case Tasty.Tree.TypeDef(sym, _)           => s"type ${sym.name.asString}"
            case Tasty.Tree.PackageDef(sym, _)        => s"package ${sym.name.asString}"
            case Tasty.Tree.ClassDef(sym, _)          => s"class ${sym.name.asString}"
            case Tasty.Tree.Template(_, _, _)         => "<template>"
            case Tasty.Tree.Super(qual, _)            => s"super[${show(qual, cp)}]"
            case Tasty.Tree.This(cls)                 => s"${cls.name.asString}.this"
            case Tasty.Tree.NamedArg(name, value)     => s"${name.asString} = ${show(value, cp)}"
            case Tasty.Tree.Annotated(expr, ann)      => s"${show(expr, cp)}: @${show(ann, cp)}"
            case Tasty.Tree.Shared(addr)              => s"<shared@$addr>"
            case Tasty.Tree.Modifier(flag)            => Tasty.Flag.name(flag)
            case Tasty.Tree.RecType(parent)           => s"<rec:${show(parent, cp)}>"
            case Tasty.Tree.SuperType(a, b)           => s"${show(a, cp)} super ${show(b, cp)}"
            case Tasty.Tree.RefinedType(parent, n, _) => s"${show(parent, cp)} { ${n.asString} }"
            case Tasty.Tree.AppliedType(tycon, args) =>
                s"${show(tycon, cp)}[${args.map(show(_, cp)).mkString(", ")}]"
            case Tasty.Tree.TypeBounds(lo, hi)            => s"${show(lo, cp)} .. ${show(hi, cp)}"
            case Tasty.Tree.AnnotatedType(parent, _)      => show(parent, cp)
            case Tasty.Tree.AndType(left, right)          => s"${show(left, cp)} & ${show(right, cp)}"
            case Tasty.Tree.OrType(left, right)           => s"${show(left, cp)} | ${show(right, cp)}"
            case Tasty.Tree.ByNameType(arg)               => s"=> ${show(arg, cp)}"
            case Tasty.Tree.MatchType(_, scr, _)          => s"${show(scr, cp)} match { ... }"
            case Tasty.Tree.FlexibleType(arg)             => s"${show(arg, cp)}?"
            case Tasty.Tree.IdentTpt(name, _)             => name.asString
            case Tasty.Tree.SelectTpt(qual, name)         => s"${show(qual, cp)}.${name.asString}"
            case Tasty.Tree.SingletonTpt(tpe)             => s"${show(tpe, cp)}.type"
            case Tasty.Tree.TermRefPkg(name)              => name.asString
            case Tasty.Tree.TypeRefPkg(name)              => name.asString
            case Tasty.Tree.TermRefSymbol(addr, _)        => s"<termref@$addr>"
            case Tasty.Tree.TypeRefSymbol(addr, _)        => s"<typeref@$addr>"
            case Tasty.Tree.TermRefDirect(addr)           => s"<termdirect@$addr>"
            case Tasty.Tree.TypeRefDirect(addr)           => s"<typedirect@$addr>"
            case Tasty.Tree.SelectIn(qual, name, _)       => s"${show(qual, cp)}.${name.asString}"
            case Tasty.Tree.Import(qual, _)               => s"import ${show(qual, cp)}.{...}"
            case Tasty.Tree.Export(qual, _)               => s"export ${show(qual, cp)}.{...}"
            case Tasty.Tree.AnnotationNode(ann, _)        => s"@${show(ann, cp)}"
            case Tasty.Tree.TermRef(prefix, name)         => s"${show(prefix, cp)}.${name.asString}"
            case Tasty.Tree.SeqLiteral(elems, _)          => s"[${elems.map(show(_, cp)).mkString(", ")}]"
            case Tasty.Tree.TypeRefTree(qual, name)       => s"${show(qual, cp)}.${name.asString}"
            case Tasty.Tree.SelfDef(name, _)              => s"self $name"
            case Tasty.Tree.SelectOuter(qual, name, _, _) => s"${show(qual, cp)}.${name.asString}"
            case Tasty.Tree.Imported(qual)                => show(qual, cp)
            case Tasty.Tree.Renamed(name)                 => name.asString
            case Tasty.Tree.ByNameTpt(inner)              => s"=> ${showType(inner, cp)}"
            case Tasty.Tree.Bounded(bound)                => show(bound, cp)
            case Tasty.Tree.ExplicitTpt(inner)            => showType(inner, cp)
            case Tasty.Tree.Elided(inner)                 => showType(inner, cp)
            case Tasty.Tree.RecThisAddr(addr)             => s"<recthis@$addr>"
            case Tasty.Tree.Unknown(tag, _)               => s"<unknown:$tag>"
        end match
    end show

end TreeShow
