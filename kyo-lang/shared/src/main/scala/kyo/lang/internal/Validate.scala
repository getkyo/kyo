package kyo.lang.internal

import scala.quoted.*

/** Validates the resolved calls and references of a `.kyo` body against the admitted API surface.
  *
  * Admission compares resolved symbols, never source names: an import or an alias grants nothing, and an inline call is checked at the
  * call itself while its trusted expansion is not re-validated. A definition carrying [[kyo.lang.Checked]] is admitted, which is how
  * separately compiled `.kyo` definitions reach each other. References to parameters, local values, and packages are admitted, every
  * other reference must resolve to an admitted symbol.
  *
  * The validator also enforces the language restrictions that do not depend on admission: no `null`, no mutable or lazy locals, and no
  * reads of mutable fields.
  *
  * The admitted surface is a fixed table at this stage of the implementation: the markers, the `Var` and `Abort` operations the
  * conformance programs use, the evidence derivations their inline expansions insert, and a few total operations on `Int`, `String`, and
  * `List`. The design replaces the table with the package rule for `kyo.*` without `AllowUnsafe` plus an exact external allowlist; the
  * table is the mechanism's evidence, not the language's standard library.
  */
private[internal] object Validate:

    def apply[A](value: Expr[A])(using Quotes): Unit =
        import quotes.reflect.*
        val checked       = TypeRepr.of[kyo.lang.Checked].typeSymbol
        val moduleMethods = List(
            "kyo.lang.internal.Markers"       -> List("force"),
            "kyo.Var"                         -> List("get", "update"),
            "kyo.Abort"                       -> List("fail", "run"),
            "kyo.Frame"                       -> List("derive"),
            "kyo.Tag"                         -> List("apply", "derive"),
            "scala.Predef"                    -> List("identity"),
            "scala.collection.immutable.List" -> List("apply")
        )
        val methods = moduleMethods.flatMap { (module, names) =>
            val symbol = Symbol.requiredModule(module)
            names.flatMap(symbol.methodMember)
        } ::: List("+", "-", "*", ">", "==").flatMap(Symbol.requiredClass("scala.Int").methodMember) :::
            Symbol.requiredClass("java.lang.String").methodMember("length") :::
            Symbol.requiredClass("scala.collection.immutable.List").methodMember("map")

        def approved(symbol: Symbol): Boolean =
            methods.exists(_.equals(symbol)) || symbol.hasAnnotation(checked)

        val modules          = moduleMethods.map((module, _) => Symbol.requiredModule(module))
        val aliases          = List(Symbol.requiredModule("scala.package").fieldMember("List"))
        val localDefinitions = new TreeAccumulator[List[Symbol]]:
            def foldTree(found: List[Symbol], tree: Tree)(owner: Symbol): List[Symbol] = tree match
                case definition: ValDef => foldOverTree(definition.symbol :: found, tree)(owner)
                case _                  => foldOverTree(found, tree)(owner)
        val locals = localDefinitions.foldTree(Nil, value.asTerm)(Symbol.spliceOwner)

        def approvedReference(symbol: Symbol): Boolean =
            symbol.flags.is(Flags.Package) || symbol.flags.is(Flags.Param) ||
                locals.exists(_.equals(symbol)) || aliases.exists(_.equals(symbol)) ||
                modules.exists(_.equals(symbol)) || approved(symbol)

        /** The symbol's name as a reader writes it: an object's member is `Object.member`, not `Object$.member`. */
        def display(symbol: Symbol): String =
            val owner = symbol.owner
            if owner.isNoSymbol || owner.flags.is(Flags.Package) then symbol.fullName
            else
                val named = if owner.flags.is(Flags.Module) && owner.isClassDef then owner.companionModule else owner
                s"${display(named)}.${symbol.name}"
            end if
        end display

        def check(symbol: Symbol, tree: Tree): Unit =
            if !approved(symbol) then
                report.errorAndAbort(s"API is not admitted in .kyo: ${display(symbol)}", tree.pos)

        val visitor = new TreeTraverser:
            override def traverseTree(tree: Tree)(owner: Symbol): Unit = tree match
                case Literal(NullConstant())                            => report.errorAndAbort("Null is not supported in .kyo", tree.pos)
                case Inlined(Some(call), _, _) if approved(call.symbol) =>
                    traverseTree(call)(owner)
                // The synthetic method of a closure is visited as a statement of the enclosing block.
                case _: Closure                                                                                                => ()
                case definition: ValDef if definition.symbol.flags.is(Flags.Mutable) || definition.symbol.flags.is(Flags.Lazy) =>
                    report.errorAndAbort("Mutable and lazy locals are not supported in .kyo", definition.pos)
                case application: Apply =>
                    check(application.fun.symbol, application)
                    super.traverseTree(tree)(owner)
                case reference: Ref if reference.symbol.flags.is(Flags.Method) =>
                    check(reference.symbol, reference)
                    super.traverseTree(tree)(owner)
                case selection: Select if selection.symbol.flags.is(Flags.Mutable) =>
                    report.errorAndAbort("Mutable fields cannot be read in .kyo", selection.pos)
                case reference: Ref if reference.isExpr && !approvedReference(reference.symbol) =>
                    report.errorAndAbort(s"Reference is not admitted in .kyo: ${display(reference.symbol)}", reference.pos)
                case _ => super.traverseTree(tree)(owner)
        visitor.traverseTree(value.asTerm)(Symbol.spliceOwner)
    end apply
end Validate
