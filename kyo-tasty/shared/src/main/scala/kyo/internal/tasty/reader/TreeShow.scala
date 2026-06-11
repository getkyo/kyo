package kyo.internal.tasty.reader

import kyo.Tasty
import kyo.Tasty.Name.asString

/** Produces a human-readable string for a Tree node, resolving Symbols and Types via the Classpath. */
private[kyo] object TreeShow:

    private def showType(tpe: Tasty.Type, classpath: Tasty.Classpath): String =
        classpath.typeShow(tpe)
    end showType

    def show(tree: Tasty.Tree, classpath: Tasty.Classpath): String =
        tree match
            case Tasty.Tree.Ident(name, _)        => name.asString
            case Tasty.Tree.Select(qual, name, _) => s"${show(qual, classpath)}.${name.asString}"
            case Tasty.Tree.Apply(fun, args) =>
                s"${show(fun, classpath)}(${args.map(show(_, classpath)).mkString(", ")})"
            case Tasty.Tree.TypeApply(fun, args) =>
                s"${show(fun, classpath)}[${args.map(showType(_, classpath)).mkString(", ")}]"
            case Tasty.Tree.Block(stats, expr) =>
                val parts = stats.map(show(_, classpath)) :+ show(expr, classpath)
                parts.mkString("{ ", "; ", " }")
            case Tasty.Tree.If(cond, thenp, elsep) =>
                s"if ${show(cond, classpath)} then ${show(thenp, classpath)} else ${show(elsep, classpath)}"
            case Tasty.Tree.Match(sel, cases) =>
                s"${show(sel, classpath)} match { ${cases.map(show(_, classpath)).mkString("; ")} }"
            case Tasty.Tree.CaseDef(pat, guard, body) =>
                val guardStr = guard match
                    case kyo.Maybe.Present(g) => s" if ${show(g, classpath)}"
                    case kyo.Maybe.Absent     => ""
                s"case ${show(pat, classpath)}$guardStr => ${show(body, classpath)}"
            case Tasty.Tree.Literal(c)       => c.show
            case Tasty.Tree.New(tpe)         => s"new ${showType(tpe, classpath)}"
            case Tasty.Tree.Assign(lhs, rhs) => s"${show(lhs, classpath)} = ${show(rhs, classpath)}"
            case Tasty.Tree.Return(expr, _) =>
                expr match
                    case kyo.Maybe.Present(e) => s"return ${show(e, classpath)}"
                    case kyo.Maybe.Absent     => "return"
            case Tasty.Tree.Throw(expr)         => s"throw ${show(expr, classpath)}"
            case Tasty.Tree.Lambda(method, _)   => s"<lambda:${show(method, classpath)}>"
            case Tasty.Tree.Typed(expr, tpe)    => s"(${show(expr, classpath)}: ${showType(tpe, classpath)})"
            case Tasty.Tree.Inlined(_, _, body) => s"<inlined:${show(body, classpath)}>"
            case Tasty.Tree.Try(expr, cases, fin) =>
                val finStr = fin match
                    case kyo.Maybe.Present(f) => s" finally ${show(f, classpath)}"
                    case kyo.Maybe.Absent     => ""
                s"try ${show(expr, classpath)} catch { ... }$finStr"
            case Tasty.Tree.While(cond, body) => s"while ${show(cond, classpath)} do ${show(body, classpath)}"
            case Tasty.Tree.Bind(name, pat)   => s"${name.asString} @ ${show(pat, classpath)}"
            case Tasty.Tree.Alternative(pats) => pats.map(show(_, classpath)).mkString(" | ")
            case Tasty.Tree.Unapply(fun, _, pats) =>
                s"${show(fun, classpath)}(${pats.map(show(_, classpath)).mkString(", ")})"
            case Tasty.Tree.ValDef(symbol, tpt, _) =>
                s"val ${symbol.name.asString}: ${showType(tpt, classpath)}"
            case Tasty.Tree.DefDef(symbol, _, tpt, _) =>
                s"def ${symbol.name.asString}: ${showType(tpt, classpath)}"
            case Tasty.Tree.TypeDef(symbol, _)          => s"type ${symbol.name.asString}"
            case Tasty.Tree.PackageDef(symbol, _)       => s"package ${symbol.name.asString}"
            case Tasty.Tree.ClassDef(symbol, _)         => s"class ${symbol.name.asString}"
            case Tasty.Tree.Template(_, _, _)           => "<template>"
            case Tasty.Tree.Super(qual, _)              => s"super[${show(qual, classpath)}]"
            case Tasty.Tree.This(cls)                   => s"${cls.name.asString}.this"
            case Tasty.Tree.NamedArg(name, value)       => s"${name.asString} = ${show(value, classpath)}"
            case Tasty.Tree.Annotated(expr, annotation) => s"${show(expr, classpath)}: @${show(annotation, classpath)}"
            case Tasty.Tree.Shared(address)             => s"<shared@$address>"
            case Tasty.Tree.Modifier(flag)              => flag.toString
            case Tasty.Tree.RecType(parent)             => s"<rec:${show(parent, classpath)}>"
            case Tasty.Tree.SuperType(a, b)             => s"${show(a, classpath)} super ${show(b, classpath)}"
            case Tasty.Tree.RefinedType(parent, n, _)   => s"${show(parent, classpath)} { ${n.asString} }"
            case Tasty.Tree.AppliedType(tycon, args) =>
                s"${show(tycon, classpath)}[${args.map(show(_, classpath)).mkString(", ")}]"
            case Tasty.Tree.TypeBounds(lo, hi)            => s"${show(lo, classpath)} .. ${show(hi, classpath)}"
            case Tasty.Tree.AnnotatedType(parent, _)      => show(parent, classpath)
            case Tasty.Tree.AndType(left, right)          => s"${show(left, classpath)} & ${show(right, classpath)}"
            case Tasty.Tree.OrType(left, right)           => s"${show(left, classpath)} | ${show(right, classpath)}"
            case Tasty.Tree.ByNameType(arg)               => s"=> ${show(arg, classpath)}"
            case Tasty.Tree.MatchType(_, scr, _)          => s"${show(scr, classpath)} match { ... }"
            case Tasty.Tree.FlexibleType(arg)             => s"${show(arg, classpath)}?"
            case Tasty.Tree.IdentTpt(name, _)             => name.asString
            case Tasty.Tree.SelectTpt(qual, name)         => s"${show(qual, classpath)}.${name.asString}"
            case Tasty.Tree.SingletonTpt(tpe)             => s"${show(tpe, classpath)}.type"
            case Tasty.Tree.TermRefPkg(name)              => name.asString
            case Tasty.Tree.TypeRefPkg(name)              => name.asString
            case Tasty.Tree.TermRefSymbol(address, _)     => s"<termref@$address>"
            case Tasty.Tree.TypeRefSymbol(address, _)     => s"<typeref@$address>"
            case Tasty.Tree.TermRefDirect(address)        => s"<termdirect@$address>"
            case Tasty.Tree.TypeRefDirect(address)        => s"<typedirect@$address>"
            case Tasty.Tree.SelectIn(qual, name, _)       => s"${show(qual, classpath)}.${name.asString}"
            case Tasty.Tree.Import(qual, _)               => s"import ${show(qual, classpath)}.{...}"
            case Tasty.Tree.Export(qual, _)               => s"export ${show(qual, classpath)}.{...}"
            case Tasty.Tree.AnnotationNode(annotation, _) => s"@${show(annotation, classpath)}"
            case Tasty.Tree.TermRef(prefix, name)         => s"${show(prefix, classpath)}.${name.asString}"
            case Tasty.Tree.SeqLiteral(elems, _)          => s"[${elems.map(show(_, classpath)).mkString(", ")}]"
            case Tasty.Tree.TypeRefTree(qual, name)       => s"${show(qual, classpath)}.${name.asString}"
            case Tasty.Tree.SelfDef(name, _)              => s"self $name"
            case Tasty.Tree.SelectOuter(qual, name, _, _) => s"${show(qual, classpath)}.${name.asString}"
            case Tasty.Tree.Imported(qual)                => show(qual, classpath)
            case Tasty.Tree.Renamed(name)                 => name.asString
            case Tasty.Tree.ByNameTpt(inner)              => s"=> ${showType(inner, classpath)}"
            case Tasty.Tree.Bounded(bound)                => show(bound, classpath)
            case Tasty.Tree.ExplicitTpt(inner)            => showType(inner, classpath)
            case Tasty.Tree.Elided(inner)                 => showType(inner, classpath)
            case Tasty.Tree.RecThisAddr(address)          => s"<recthis@$address>"
            case Tasty.Tree.Unknown(tag, _)               => s"<unknown:$tag>"
        end match
    end show

end TreeShow
