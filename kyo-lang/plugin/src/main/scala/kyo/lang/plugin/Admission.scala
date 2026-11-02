package kyo.lang.plugin

import dotty.tools.dotc.ast.tpd
import dotty.tools.dotc.cc.SafeRefs
import dotty.tools.dotc.core.Contexts.*
import dotty.tools.dotc.core.Decorators.*
import dotty.tools.dotc.core.Flags.*
import dotty.tools.dotc.core.NameKinds.DefaultGetterName
import dotty.tools.dotc.core.Symbols.*
import dotty.tools.dotc.plugins.PluginPhase
import dotty.tools.dotc.report

/** Admits the APIs a `.kyo` body may use, with the compiler's own notion of safe code.
  *
  * This post-typer phase visits every term reference of a `.kyo` unit and rejects the ones outside the admitted surface. A reference is
  * admitted when it is one of:
  *
  *   - a local definition or a parameter, validated where it is defined;
  *   - a definition carrying `kyo.lang.Checked`, a compiled `.kyo` definition;
  *   - a Kyo API, any definition in package `kyo` or below, unless it requires `AllowUnsafe`: a parameter of that type, or a member of
  *     the `AllowUnsafe` object itself;
  *   - safe by the compiler's safe mode: the definition or an owner carries `scala.caps.assumeSafe`, or its package is one the compiler
  *     assumes safe, and neither it nor an owner carries `scala.caps.rejectSafe`.
  *
  * The last rule is the one `import language.experimental.safe` applies, taken from the compiler's `SafeRefs` rather than re-stated: its
  * package list and its in-memory marks on the standard library and the JDK (`Predef` without `println`, `java.lang.String`, the boxes,
  * the exceptions, no `scala.Console`, no mutable runtime cells). Safe mode itself is not enabled, since it implies capture checking,
  * which is experimental; only its judgment is reused, and a library compiled under it is recognized by the annotations it carries.
  * A member is admitted through its receiver as well: safe mode checks static references and leaves members to capture checking, so
  * here a member of an admitted class counts as admitted wherever it is declared. `scala.collection`, the generic collection traits
  * and factories, is added to the compiler's packages; its mutable implementations live in `scala.collection.mutable`, which stays out.
  *
  * The language excludes a few operations the compiler's list accepts, because they are impure or partial rather than capability
  * unsafe: casts and runtime type tests, monitors and reference identity, `scala.util.Random`, and the partial collection operations
  * (`head`, `last`, `reduce`, `max`, positional `apply`, `Option.get`, `???`).
  */
