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
  *     a pending `A < S` selects the overload that is kyo-direct's `.now`, except where a computation is expected, where the identity
  *     overload passes the computation through. That is how a direct body exposes an operation's result without a user-facing
  *     annotation, and how the body becomes one kyo-direct knows how to lower.
  *   - wraps the body of a lambda outside argument position in `kyo.lang.internal.Body.closure`, so a stored or returned closure with an
  *     effectful body is a function returning a computation. The body of a lambda in argument position keeps its markers: the
  *     receiving parameter's type decides, through typing, whether the body is a value (a callback to a method with a Kyo
  *     counterpart) or a computation (a callback to an effect-aware API).
  *   - wraps the whole body in `kyo.lang.internal.Body.apply` (methods) or `kyo.lang.internal.Body.field` (values), the typed macro that
  *     validates the body and lowers it through kyo-direct.
  *   - attaches the `kyo.lang.Checked` annotation, the metadata a later compilation reads to admit the definition.
  *
  * Every `.kyo` unit also gets `import kyo.*` ahead of its own statements, so Kyo's API is in scope without an import; a unit that
  * writes the import as well is unaffected. It also gets `import kyo.lang.internal.Markers.boundary`, the conversion the typer applies
  * where an expression of an ordinary type meets an expected pending type (a handler's argument, the body of a lambda an effect-aware
  * API calls): its expansion lowers the expression into a computation with the precise row before the receiving call is typed.
  *
  * A default argument is wrapped in `kyo.lang.internal.Body.default`, which the compiler makes the body of the default getter: pure
  * or rejected. A pattern binding at the object level is a field. A constant `inline val` keeps its literal, which must be one. An
  * `export` clause is left to the compiler: the forwarders it generates call the exported member, which admission checks.
  *
  * Class-like definitions follow the closed-hierarchy policy: a trait or abstract class is sealed, a concrete class is final, enums,
  * enum cases, and given instances are closed by construction, and a constructor parameter is immutable. Their templates are prepared
  * like an object's; the parents and the initialization order are checked after typing by `Admission`. An alias given is a field, a
  * conditional given is a method, and a structural given is a class.
  *
  * The expression forms the language admits are the ones [[expression]] handles; every other form, and every declaration outside the
  * supported subset, is reported here at its source position with a message naming the construct: open classes and unsealed traits,
  * inline methods and Scala 2 implicit definitions, mutable and lazy fields, initialization statements, entry points, package objects,
  * language imports, assignments, exceptions, returns, anonymous classes, postfix calls, and placeholder lambdas.
  * kyo-lang/DESIGN.md's construct inventory gives each its row.
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

    /** The body of a lambda outside argument position, lowered on its own so that the closure's type carries its latent effects. */
    private def closure(tree: untpd.Tree)(using Context): untpd.Tree =
        untpd.Apply(qualified((bodyPath :+ "closure")*), List(tree)).withSpan(tree.span)

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
        case tree: untpd.Apply => untpd.cpy.Apply(tree)(callee(tree.fun), tree.args.map(argument))
        case _                 => tree

    /** An argument: an expression whose expected type is the receiving parameter's, marked as a whole. */
    private def argument(tree: untpd.Tree)(using Context): untpd.Tree = whole(tree, argument = true)

    /** The expression marked as one whole where typing meets an expected type: a block, a conditional, a match, or an ascription gets
      * a marker around it, so that a computation expected there is lowered from the whole expression and not from its result alone,
      * which would leave the markers of its statements and conditions behind. Every other form carries its own marker already, or is a
      * lambda or a literal, which typing shapes from the expected type itself.
      */
    private def whole(tree: untpd.Tree, argument: Boolean)(using Context): untpd.Tree =
        val marked = expression(tree, argument)
        tree match
            case _: untpd.Block | _: untpd.If | _: untpd.Match | _: untpd.Typed if !isLambda(tree) => force(marked)
            case _                                                                                 => marked
    end whole

    /** True for a lambda, written with parameters or as cases (a match without a selector), possibly inside braces. */
    private def isLambda(tree: untpd.Tree): Boolean = tree match
        case _: untpd.Function        => true
        case matched: untpd.Match     => matched.selector.isEmpty
        case untpd.Block(Nil, result) => isLambda(result)
        case _                        => false

    private def caseDef(tree: untpd.CaseDef, argument: Boolean)(using Context): untpd.CaseDef =
        val guard = if tree.guard.isEmpty then tree.guard else expression(tree.guard)
        untpd.cpy.CaseDef(tree)(tree.pat, guard, expression(tree.body, argument))

    /** The case of a lambda written as cases, outside argument position: each body is lowered on its own, like a lambda body. */
    private def closureCase(tree: untpd.CaseDef)(using Context): untpd.CaseDef =
        val guard = if tree.guard.isEmpty then tree.guard else expression(tree.guard)
        untpd.cpy.CaseDef(tree)(tree.pat, guard, closure(expression(tree.body)))

    private def enumerator(tree: untpd.Tree)(using Context): untpd.Tree = tree match
        case generator: untpd.GenFrom => untpd.cpy.GenFrom(generator)(generator.pat, expression(generator.expr), generator.checkMode)
        case alias: untpd.GenAlias    => untpd.cpy.GenAlias(alias)(alias.pat, expression(alias.expr))
        case guard                    => expression(guard)

    private def statement(tree: untpd.Tree)(using Context): untpd.Tree = tree match
        case value: untpd.ValDef   => untpd.cpy.ValDef(value)(value.name, value.tpt, whole(value.rhs, argument = false))
        case pattern: untpd.PatDef =>
            untpd.cpy.PatDef(pattern)(pattern.mods, pattern.pats, pattern.tpt, whole(pattern.rhs, argument = false))
        case method: untpd.DefDef   => untpd.cpy.DefDef(method)(method.name, method.paramss, method.tpt, closure(expression(method.rhs)))
        case imported: untpd.Import => languageImport(imported)
        case definition: untpd.TypeDef if !definition.isClassDef => definition
        case other                                               => expression(other)

    private def languageImport(tree: untpd.Import)(using Context): untpd.Tree =
        if path(tree.expr).exists(dotted => dotted == "scala.language" || dotted.startsWith("scala.language.")) then
            reject(tree, "Language imports are not supported in .kyo; the language's features are fixed")
        else tree

    /** The expression with its markers. `argument` is true in argument position, where the receiving parameter's type is the expected
      * type: a lambda there keeps the markers in its body for typing to resolve, while a lambda anywhere else gets its body lowered on
      * its own. The flag follows the expected type through parentheses, blocks, branches, ascriptions, and named arguments.
      */
    private def expression(tree: untpd.Tree, argument: Boolean = false)(using Context): untpd.Tree = tree match
        case tree: untpd.Literal   => tree
        case tree: untpd.Number    => tree
        case tree: untpd.Ident     => force(tree)
        case tree: untpd.This      => force(tree)
        case tree: untpd.Select    => force(callee(tree))
        case tree: untpd.TypeApply => force(callee(tree))
        case tree: untpd.Apply     => force(untpd.cpy.Apply(tree)(callee(tree.fun), tree.args.map(this.argument)))
        case tree: untpd.Function  =>
            tree.args.foreach {
                case parameter: untpd.ValDef if parameter.name.is(WildcardParamName) =>
                    report.error("Placeholder lambda shorthand is not canonical .kyo; name the parameter", tree.srcPos)
                case _ => ()
            }
            val body = if argument then whole(tree.body, argument = true) else closure(expression(tree.body))
            untpd.cpy.Function(tree)(tree.args, body)
        case tree: untpd.InfixOp =>
            force(untpd.cpy.InfixOp(tree)(expression(tree.left), tree.op, this.argument(tree.right)))
        case tree: untpd.PrefixOp  => force(untpd.cpy.PrefixOp(tree)(tree.op, expression(tree.od)))
        case tree: untpd.PostfixOp => reject(tree, "Postfix calls are not canonical .kyo; use dot syntax")
        case tree: untpd.Parens    => untpd.cpy.Parens(tree)(expression(tree.t, argument))
        case tree: untpd.Tuple     => untpd.cpy.Tuple(tree)(tree.trees.map(expression(_, argument)))
        case tree: untpd.Typed     => untpd.cpy.Typed(tree)(expression(tree.expr, argument), tree.tpt)
        case tree: untpd.NamedArg  => untpd.cpy.NamedArg(tree)(tree.name, expression(tree.arg, argument))
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
        case tree: untpd.Block => untpd.cpy.Block(tree)(tree.stats.map(statement), expression(tree.expr, argument))
        case tree: untpd.If    =>
            val elsep = if tree.elsep.isEmpty then tree.elsep else expression(tree.elsep, argument)
            untpd.cpy.If(tree)(expression(tree.cond), expression(tree.thenp, argument), elsep)
        case tree: untpd.Match if tree.selector.isEmpty && !argument =>
            untpd.cpy.Match(tree)(tree.selector, tree.cases.map(closureCase))
        case tree: untpd.Match =>
            val selector = if tree.selector.isEmpty then tree.selector else expression(tree.selector)
            untpd.cpy.Match(tree)(selector, tree.cases.map(caseDef(_, argument)))
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

        /** The body macro around a definition body: `apply` for a method, `field` for a value, `default` for a default argument. A
          * declared type is ascribed inside the marker, so the body is typed against it: the macro's own type parameter would
          * otherwise stand between the two and the expected type would not reach the body's applications.
          */
        def body(tree: untpd.Tree, entry: String, declared: untpd.Tree = untpd.EmptyTree): untpd.Tree =
            val marked = expression(tree)
            val typed  =
                if declared.isEmpty then marked
                else
                    val inner = marked match
                        case untpd.Apply(function, List(inner)) if path(function).contains(markersPath.mkString(".")) => inner
                        case other                                                                                    => other
                    force(untpd.Typed(inner, declared).withSpan(tree.span))
            untpd.Apply(qualified((bodyPath :+ entry)*), List(typed)).withSpan(tree.span)
        end body

        def checkedMods(mods: untpd.Modifiers, tree: untpd.Tree): untpd.Modifiers =
            val tpt        = untpd.Select(qualified(checkedPackage*), checkedName.toTypeName)
            val annotation = untpd.Apply(untpd.Select(untpd.New(tpt), nme.CONSTRUCTOR), Nil).withSpan(tree.span)
            mods.withAddedAnnotation(annotation)
        end checkedMods

        /** The parameter clauses with each default argument wrapped as a body of its own: the compiler makes it the body of the
          * default getter, which is pure or rejected.
          */
        def parameters(method: untpd.DefDef): List[untpd.ParamClause] =
            method.paramss.map { clause =>
                if clause.forall(_.isInstanceOf[untpd.ValDef]) then
                    clause.asInstanceOf[List[untpd.ValDef]].map { parameter =>
                        if parameter.rhs.isEmpty then parameter
                        else untpd.cpy.ValDef(parameter)(parameter.name, parameter.tpt, body(parameter.rhs, "default"))
                    }
                else clause
            }

        /** True for a literal, the only initializer a constant `inline val` can have. */
        def isLiteral(tree: untpd.Tree): Boolean = tree match
            case _: untpd.Literal | _: untpd.Number => true
            case untpd.Parens(inner)                => isLiteral(inner)
            case _                                  => false

        val mapper = new untpd.UntypedTreeMap:
            /** A class, trait, enum, or given template: its members are prepared like an object's, its constructor's default arguments
              * like a method's, and its parents are left for the post-typer admission check. A mutable constructor parameter is rejected.
              */
            private def template(tree: untpd.Template)(using Context): untpd.Template =
                tree.constr.paramss.flatten.foreach {
                    case parameter: untpd.ValDef if parameter.mods.is(Mutable) =>
                        report.error("Mutable constructor parameters are not supported in .kyo", parameter.srcPos)
                    case _ => ()
                }
                val constr  = untpd.cpy.DefDef(tree.constr)(tree.constr.name, parameters(tree.constr), tree.constr.tpt, tree.constr.rhs)
                val members = tree.body.map {
                    // A forwarder's body is the exported member's call, which admission checks like any other.
                    case exported: untpd.Export                              => exported
                    case cases: untpd.PatDef if cases.mods.isAllOf(EnumCase) =>
                        untpd.cpy.PatDef(cases)(checkedMods(cases.mods, cases), cases.pats, cases.tpt, cases.rhs)
                    case pattern: untpd.PatDef =>
                        untpd.cpy.PatDef(pattern)(checkedMods(pattern.mods, pattern), pattern.pats, pattern.tpt, body(pattern.rhs, "field"))
                    case extensions: untpd.ExtMethods =>
                        untpd.cpy.ExtMethods(extensions)(extensions.paramss, extensions.methods.map(transform))
                    case member: untpd.MemberDef => transform(member)
                    case imported: untpd.Import  => languageImport(imported)
                    case statement               => reject(statement, "Initialization statements are not supported in .kyo")
                }
                untpd.cpy.Template(tree)(constr, tree.parents, tree.derived, tree.self, members)
            end template

            /** The shape policy of a class-like definition: a trait or abstract class is sealed, so its implementations are in this
              * unit and checked; a concrete class is final; an enum, an enum case, and a given instance are closed by construction.
              */
            private def classPolicy(definition: untpd.TypeDef)(using Context): Unit =
                val mods = definition.mods
                if mods.is(Implicit) then
                    report.error("Scala 2 implicit classes are not supported in .kyo; use an extension or a given", definition.srcPos)
                else if (mods.is(Trait) || mods.is(Abstract)) && !mods.is(Sealed) && !mods.is(Enum) then
                    report.error(
                        "A trait or abstract class in .kyo must be sealed; its implementations live in the same unit",
                        definition.srcPos
                    )
                else if !mods.is(Trait) && !mods.is(Abstract) && !mods.is(Final) && !mods.is(Enum) && !mods.isAllOf(EnumCase) &&
                    !mods.is(Given)
                then
                    report.error("A class in .kyo must be final; open classes are not supported", definition.srcPos)
                end if
            end classPolicy

            override def transform(tree: untpd.Tree)(using Context): untpd.Tree = tree match
                case module: untpd.ModuleDef if module.mods.is(Package) =>
                    reject(module, "Package objects are not supported in .kyo; use top-level definitions")
                case module: untpd.ModuleDef =>
                    untpd.cpy.ModuleDef(module)(module.name, template(module.impl)).withMods(checkedMods(module.mods, module))
                case definition: untpd.TypeDef if definition.isClassDef =>
                    classPolicy(definition)
                    definition.rhs match
                        case impl: untpd.Template =>
                            untpd.cpy.TypeDef(definition)(
                                definition.name,
                                template(impl)
                            ).withMods(checkedMods(definition.mods, definition))
                        case _ => definition.withMods(checkedMods(definition.mods, definition))
                    end match
                case tree: untpd.Template                                                         => template(tree)
                case method: untpd.DefDef if method.rhs.isEmpty && method.name != nme.CONSTRUCTOR =>
                    method.withMods(checkedMods(method.mods, method))
                case method: untpd.DefDef if !method.rhs.isEmpty && method.name != nme.CONSTRUCTOR =>
                    if method.mods.is(Inline) || method.mods.is(Implicit) then
                        report.error("Inline and implicit definitions are not supported in .kyo", method.srcPos)
                    if method.mods.annotations.exists(isMain) then
                        report.error(
                            "Program entry points are not supported in .kyo; an ordinary Scala host runs the program",
                            method.srcPos
                        )
                    end if
                    untpd.cpy.DefDef(method)(method.name, parameters(method), method.tpt, body(method.rhs, "apply", method.tpt))
                        .withMods(checkedMods(method.mods, method))
                case value: untpd.ValDef if value.mods.is(Inline) && !value.rhs.isEmpty =>
                    if !isLiteral(value.rhs) then
                        report.error("An inline val must be a literal constant in .kyo", value.rhs.srcPos)
                    value.withMods(checkedMods(value.mods, value))
                case value: untpd.ValDef if value.rhs.isEmpty && !value.mods.is(Param) && !value.mods.is(ParamAccessor) =>
                    value.withMods(checkedMods(value.mods, value))
                case value: untpd.ValDef if !value.rhs.isEmpty =>
                    if value.mods.is(Implicit) then
                        report.error("Inline and implicit definitions are not supported in .kyo", value.srcPos)
                    else if value.mods.is(Mutable) || (value.mods.is(Lazy) && !value.mods.is(Given)) then
                        report.error("Mutable and lazy fields are not supported in .kyo", value.srcPos)
                    end if
                    untpd.cpy.ValDef(value)(value.name, value.tpt, body(value.rhs, "field", value.tpt))
                        .withMods(checkedMods(value.mods, value))
                case imported: untpd.Import => languageImport(imported)
                case _                      => super.transform(tree)

        /** The unit with `import kyo.*`, the boundary conversion, and the conversions feature ahead of its own statements: every
          * `.kyo` file sees Kyo's API without writing the import, the typer finds the conversion in lexical scope, and a conversion
          * the unit declares (inventory row C06) is usable without a language import, which the language does not accept.
          */
        def imported(tree: untpd.Tree): untpd.Tree = tree match
            case pkg: untpd.PackageDef =>
                val start      = pkg.span.startPos
                val wildcard   = untpd.ImportSelector(untpd.Ident(nme.WILDCARD).withSpan(start)).withSpan(start)
                val kyo        = untpd.Import(untpd.Ident("kyo".toTermName).withSpan(start), List(wildcard)).withSpan(start)
                val boundary   = untpd.ImportSelector(untpd.Ident(boundaryName.toTermName).withSpan(start)).withSpan(start)
                val markers    = qualified(markersPath.init*).withSpan(start)
                val conversion = untpd.Import(markers, List(boundary)).withSpan(start)
                val feature    = untpd.ImportSelector(untpd.Ident("implicitConversions".toTermName).withSpan(start)).withSpan(start)
                val language   = untpd.Import(qualified("scala", "language").withSpan(start), List(feature)).withSpan(start)
                untpd.cpy.PackageDef(pkg)(pkg.pid, kyo :: conversion :: language :: pkg.stats)
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
    private val boundaryName: String      = "boundary"
    private val bodyPath: List[String]    = List("kyo", "lang", "internal", "Body")

    /** True when the compilation unit being compiled is a `.kyo` source. */
    def isKyoUnit(using Context): Boolean =
        ctx.compilationUnit.source.file.name.endsWith(extension)
end Preparation
