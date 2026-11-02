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
  * conformance programs use, the evidence derivations their inline expansions insert, and a few total operations on `Int`, `Boolean`,
  * `String`, functions, tuples, `Option`, string interpolation, and `List` with its `WithFilter`. The design replaces the table with
  * the package rule for `kyo.*` without `AllowUnsafe` plus an exact external allowlist; the table is the mechanism's evidence, not the
  * language's standard library.
  */
private[internal] object Validate:

    def apply[A](value: Expr[A])(using Quotes): Unit =
        import quotes.reflect.*
        val checked       = TypeRepr.of[kyo.lang.Checked].typeSymbol
        val moduleMethods = List(
            "kyo.lang.internal.Markers"       -> List("force"),
            "kyo.Var"                         -> List("get", "update", "updateDiscard"),
            "kyo.Abort"                       -> List("fail", "run"),
            "kyo.Frame"                       -> List("derive"),
            "kyo.Tag"                         -> List("apply", "derive"),
            "scala.Predef"                    -> List("identity", "summon"),
            "scala.StringContext"             -> List("apply"),
            "scala.Some"                      -> List("apply", "unapply"),
            "scala.None"                      -> Nil,
            "scala.Tuple2"                    -> List("apply", "unapply"),
            "scala.Tuple3"                    -> List("apply", "unapply"),
            "scala.NamedTuple"                -> List("build", "apply"),
            "scala.collection.immutable.List" -> List("apply")
        )
        val classMethods = List(
            "scala.Int"                       -> List("+", "-", "*", "%", ">", "<", ">=", "<=", "==", "!=", "unary_-"),
            "scala.Boolean"                   -> List("&&", "||", "unary_!"),
            "java.lang.String"                -> List("length"),
            "scala.StringContext"             -> List("s"),
            "scala.Some"                      -> List("<init>"),
            "scala.Tuple2"                    -> List("_1", "_2"),
            "scala.Function1"                 -> List("apply"),
            "scala.Function2"                 -> List("apply"),
            "scala.ContextFunction1"          -> List("apply"),
            "scala.collection.immutable.List" -> List("map", "flatMap", "filter", "withFilter", "foreach"),
            "scala.collection.WithFilter"     -> List("map", "flatMap", "foreach", "withFilter")
        )
        val methods = moduleMethods.flatMap { (module, names) =>
            val symbol = Symbol.requiredModule(module)
            names.flatMap(symbol.methodMember)
        } ::: classMethods.flatMap { (cls, names) =>
            val symbol = Symbol.requiredClass(cls)
            names.flatMap(name => if name == "<init>" then List(symbol.primaryConstructor) else symbol.methodMember(name))
        }

        def approved(symbol: Symbol): Boolean =
            methods.exists(_.equals(symbol)) || symbol.hasAnnotation(checked)

        val modules = moduleMethods.map((module, _) => Symbol.requiredModule(module))
        val aliases = List(Symbol.requiredModule("scala.package").fieldMember("List"))

        /** A parameter or a local value: a definition owned by a method or value rather than by a class or package. The body's own
          * definitions are validated where they are defined, so a reference to one is admitted by its owner alone; the original call
          * tree kept by an inlined marker refers to the definitions' symbols as they were before the macro re-owned them, which is why
          * the test is on the owner's kind and not on symbol identity.
          */
        def local(symbol: Symbol): Boolean = symbol.owner.isTerm

        def approvedReference(symbol: Symbol): Boolean =
            symbol.flags.is(Flags.Package) || local(symbol) || aliases.exists(_.equals(symbol)) ||
                modules.exists(_.equals(symbol)) || approved(symbol)

        /** The symbol's name as a reader writes it: an object's member is `Object.member`, not `Object$.member`. */
        def display(symbol: Symbol): String =
            val owner = symbol.owner
            if owner.isNoSymbol || owner.isTerm then symbol.name
            else if owner.flags.is(Flags.Package) then symbol.fullName
            else
                val named = if owner.flags.is(Flags.Module) && owner.isClassDef then owner.companionModule else owner
                s"${display(named)}.${symbol.name}"
            end if
        end display

        def check(symbol: Symbol, tree: Tree): Unit =
            if !local(symbol) && !approved(symbol) then
                report.errorAndAbort(s"API is not admitted in .kyo: ${display(symbol)}", tree.pos)

        /** A pattern: its extractors and stable identifiers are checked, its binders and type tests are the compiler's. */
        def pattern(tree: Tree): Unit = tree match
            case Unapply(extractor, _, patterns) =>
                check(extractor.symbol, extractor)
                patterns.foreach(pattern)
            case Bind(_, body)                                          => pattern(body)
            case Typed(body, _)                                         => pattern(body)
            case Alternatives(patterns)                                 => patterns.foreach(pattern)
            case Wildcard()                                             => ()
            case reference: Ref if !approvedReference(reference.symbol) =>
                report.errorAndAbort(s"Reference is not admitted in .kyo: ${display(reference.symbol)}", reference.pos)
            case _ => ()

        /** A case lambda typed as a partial function: admitted only as the direct argument of an admitted call, whose contract
          * decides how the uncovered cases are treated; stored or returned, its partiality would be a hidden failure.
          */
        def partialFunction(tree: Tree): Boolean = tree match
            case Block(List(_: DefDef), closure: Closure) => closure.tpeOpt.exists(_ <:< TypeRepr.of[PartialFunction[?, ?]])
            case Inlined(_, Nil, inner)                   => partialFunction(inner)
            case _                                        => false

        val visitor = new TreeTraverser:
            override def traverseTree(tree: Tree)(owner: Symbol): Unit = tree match
                case Literal(NullConstant()) => report.errorAndAbort("Null is not supported in .kyo", tree.pos)
                case application: Apply if application.args.exists(partialFunction) =>
                    check(application.fun.symbol, application)
                    traverseTree(application.fun)(owner)
                    application.args.foreach {
                        case Block(List(method: DefDef), _)                  => traverseTree(method)(owner)
                        case Inlined(_, Nil, Block(List(method: DefDef), _)) => traverseTree(method)(owner)
                        case argument                                        => traverseTree(argument)(owner)
                    }
                case tree if partialFunction(tree) =>
                    report.errorAndAbort("Partial function values are not supported in .kyo; pass the cases to a method directly", tree.pos)
                case CaseDef(pat, guard, body) =>
                    pattern(pat)
                    guard.foreach(traverseTree(_)(owner))
                    traverseTree(body)(owner)
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
