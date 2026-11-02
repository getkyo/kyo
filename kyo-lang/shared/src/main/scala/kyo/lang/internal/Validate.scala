package kyo.lang.internal

import scala.quoted.*

/** Validates the shape of a `.kyo` body: the language restrictions that do not depend on which API is called.
  *
  *   - no `null`;
  *   - no mutable or lazy locals, and no reads of mutable fields;
  *   - no partial-function values: a case lambda is admitted only as the direct argument of a call, whose contract decides how the
  *     uncovered cases are treated; stored or returned, its partiality would be a hidden failure.
  *
  * Which APIs a body may call is decided after typing by the plugin's `Admission` phase, which reuses the compiler's notion of safe
  * code; it is not a concern of this macro.
  */
private[internal] object Validate:

    def apply[A](value: Expr[A])(using Quotes): Unit =
        import quotes.reflect.*

        def partialFunction(tree: Tree): Boolean = tree match
            case Block(List(_: DefDef), closure: Closure) => closure.tpeOpt.exists(_ <:< TypeRepr.of[PartialFunction[?, ?]])
            case Inlined(_, Nil, inner)                   => partialFunction(inner)
            case _                                        => false

        val visitor = new TreeTraverser:
            override def traverseTree(tree: Tree)(owner: Symbol): Unit = tree match
                case Literal(NullConstant()) => report.errorAndAbort("Null is not supported in .kyo", tree.pos)
                case application: Apply if application.args.exists(partialFunction) =>
                    traverseTree(application.fun)(owner)
                    application.args.foreach {
                        case Block(List(method: DefDef), _)                  => traverseTree(method)(owner)
                        case Inlined(_, Nil, Block(List(method: DefDef), _)) => traverseTree(method)(owner)
                        case argument                                        => traverseTree(argument)(owner)
                    }
                case tree if partialFunction(tree) =>
                    report.errorAndAbort("Partial function values are not supported in .kyo; pass the cases to a method directly", tree.pos)
                // The synthetic method of a closure is visited as a statement of the enclosing block.
                case _: Closure                                                                                                => ()
                case definition: ValDef if definition.symbol.flags.is(Flags.Mutable) || definition.symbol.flags.is(Flags.Lazy) =>
                    report.errorAndAbort("Mutable and lazy locals are not supported in .kyo", definition.pos)
                case selection: Select if selection.symbol.flags.is(Flags.Mutable) =>
                    report.errorAndAbort("Mutable fields cannot be read in .kyo", selection.pos)
                case _ => super.traverseTree(tree)(owner)
        visitor.traverseTree(value.asTerm)(Symbol.spliceOwner)
    end apply
end Validate
