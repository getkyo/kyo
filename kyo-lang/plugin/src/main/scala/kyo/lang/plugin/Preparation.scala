package kyo.lang.plugin

import dotty.tools.dotc.ast.untpd
import dotty.tools.dotc.core.Contexts.*
import dotty.tools.dotc.core.Decorators.*
import dotty.tools.dotc.core.Flags.*
import dotty.tools.dotc.core.NameKinds.WildcardParamName
import dotty.tools.dotc.core.StdNames.nme
import dotty.tools.dotc.core.StdNames.tpnme
import dotty.tools.dotc.core.Symbols
import dotty.tools.dotc.plugins.PluginPhase
import dotty.tools.dotc.report

/** Prepares a `.kyo` compilation unit for ordinary typing and for the `Body` lowering macro.
  *
  * The phase runs after the parser and before the typer, on untyped trees, and touches only units whose source file ends in `.kyo`.
  * Nothing here knows a type: every decision that needs one is left to normal Scala typing and to the typed macro the phase inserts.
  *
  * For each definition body the phase:
  *
  *   - wraps every expression in `kyo.lang.internal.Markers.force`. Typing selects the overload: a pure `A` keeps the identity overload,
  *     a pending `A < S` selects the overload that is kyo-direct's `.now`. That is how a direct body exposes an operation's result
  *     without a user-facing annotation, and how the body becomes one kyo-direct knows how to lower.
  *   - wraps the whole body in `kyo.lang.internal.Body.apply` (methods) or `kyo.lang.internal.Body.field` (values), the typed macro that
  *     validates the body and lowers it through kyo-direct.
  *   - attaches the `kyo.lang.Checked` annotation, the metadata a later compilation reads to admit the definition.
  *
  * Every `.kyo` unit also gets `import kyo.*` ahead of its own statements, so Kyo's API is in scope without an import; a unit that
  * writes the import as well is unaffected.
  *
  * The expression forms the language admits are the ones [[expression]] handles; every other form, and every declaration outside the
  * supported subset, is reported here at its source position with a message naming the construct: classes and traits, inheritance,
  * inline and implicit definitions, default parameters, mutable and lazy fields, object initialization statements, entry points,
  * exports, package objects, language imports, assignments, exceptions, returns, anonymous classes, postfix calls, and placeholder
  * lambdas. kyo-lang/DESIGN.md's construct inventory gives each its row.
  */