final class Admission extends PluginPhase:
    val phaseName: String = "kyoLangAdmission"

    override val runsAfter: Set[String] = Set("typer")

    /** `SafeRefs.init()` marks the standard library once per compiler run. */
    private var markedRun: Int = -1

    override def run(using Context): Unit =
        if !Preparation.isKyoUnit then return
        if markedRun != ctx.runId then
            SafeRefs.init()
            markedRun = ctx.runId
        val checked     = requiredClass(Preparation.checkedClass)
        val allowUnsafe = requiredModule("kyo.AllowUnsafe").moduleClass
        val excluded    = Admission.excluded
        val packages    = defn.assumedSafePackages + requiredPackage("scala.collection").moduleClass

        def local(sym: Symbol): Boolean = sym.owner.isTerm

        /** Defined in the unit being checked: every definition in it goes through this phase, synthetic wrappers included. */
        def ownUnit(sym: Symbol): Boolean = sym.source == ctx.compilationUnit.source

        /** Carries `Checked`, or is a member of a definition that does, the default getter of a method that does, or the companion the
          * compiler generated for a class that does.
          */
        def isChecked(sym: Symbol): Boolean =
            sym.exists && !sym.is(Package) &&
                (sym.hasAnnotation(checked) || isChecked(sym.owner) || checkedDefaultGetter(sym) || checkedCompanion(sym))

        def checkedCompanion(sym: Symbol): Boolean =
            val cls = if sym.is(ModuleVal) then sym.moduleClass else sym
            cls.is(ModuleClass) && cls.companionClass.exists && cls.companionClass.hasAnnotation(checked)

        def checkedDefaultGetter(sym: Symbol): Boolean = sym.name match
            case DefaultGetterName(method, _) => sym.owner.info.member(method).alternatives.exists(alt => isChecked(alt.symbol))
            case _                            => false

        def requiresAllowUnsafe(sym: Symbol): Boolean =
            sym.ownersIterator.contains(allowUnsafe) ||
                sym.info.paramInfoss.flatten.exists(_.dealias.typeSymbol == allowUnsafe)

        def kyoSafe(sym: Symbol): Boolean =
            val pkg = sym.enclosingPackageClass.fullName.toString
            (pkg == "kyo" || pkg.startsWith("kyo.")) && !requiresAllowUnsafe(sym)

        /** The compiler's `SafeRefs.isSafe`: marked safe, or owned by something marked safe, up to an assumed-safe package. */
        def compilerSafe(sym: Symbol): Boolean =
            if !sym.exists then false
            else if sym.is(Package) then packages.contains(sym)
            else sym.hasAnnotation(defn.AssumeSafeAnnot) || compilerSafe(if sym.is(ModuleVal) then sym.moduleClass else sym.owner)

        /** The compiler's `checkNotRejected`, plus the language's own exclusions. */
        def rejected(sym: Symbol): Boolean =
            sym.exists && !sym.is(Package) &&
                (sym.hasAnnotation(defn.RejectSafeAnnot) || excluded(sym) || rejected(sym.owner))

        def admitted(sym: Symbol): Boolean =
            !sym.exists || sym.is(Package) || local(sym) || ownUnit(sym) || isChecked(sym) ||
                (!rejected(sym) && (kyoSafe(sym) || compilerSafe(sym)))

        /** A member is also admitted through its receiver: a member of an admitted class is admitted, wherever it is declared, as the
          * compiler's safe mode checks the static receiver rather than every member it reaches. The language's exclusions still apply.
          */
        def admittedMember(member: Symbol, receiver: tpd.Tree): Boolean =
            admitted(member) || {
                val cls = receiver.tpe.widen.dealias.classSymbol
                !rejected(member) && cls.exists && (ownUnit(cls) || isChecked(cls) || kyoSafe(cls) || compilerSafe(cls))
            }

        /** The symbol's name as a reader writes it: an object's member is `Object.member`, not `Object$.member`. */
        def display(sym: Symbol): String =
            val owner = sym.owner
            if !owner.exists || owner.is(Package) then sym.fullName.toString
            else if owner.isTerm then sym.name.toString
            else display(if owner.is(ModuleClass) then owner.sourceModule else owner) + "." + sym.name.toString
        end display

        def reject(tree: tpd.Tree): Unit =
            val kind = if tree.symbol.is(Method) then "API" else "Reference"
            report.error(s"$kind is not admitted in .kyo: ${display(tree.symbol)}", tree.srcPos)

        /** A compiler-generated member whose body is not the user's: the machinery of enums and case classes, trusted like an admitted
          * inline API. An anonymous function is the user's lambda, and an export forwarder, a default getter, and a derived instance
          * come from the user's source; all of those are checked.
          */
        def trustedSynthetic(sym: Symbol): Boolean =
            sym.is(Synthetic) && !sym.isAnonymousFunction && !sym.is(Exported) &&
                !sym.name.is(DefaultGetterName) && !sym.name.startsWith("derived$")

        /** The parents the compiler adds on its own to a class, enum, or case class. */
        val implicitParents: Set[Symbol] = Set(
            defn.ObjectClass,
            defn.AnyClass,
            defn.ProductClass,
            defn.SerializableClass,
            defn.EnumClass,
            requiredClass("scala.runtime.EnumValue")
        )

        /** The closed-hierarchy policy after typing: every parent is admitted, so an unsafe class cannot be extended, and a checked
          * parent is sealed, so its implementations are in its own unit and checked.
          */
        def checkParents(definition: tpd.TypeDef, template: tpd.Template): Unit =
            template.parents.foreach { parent =>
                val cls = parent.tpe.classSymbol
                if !implicitParents.contains(cls) && !admitted(cls) then
                    report.error(s"Parent is not admitted in .kyo: ${display(cls)}", parent.srcPos)
                else if isChecked(cls) && !cls.is(Sealed) && !cls.is(Final) && !cls.is(Enum) && !cls.is(Module) then
                    report.error(s"Parent is not sealed: ${display(cls)}", parent.srcPos)
                end if
            }

        /** Safe initialization: a field initializer and a parent constructor argument may read a constructor parameter or a field
          * defined before it, and nothing else of the object under construction. A method call could read a field not yet set, and
          * `this` as a value could escape before the object is complete.
          */
        def checkInitialization(definition: tpd.TypeDef, template: tpd.Template): Unit =
            val cls                                                = definition.symbol
            def check(initializer: tpd.Tree, position: Long): Unit =
                def fail(tree: tpd.Tree, message: String): Unit = report.error(message, tree.srcPos)
                def earlier(member: Symbol): Boolean            =
                    member.is(ParamAccessor) || (member.isTerm && !member.is(Method) && member.span.exists && member.span.start < position)
                // A nested object is initialized on its first access and a synthetic member is the compiler's, so both are safe.
                def member(tree: tpd.Tree): Unit =
                    val member = tree.symbol
                    if member.is(Module) || member.isType || member.is(Synthetic) then ()
                    else if member.is(Method) && !member.is(ParamAccessor) then
                        fail(tree, s"An initializer may not call ${member.name}; the object is not initialized yet")
                    else if !earlier(member) then
                        fail(tree, s"An initializer may not read ${member.name} before it is initialized")
                    end if
                end member
                // A member of the object under construction is referenced through `this` or, unqualified, by name.
                val visitor = new tpd.TreeTraverser:
                    def traverse(tree: tpd.Tree)(using Context): Unit = tree match
                        case tpd.Select(qualifier: tpd.This, _) if qualifier.symbol == cls => member(tree)
                        case tree: tpd.Ident if tree.symbol.maybeOwner == cls              => member(tree)
                        case tree: tpd.This if tree.symbol == cls                          =>
                            fail(tree, "this may not escape while the object is initialized")
                        case _ => traverseChildren(tree)
                visitor.traverse(initializer)
            end check
            template.parents.foreach(parent => check(parent, parent.span.start))
            template.body.foreach {
                // A lazy field and a nested object are initialized on first access, after the enclosing object is complete.
                case field: tpd.ValDef if !field.symbol.isOneOf(Synthetic | ParamAccessor | Lazy | Module) && !field.rhs.isEmpty =>
                    check(field.rhs, field.span.start)
                case _ => ()
            }
        end checkInitialization

        val visitor = new tpd.TreeTraverser:
            def traverse(tree: tpd.Tree)(using Context): Unit = tree match
                case _: tpd.Import | _: tpd.TypTree       => ()
                case tree: tpd.Annotated                  => traverse(tree.arg)
                case tree: tpd.TypeDef if tree.isClassDef =>
                    tree.rhs match
                        case template: tpd.Template =>
                            checkParents(tree, template)
                            checkInitialization(tree, template)
                        case _ => ()
                    end match
                    traverseChildren(tree)
                case tree: tpd.DefDef if trustedSynthetic(tree.symbol) => ()
                case tree: tpd.ValDef if trustedSynthetic(tree.symbol) => ()
                case tree: tpd.Select if tree.symbol.isTerm            =>
                    if !admittedMember(tree.symbol, tree.qualifier) then reject(tree)
                    traverse(tree.qualifier)
                case tree: tpd.Ident if tree.symbol.isTerm && !admitted(tree.symbol) => reject(tree)
                case _                                                               => traverseChildren(tree)
        visitor.traverse(ctx.compilationUnit.tpdTree)
    end run
