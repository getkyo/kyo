package kyo.lang.plugin

import dotty.tools.dotc.ast.untpd
import dotty.tools.dotc.core.Contexts.*
import dotty.tools.dotc.core.Decorators.*
import dotty.tools.dotc.core.Flags.*
import dotty.tools.dotc.core.StdNames.nme
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
  *     a pending `A < S` selects the overload that exposes `A` and records `S`. Those markers are what the macro later lowers into Kyo
  *     composition, which is how a direct body exposes an operation's result without a user-facing annotation.
  *   - wraps the whole body in `kyo.lang.internal.Body.apply` (methods) or `kyo.lang.internal.Body.field` (values), the typed macro that
  *     validates the body and lowers the markers.
  *   - attaches the `kyo.lang.Checked` annotation, the metadata a later compilation reads to admit the definition.
  *
  * Declarations outside the supported subset are reported here, at their source position: classes and traits, inheritance, inline and
  * implicit definitions, default parameters, mutable and lazy fields, and object initialization statements. The error text names the
  * construct so a reader of the diagnostic knows which rule applies.
  */
final class Preparation extends PluginPhase:
    import Preparation.*

    val phaseName: String = "kyoLangPreparation"

    override val runsAfter: Set[String]  = Set("parser")
    override val runsBefore: Set[String] = Set("typer")

    private def qualified(parts: String*)(using Context): untpd.Tree =
        parts.foldLeft[untpd.Tree](untpd.Ident("_root_".toTermName))((tree, part) => untpd.Select(tree, part.toTermName))

    private def force(tree: untpd.Tree)(using Context): untpd.Tree =
        untpd.Apply(qualified(markersPath*), List(tree)).withSpan(tree.span)

    /** The function position of an application: its qualifier is an expression, its name is not. */
    private def callee(tree: untpd.Tree)(using Context): untpd.Tree = tree match
        case tree: untpd.Select    => untpd.cpy.Select(tree)(expression(tree.qualifier), tree.name)
        case tree: untpd.TypeApply => untpd.cpy.TypeApply(tree)(callee(tree.fun), tree.args)
        case tree: untpd.Apply     => untpd.cpy.Apply(tree)(callee(tree.fun), tree.args.map(expression))
        case _                     => tree

    private def expression(tree: untpd.Tree)(using Context): untpd.Tree = tree match
        case tree: untpd.Literal   => tree
        case tree: untpd.Number    => tree
        case tree: untpd.Ident     => force(tree)
        case tree: untpd.Select    => force(callee(tree))
        case tree: untpd.TypeApply => force(callee(tree))
        case tree: untpd.Apply     => force(untpd.cpy.Apply(tree)(callee(tree.fun), tree.args.map(expression)))
        case tree: untpd.Function  => untpd.cpy.Function(tree)(tree.args, expression(tree.body))
        case tree: untpd.InfixOp   =>
            force(untpd.cpy.InfixOp(tree)(expression(tree.left), tree.op, expression(tree.right)))
        case tree: untpd.Block =>
            val stats = tree.stats.map {
                case value: untpd.ValDef => untpd.cpy.ValDef(value)(value.name, value.tpt, expression(value.rhs))
                case statement           => expression(statement)
            }
            untpd.cpy.Block(tree)(stats, expression(tree.expr))
        case tree: untpd.If =>
            untpd.cpy.If(tree)(expression(tree.cond), expression(tree.thenp), expression(tree.elsep))
        case _ =>
            report.error(s"Expression form is not supported in .kyo: ${tree.getClass.getSimpleName}", tree.srcPos)
            tree

    override def run(using Context): Unit =
        if !isKyoUnit then return

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
                    case member: untpd.MemberDef => transform(member)
                    case imported: untpd.Import  => imported
                    case statement               =>
                        report.error("Object initialization statements are not supported in .kyo", statement.srcPos)
                        statement
                }
                untpd.cpy.Template(tree)(tree.constr, tree.parents, tree.derived, tree.self, members)
            end template

            override def transform(tree: untpd.Tree)(using Context): untpd.Tree = tree match
                case module: untpd.ModuleDef =>
                    untpd.cpy.ModuleDef(module)(module.name, template(module.impl)).withMods(checkedMods(module))
                case definition: untpd.TypeDef if definition.isClassDef =>
                    report.error("Classes and traits are not supported in .kyo; use objects and methods", definition.srcPos)
                    definition
                case tree: untpd.Template                                                          => template(tree)
                case method: untpd.DefDef if !method.rhs.isEmpty && method.name != nme.CONSTRUCTOR =>
                    if method.mods.is(Inline) || method.mods.is(Implicit) || method.mods.is(Given) then
                        report.error("Inline and implicit definitions are not supported in .kyo", method.srcPos)
                    method.paramss.flatten.foreach {
                        case parameter: untpd.ValDef if !parameter.rhs.isEmpty =>
                            report.error("Default parameters are not supported in .kyo", parameter.srcPos)
                        case _ => ()
                    }
                    untpd.cpy.DefDef(method)(method.name, method.paramss, method.tpt, body(method.rhs, false))
                        .withMods(checkedMods(method))
                case value: untpd.ValDef if !value.rhs.isEmpty =>
                    if value.mods.is(Mutable) || value.mods.is(Lazy) then
                        report.error("Mutable and lazy fields are not supported in .kyo", value.srcPos)
                    untpd.cpy.ValDef(value)(value.name, value.tpt, body(value.rhs, true)).withMods(checkedMods(value))
                case _ => super.transform(tree)
        // The unit's untyped tree is replaced before the typer reads it; the compiler owns the unit and drives this phase.
        ctx.compilationUnit.untpdTree = mapper.transform(ctx.compilationUnit.untpdTree)
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