final class Preparation extends PluginPhase:
    import Preparation.*

    val phaseName: String = "kyoLangPreparation"

    /** The dotted names of the unit being prepared's package and of its parents, set for each unit by [[run]]. */
    private var ownPackages: Set[String] = Set.empty

    override val runsAfter: Set[String]  = Set("parser")
    override val runsBefore: Set[String] = Set("typer")

    private def qualified(parts: String*)(using Context): untpd.Tree =
        parts.foldLeft[untpd.Tree](untpd.Ident("_root_".toTermName))((tree, part) => untpd.Select(tree, part.toTermName))

    private def force(tree: untpd.Tree)(using Context): untpd.Tree =
        untpd.Apply(qualified(markersPath*), List(tree)).withSpan(tree.span)

    private def reject(tree: untpd.Tree, message: String)(using Context): untpd.Tree =
        report.error(message, tree.srcPos)
        tree

    /** The dotted spelling of a stable path of identifiers, `""` for `_root_`; `None` for any other tree. */
    private def path(tree: untpd.Tree): Option[String] = tree match
        case untpd.Ident(name) if name == nme.ROOTPKG => Some("")
        case untpd.Ident(name)                        => Some(name.toString)
        case untpd.Select(qualifier, name)            =>
            path(qualifier).map(prefix => if prefix.isEmpty then name.toString else s"$prefix.$name")
        case _ => None

    /** True when the tree is a stable path naming a package: one on the classpath, such as `java.lang`, the unit's own package or
      * one of its parents, or the first segment under `_root_`. A package is not a value, so it cannot be wrapped in a marker; the
      * first selection that leaves the package is wrapped instead. A package first defined in the same compilation, other than the
      * unit's own, is reached through an import.
      */
    private def isPackage(tree: untpd.Tree, own: Set[String])(using Context): Boolean =
        tree match
            case untpd.Select(untpd.Ident(nme.ROOTPKG), _) => true
            case _                                         =>
                path(tree).exists(dotted => dotted.isEmpty || own.contains(dotted) || Symbols.getPackageClassIfDefined(dotted).exists)

    /** The function position of an application: its qualifier is an expression, its name is not. */
    private def callee(tree: untpd.Tree)(using Context): untpd.Tree = tree match
        case tree: untpd.Select if isPackage(tree.qualifier, ownPackages) => tree
        case tree: untpd.Select                                           => untpd.cpy.Select(tree)(expression(tree.qualifier), tree.name)
        case tree: untpd.TypeApply                                        => untpd.cpy.TypeApply(tree)(callee(tree.fun), tree.args)
        case tree: untpd.Apply => untpd.cpy.Apply(tree)(callee(tree.fun), tree.args.map(expression))
        case _                 => tree

    private def caseDef(tree: untpd.CaseDef)(using Context): untpd.CaseDef =
        val guard = if tree.guard.isEmpty then tree.guard else expression(tree.guard)
        untpd.cpy.CaseDef(tree)(tree.pat, guard, expression(tree.body))

    private def enumerator(tree: untpd.Tree)(using Context): untpd.Tree = tree match
        case generator: untpd.GenFrom => untpd.cpy.GenFrom(generator)(generator.pat, expression(generator.expr), generator.checkMode)
        case alias: untpd.GenAlias    => untpd.cpy.GenAlias(alias)(alias.pat, expression(alias.expr))
        case guard                    => expression(guard)

    private def statement(tree: untpd.Tree)(using Context): untpd.Tree = tree match
        case value: untpd.ValDef    => untpd.cpy.ValDef(value)(value.name, value.tpt, expression(value.rhs))
        case pattern: untpd.PatDef  => untpd.cpy.PatDef(pattern)(pattern.mods, pattern.pats, pattern.tpt, expression(pattern.rhs))
        case method: untpd.DefDef   => untpd.cpy.DefDef(method)(method.name, method.paramss, method.tpt, expression(method.rhs))
        case imported: untpd.Import => languageImport(imported)
        case definition: untpd.TypeDef if !definition.isClassDef => definition
        case other                                               => expression(other)

    private def languageImport(tree: untpd.Import)(using Context): untpd.Tree =
        if path(tree.expr).exists(dotted => dotted == "scala.language" || dotted.startsWith("scala.language.")) then
            reject(tree, "Language imports are not supported in .kyo; the language's features are fixed")
        else tree

    private def expression(tree: untpd.Tree)(using Context): untpd.Tree = tree match
        case tree: untpd.Literal   => tree
        case tree: untpd.Number    => tree
        case tree: untpd.Ident     => force(tree)
        case tree: untpd.Select    => force(callee(tree))
        case tree: untpd.TypeApply => force(callee(tree))
        case tree: untpd.Apply     => force(untpd.cpy.Apply(tree)(callee(tree.fun), tree.args.map(expression)))
        case tree: untpd.Function  =>
            tree.args.foreach {
                case parameter: untpd.ValDef if parameter.name.is(WildcardParamName) =>
                    report.error("Placeholder lambda shorthand is not canonical .kyo; name the parameter", tree.srcPos)
                case _ => ()
            }
            untpd.cpy.Function(tree)(tree.args, expression(tree.body))
        case tree: untpd.InfixOp =>
            force(untpd.cpy.InfixOp(tree)(expression(tree.left), tree.op, expression(tree.right)))
        case tree: untpd.PrefixOp  => force(untpd.cpy.PrefixOp(tree)(tree.op, expression(tree.od)))
        case tree: untpd.PostfixOp => reject(tree, "Postfix calls are not canonical .kyo; use dot syntax")
        case tree: untpd.Parens    => untpd.cpy.Parens(tree)(expression(tree.t))
        case tree: untpd.Tuple     => untpd.cpy.Tuple(tree)(tree.trees.map(expression))
        case tree: untpd.Typed     => untpd.cpy.Typed(tree)(expression(tree.expr), tree.tpt)
        case tree: untpd.NamedArg  => untpd.cpy.NamedArg(tree)(tree.name, expression(tree.arg))
        case tree: untpd.New       =>
            tree.tpt match
                case _: untpd.Template => reject(tree, "Anonymous classes are not supported in .kyo")
                case _                 => tree
        case tree: untpd.InterpolatedString =>
            val segments = tree.segments.map {
                case untpd.Thicket(List(part, interpolated)) => untpd.Thicket(List(part, expression(interpolated)))
                case part                                    => part
            }
            force(untpd.cpy.InterpolatedString(tree)(tree.id, segments))
        case tree: untpd.Block => untpd.cpy.Block(tree)(tree.stats.map(statement), expression(tree.expr))
        case tree: untpd.If    => untpd.cpy.If(tree)(expression(tree.cond), expression(tree.thenp), expression(tree.elsep))
        case tree: untpd.Match =>
            val selector = if tree.selector.isEmpty then tree.selector else expression(tree.selector)
            untpd.cpy.Match(tree)(selector, tree.cases.map(caseDef))
        case tree: untpd.WhileDo                 => untpd.cpy.WhileDo(tree)(expression(tree.cond), expression(tree.body))
        case tree: untpd.ForYield                => untpd.cpy.ForYield(tree)(tree.enums.map(enumerator), expression(tree.expr))
        case tree: untpd.ForDo                   => untpd.cpy.ForDo(tree)(tree.enums.map(enumerator), expression(tree.body))
        case tree: untpd.Return                  => reject(tree, "return is not supported in .kyo; the last expression is the result")
        case tree: untpd.Throw                   => reject(tree, "throw is not supported in .kyo; fail with Abort")
        case tree: (untpd.Try | untpd.ParsedTry) => reject(tree, "try is not supported in .kyo; handle failures with Abort.run")
        case tree: untpd.Assign                  => reject(tree, "Assignment is not supported in .kyo; keep state in an effect such as Var")
        case _ => reject(tree, s"Expression form is not supported in .kyo: ${tree.getClass.getSimpleName}")

    /** True when the annotation tree names `main`, the compiler's entry-point annotation. */
    private def isMain(annotation: untpd.Tree): Boolean = annotation match
        case untpd.Apply(untpd.Select(untpd.New(tpt), _), _) => path(tpt).exists(dotted => dotted == "main" || dotted.endsWith(".main"))
        case _                                               => false

    override def run(using Context): Unit =
        if !isKyoUnit then return
        ownPackages = ctx.compilationUnit.untpdTree match
            case pkg: untpd.PackageDef =>
                path(pkg.pid).toSet.flatMap(dotted =>
                    dotted.split('.').scanLeft("")((prefix, part) => if prefix.isEmpty then part else s"$prefix.$part").toSet
                )
            case _ => Set.empty

        def body(tree: untpd.Tree, field: Boolean): untpd.Tree =
            val entry = if field then "field" else "apply"
            untpd.Apply(qualified((bodyPath :+ entry)*), List(expression(tree))).withSpan(tree.span)

        def checkedMods(member: untpd.MemberDef): untpd.Modifiers =
            val tpt        = untpd.Select(qualified(checkedPackage*), checkedName.toTypeName)
            val annotation = untpd.Apply(untpd.Select(untpd.New(tpt), nme.CONSTRUCTOR), Nil).withSpan(member.span)
            member.mods.withAddedAnnotation(annotation)
        end checkedMods

        val mapper = new untpd.UntypedTreeMap:
            private def template(tree: untpd.Template)(using Context): untpd.Template =
                if tree.parents.nonEmpty then
                    report.error("Object inheritance is not supported in .kyo", tree.srcPos)
                val members = tree.body.map {
                    case exported: untpd.Export       => reject(exported, "Export clauses are not supported in .kyo")
                    case pattern: untpd.PatDef        => reject(pattern, "Pattern bindings are supported only inside method bodies in .kyo")
                    case extensions: untpd.ExtMethods =>
                        untpd.cpy.ExtMethods(extensions)(extensions.paramss, extensions.methods.map(transform))
                    case member: untpd.MemberDef => transform(member)
                    case imported: untpd.Import  => languageImport(imported)
                    case statement               => reject(statement, "Object initialization statements are not supported in .kyo")
                }
                untpd.cpy.Template(tree)(tree.constr, tree.parents, tree.derived, tree.self, members)
            end template

            override def transform(tree: untpd.Tree)(using Context): untpd.Tree = tree match
                case module: untpd.ModuleDef if module.mods.is(Package) =>
                    reject(module, "Package objects are not supported in .kyo; use top-level definitions")
                case module: untpd.ModuleDef =>
                    untpd.cpy.ModuleDef(module)(module.name, template(module.impl)).withMods(checkedMods(module))
                case definition: untpd.TypeDef if definition.isClassDef =>
                    reject(definition, "Classes and traits are not supported in .kyo; use objects and methods")
                case tree: untpd.Template                                                          => template(tree)
                case method: untpd.DefDef if !method.rhs.isEmpty && method.name != nme.CONSTRUCTOR =>
                    if method.mods.is(Inline) || method.mods.is(Implicit) || method.mods.is(Given) then
                        report.error("Inline and implicit definitions are not supported in .kyo", method.srcPos)
                    if method.mods.annotations.exists(isMain) then
                        report.error(
                            "Program entry points are not supported in .kyo; an ordinary Scala host runs the program",
                            method.srcPos
                        )
                    end if
                    method.paramss.flatten.foreach {
                        case parameter: untpd.ValDef if !parameter.rhs.isEmpty =>
                            report.error("Default parameters are not supported in .kyo", parameter.srcPos)
                        case _ => ()
                    }
                    untpd.cpy.DefDef(method)(method.name, method.paramss, method.tpt, body(method.rhs, false))
                        .withMods(checkedMods(method))
                case value: untpd.ValDef if !value.rhs.isEmpty =>
                    if value.mods.is(Inline) || value.mods.is(Implicit) || value.mods.is(Given) then
                        report.error("Inline and implicit definitions are not supported in .kyo", value.srcPos)
                    else if value.mods.is(Mutable) || value.mods.is(Lazy) then
                        report.error("Mutable and lazy fields are not supported in .kyo", value.srcPos)
                    end if
                    untpd.cpy.ValDef(value)(value.name, value.tpt, body(value.rhs, true)).withMods(checkedMods(value))
                case imported: untpd.Import => languageImport(imported)
                case _                      => super.transform(tree)

        /** The unit with `import kyo.*` ahead of its own statements: every `.kyo` file sees Kyo's API without writing the import. */
        def imported(tree: untpd.Tree): untpd.Tree = tree match
            case pkg: untpd.PackageDef =>
                val start    = pkg.span.startPos
                val wildcard = untpd.ImportSelector(untpd.Ident(nme.WILDCARD).withSpan(start)).withSpan(start)
                val kyo      = untpd.Import(untpd.Ident("kyo".toTermName).withSpan(start), List(wildcard)).withSpan(start)
                untpd.cpy.PackageDef(pkg)(pkg.pid, kyo :: pkg.stats)
            case other => other

        // The unit's untyped tree is replaced before the typer reads it; the compiler owns the unit and drives this phase.
        ctx.compilationUnit.untpdTree = imported(mapper.transform(ctx.compilationUnit.untpdTree))
    end run
end Preparation

object Preparation:

    /** The source extension that selects the language. */
    val extension: String = ".kyo"

    /** The annotation class attached to every compiled `.kyo` definition. */
    val checkedPackage: List[String] = List("kyo", "lang")
    val checkedName: String          = "Checked"
    val checkedClass: String         = (checkedPackage :+ checkedName).mkString(".")

    private val markersPath: List[String] = List("kyo", "lang", "internal", "Markers", "force")
    private val bodyPath: List[String]    = List("kyo", "lang", "internal", "Body")

    /** True when the compilation unit being compiled is a `.kyo` source. */
    def isKyoUnit(using Context): Boolean =
        ctx.compilationUnit.source.file.name.endsWith(extension)
end Preparation