end Admission

object Admission:

    /** The operations the language excludes although the compiler's safe mode accepts them: impure or partial, not capability unsafe. */
    private def excluded(using Context): Symbol => Boolean =
        def members(owner: String, names: String*): List[Symbol] =
            val cls = requiredClass(owner)
            names.toList.flatMap(name => cls.info.member(name.toTermName).alternatives.map(_.symbol))
        val methods = Set.from(
            members("java.lang.Object", "synchronized", "wait", "notify", "notifyAll", "eq", "ne", "getClass", "clone", "finalize") :::
                members("scala.Any", "asInstanceOf", "isInstanceOf") :::
                members("scala.collection.IterableOnceOps", "reduce", "reduceLeft", "reduceRight", "max", "min", "maxBy", "minBy") :::
                members("scala.collection.IterableOps", "head", "last") :::
                members("scala.collection.SeqOps", "apply") :::
                members("scala.collection.MapOps", "apply") :::
                members("scala.Option", "get") :::
                members("scala.Predef", "???")
        )
        val owners = Set(requiredModule("scala.util.Random").moduleClass, requiredClass("scala.util.Random"))
        sym => methods.contains(sym) || sym.allOverriddenSymbols.exists(methods.contains) || sym.ownersIterator.exists(owners.contains)
    end excluded
end Admission
