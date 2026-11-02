package kyo.internal

import kyo.*
import kyo.kernel.Loop
import scala.quoted.*

/** Lowers a typed body with markers into Kyo composition.
  *
  * The input is the body of a boundary (a `direct` block, a `.kyo` definition) as the typer left it, with sequencing and pass-through
  * markers inside. The output is a term of type `A < S`, where `S` is the row recorded by the sequencing markers of this boundary.
  * Pass-through markers are erased first, wherever they appear. Then each construct containing a sequencing marker is rewritten by
  * the rules of the design (kyo-direct/DESIGN.md): a marker becomes a `map` over its computation with the rest of the body as the
  * continuation; blocks bind in order; conditionals, boolean operators, and matches join their branches into one computation that is
  * bound once; loops become `Loop`; applications evaluate receiver and arguments in source order. A subtree without a sequencing
  * marker is left as it is.
  *
  * Every generated `map` and `Loop` receives a `Frame` built from the position of the marker or construct it stands for, never from
  * implicit derivation. Every subtree moved into a generated lambda is re-owned to it.
  */
private[kyo] object Lower:

    def apply[A: Type](body: Expr[A], markers: Markers, policy: Policy)(using Quotes): Expr[Any] =
        import quotes.reflect.*
        val erased = erase(body.asTerm, markers)
        rowOf(erased, markers) match
            case Absent =>
                if policy.liftPure then '{ Kyo.lift[A, Any](${ erased.asExprOf[A] }) } else erased.asExpr
            case Present(row) =>
                policy.pureOnly.foreach(message => report.errorAndAbort(message, body.asTerm.pos))
                // A marker whose computation has no effects still sequences: its row is `Any`.
                row.foldLeft(TypeRepr.of[Any])((left, right) => AndType(left, right)).simplified.asType match
                    case '[s] => new Boundary[A, s](markers, policy).lower(erased.asExpr)
        end match
    end apply

    /** Replaces every pass-through marker by the computation it wraps, in every position of the tree. */
    private def erase(using Quotes)(tree: quotes.reflect.Term, markers: Markers): quotes.reflect.Term =
        import quotes.reflect.*
        val mapper = new TreeMap:
            override def transformTerm(term: Term)(owner: Symbol): Term =
                markers.passThrough(term) match
                    case Present(inner) => transformTerm(inner)(owner)
                    case Absent         => super.transformTerm(term)(owner)
        mapper.transformTerm(tree)(Symbol.spliceOwner)
    end erase

    /** The rows recorded by the sequencing markers of the tree, split and deduplicated, in source order; `Absent` when the tree has no
      * sequencing marker at all, as opposed to markers whose rows are all `Any`.
      */
    private def rowOf(using Quotes)(tree: quotes.reflect.Term, markers: Markers): Maybe[List[quotes.reflect.TypeRepr]] =
        import quotes.reflect.*
        val accumulator = new TreeAccumulator[Maybe[List[TypeRepr]]]:
            def foldTree(rows: Maybe[List[TypeRepr]], child: Tree)(owner: Symbol): Maybe[List[TypeRepr]] =
                child match
                    case term: Term =>
                        markers.sequencing(term) match
                            case Present((computation, _, row)) => foldTree(Present(row :: rows.getOrElse(Nil)), computation)(owner)
                            case Absent                         => foldOverTree(rows, child)(owner)
                    case _ => foldOverTree(rows, child)(owner)
        def split(row: TypeRepr): List[TypeRepr] = row.dealias match
            case AndType(left, right) => split(left) ::: split(right)
            case other                => List(other)
        accumulator.foldTree(Absent, tree)(Symbol.spliceOwner).map { found =>
            found.reverse.flatMap(split).foldLeft(List.empty[TypeRepr]) { (seen, row) =>
                if row =:= TypeRepr.of[Any] || seen.exists(_ =:= row) then seen else seen :+ row
            }
        }
    end rowOf

    /** One boundary's lowering, for a body of type `A` and a row `S`. */
    final private class Boundary[A: Type, S: Type](markers: Markers, policy: Policy)(using Quotes):
        import quotes.reflect.*

        /** Where a lowered term flows: a function building the rest of the boundary from the term, and the type that rest produces. The
          * final continuation of a boundary lifts into `A < S`; a join point of a branch construct lifts into the construct's own type.
          */
        final private class Cont(val result: TypeRepr, val isFinal: Boolean)(continue: Term => Term):
            def apply(term: Term): Term = continue(term)

        /** References to the locals lowering rebinds, from their original symbol to the fresh local. */
        private type Environment = Map[Symbol, Term]

        def lower(body: Expr[Any]): Expr[Any] =
            val finalContinuation = new Cont(TypeRepr.of[A], isFinal = true)(term => '{ Kyo.lift[A, S](${ term.asExprOf[A] }) }.asTerm)
            lowerTerm(body.asTerm, Map.empty)(finalContinuation).asExpr
        end lower

        private def cont(result: TypeRepr)(continue: Term => Term): Cont = new Cont(result, isFinal = false)(continue)

        private def lift(result: TypeRepr): Cont =
            result.asType match
                case '[r] => new Cont(result, isFinal = false)(term => '{ Kyo.lift[r, S](${ term.asExprOf[r] }) }.asTerm)

        private def frameAt(tree: Tree): Expr[Frame] = Frame.fromPosition(tree.pos)

        private def hasMarker(tree: Tree): Boolean =
            val accumulator = new TreeAccumulator[Boolean]:
                def foldTree(found: Boolean, child: Tree)(owner: Symbol): Boolean =
                    found ||
                        (child match
                            case term: Term if markers.sequencing(term).isDefined => true
                            case _                                                => foldOverTree(false, child)(owner))
            accumulator.foldTree(false, tree)(Symbol.spliceOwner)
        end hasMarker

        /** A term whose evaluation has no effect and no cost worth a binding: references, literals, stable paths, closures. */
        private def trivial(term: Term): Boolean = term match
            case _: Ident | _: Literal | _: This    => true
            case Typed(inner, _)                    => trivial(inner)
            case Block(List(_: DefDef), _: Closure) => true
            case Inlined(_, Nil, inner)             => trivial(inner)
            case _ if term.tpe.isSingleton          => true
            case _                                  => false

        /** Rebinds references to lowered locals and re-owns definitions to the current generated scope. */
        private def rebind(tree: Tree, environment: Environment): Tree =
            val mapper = new TreeMap:
                override def transformTerm(term: Term)(owner: Symbol): Term = term match
                    case id: Ident => environment.getOrElse(id.symbol, id)
                    case _         => super.transformTerm(term)(owner)
            val rebound = if environment.isEmpty then tree else mapper.transformTree(tree)(Symbol.spliceOwner)
            rebound.changeOwner(Symbol.spliceOwner)
        end rebind

        private def rebindTerm(term: Term, environment: Environment): Term =
            rebind(term, environment).asInstanceOf[Term]

        /** Binds a computation and continues with its exposed result: `computation.map(x => k(x))`. */
        private def bind(computation: Term, exposed: TypeRepr, frame: Expr[Frame], environment: Environment)(k: Cont): Term =
            exposed.asType match
                case '[a] =>
                    k.result.asType match
                        case '[r] =>
                            val op = computation.asExprOf[a < S]
                            '{ $op.map[r, S]((x: a) => ${ k(('x).asTerm).asExprOf[r < S] })(using $frame) }.asTerm

        /** Introduces a fresh local for a value that must be evaluated at this point, before the rest of type `result < S`. Built with a
          * quote so the local and its references carry positions, which the compiler asserts when it retypes generated code.
          */
        private def let(value: Term, result: TypeRepr)(body: Term => Term): Term =
            value.tpe.widen.asType match
                case '[v] =>
                    result.asType match
                        case '[r] =>
                            '{
                                val local: v = ${ value.asExprOf[v] }
                                ${ body(('local).asTerm).asExprOf[r < S] }
                            }.asTerm

        private def lowerTerm(tree: Term, environment: Environment)(k: Cont): Term =
            if !hasMarker(tree) then k(rebindTerm(tree, environment))
            else
                markers.sequencing(tree) match
                    case Present((computation, exposed, _)) =>
                        lowerTerm(computation, environment)(cont(k.result)(bound =>
                            bind(bound, exposed, frameAt(tree), environment)(k)
                        ))
                    case Absent =>
                        tree match
                            case Inlined(_, bindings, expansion) =>
                                lowerBlock(bindings, expansion, environment)(k)
                            case Block(List(_: DefDef), _: Closure) =>
                                report.errorAndAbort("A lambda containing an effect marker needs its own `direct` block", tree.pos)
                            case Block(statements, expression) =>
                                lowerBlock(statements, expression, environment)(k)
                            case If(condition, thenBranch, elseBranch) =>
                                lowerIf(tree, condition, thenBranch, elseBranch, environment)(k)
                            case Apply(select @ Select(left, name), List(right)) if isBooleanOperator(select) && hasMarker(right) =>
                                lowerBoolean(tree, left, right, name == "&&", environment)(k)
                            case Match(scrutinee, cases) =>
                                lowerMatch(tree, scrutinee, cases, environment)(k)
                            case While(condition, body) =>
                                lowerWhile(tree, condition, body, environment)(k)
                            case application: Apply =>
                                lowerCallee(application, markersFollow = false, environment)(k)
                            case _: TypeApply =>
                                lowerCallee(tree, markersFollow = false, environment)(k)
                            case selection: Select =>
                                lowerCallee(selection, markersFollow = false, environment)(k)
                            case Typed(expression, tpt) =>
                                lowerTerm(expression, environment)(cont(k.result)(term =>
                                    k(Typed.copy(tree)(term, tpt))
                                ))
                            case NamedArg(name, argument) =>
                                lowerTerm(argument, environment)(cont(k.result)(term =>
                                    k(NamedArg.copy(tree)(name, term))
                                ))
                            case Repeated(elements, tpt) =>
                                lowerSequence(elements, environment, k.result)(terms => k(Repeated.copy(tree)(terms, tpt)))
                            case _ =>
                                report.errorAndAbort(
                                    s"Effect markers are not supported in this position: ${tree.getClass.getSimpleName}",
                                    tree.pos
                                )
                end match
        end lowerTerm

        private def isBooleanOperator(select: Select): Boolean =
            val symbol = select.symbol
            (symbol.name == "&&" || symbol.name == "||") && symbol.owner.equals(TypeRepr.of[Boolean].typeSymbol)

        // --- Blocks ---

        private def lowerBlock(statements: List[Statement], expression: Term, environment: Environment)(k: Cont): Term =
            statements match
                case Nil                          => lowerTerm(expression, environment)(k)
                case (definition: ValDef) :: rest =>
                    definition.rhs match
                        case Some(rhs) if hasMarker(rhs) =>
                            lowerTerm(rhs, environment)(cont(k.result)(bound =>
                                let(bound, k.result)(local =>
                                    lowerBlock(rest, expression, environment.updated(definition.symbol, local))(k)
                                )
                            ))
                        case Some(_) =>
                            Block(
                                List(rebind(definition, environment).asInstanceOf[Statement]),
                                lowerBlock(rest, expression, environment)(k)
                            )
                        case None =>
                            report.errorAndAbort("A local value needs an initializer", definition.pos)
                case (statement: Term) :: rest =>
                    if hasMarker(statement) then
                        lowerTerm(statement, environment)(cont(k.result)(_ =>
                            lowerBlock(rest, expression, environment)(k)
                        ))
                    else
                        Block(List(rebindTerm(statement, environment)), lowerBlock(rest, expression, environment)(k))
                case definition :: rest =>
                    if hasMarker(definition) then
                        report.errorAndAbort(
                            "A local definition containing an effect marker is not supported; move it out of the block",
                            definition.pos
                        )
                    end if
                    Block(List(rebind(definition, environment).asInstanceOf[Statement]), lowerBlock(rest, expression, environment)(k))
        end lowerBlock

        // --- Branches: one continuation, emitted once, after a join ---

        private def lowerIf(tree: Term, condition: Term, thenBranch: Term, elseBranch: Term, environment: Environment)(k: Cont): Term =
            lowerTerm(condition, environment)(cont(k.result) { decided =>
                if k.isFinal then
                    If.copy(tree)(decided, lowerTerm(thenBranch, environment)(k), lowerTerm(elseBranch, environment)(k))
                else
                    val joined = lift(tree.tpe.widen)
                    val join   =
                        If.copy(tree)(decided, lowerTerm(thenBranch, environment)(joined), lowerTerm(elseBranch, environment)(joined))
                    bind(join, tree.tpe.widen, frameAt(tree), environment)(k)
            })

        private def lowerBoolean(tree: Term, left: Term, right: Term, conjunction: Boolean, environment: Environment)(k: Cont): Term =
            lowerTerm(left, environment)(cont(k.result) { decided =>
                val boolean = TypeRepr.of[Boolean]
                val test    = decided.asExprOf[Boolean]
                val rest    = lowerTerm(right, environment)(lift(boolean)).asExprOf[Boolean < S]
                val join    =
                    if conjunction then '{ if $test then $rest else Kyo.lift[Boolean, S](false) }
                    else '{ if $test then Kyo.lift[Boolean, S](true) else $rest }
                bind(join.asTerm, boolean, frameAt(tree), environment)(k)
            })

        private def lowerMatch(tree: Term, scrutinee: Term, cases: List[CaseDef], environment: Environment)(k: Cont): Term =
            cases.foreach(_.guard.foreach { guard =>
                if hasMarker(guard) then
                    report.errorAndAbort("An effect marker in a pattern guard is not supported; bind the value before the match", guard.pos)
            })
            lowerTerm(scrutinee, environment)(cont(k.result) { selector =>
                def lowered(continuation: Cont): Term =
                    val rebuilt = cases.map { caseDef =>
                        CaseDef.copy(caseDef)(caseDef.pattern, caseDef.guard, lowerTerm(caseDef.rhs, environment)(continuation))
                    }
                    Match.copy(tree)(selector, rebuilt).changeOwner(Symbol.spliceOwner)
                end lowered
                if k.isFinal then lowered(k)
                else bind(lowered(lift(tree.tpe.widen)), tree.tpe.widen, frameAt(tree), environment)(k)
            })
        end lowerMatch

        // --- Loops ---

        private def lowerWhile(tree: Term, condition: Term, body: Term, environment: Environment)(k: Cont): Term =
            val outcome = TypeRepr.of[Loop.Outcome[Unit, Unit]]
            val step    = lowerTerm(condition, environment)(cont(outcome) { decided =>
                val continue = cont(outcome)(_ => '{ Loop.continue[Unit] }.asTerm)
                val repeat   = lowerTerm(body, environment)(continue).asExprOf[Loop.Outcome[Unit, Unit] < S]
                '{ if ${ decided.asExprOf[Boolean] } then $repeat else Loop.done[Unit] }.asTerm
            })
            val frame = frameAt(tree)
            val loop  = '{ Loop[Unit, Unit, S](())((_: Unit) => ${ step.asExprOf[Loop.Outcome[Unit, Unit] < S] })(using $frame) }.asTerm
            bind(loop, TypeRepr.of[Unit], frame, environment)(k)
        end lowerWhile

        // --- Applications: receiver first, then arguments in order ---

        /** Lowers the function position of an application chain. `markersFollow` says whether an argument later in the chain has a
          * marker, in which case pure receivers and arguments before it are bound to keep their evaluation position.
          */
        private def lowerCallee(tree: Term, markersFollow: Boolean, environment: Environment)(k: Cont): Term =
            tree match
                case application @ Apply(function, arguments) =>
                    val parameters = function.tpe.widen match
                        case method: MethodType => method.paramTypes
                        case _                  => arguments.map(_ => TypeRepr.of[Any])
                    val follows = markersFollow || arguments.exists(hasMarker)
                    lowerCallee(function, follows, environment)(cont(k.result) { callee =>
                        lowerArguments(arguments, parameters, environment, markersFollow, k.result)(terms =>
                            k(Apply.copy(application)(callee, terms))
                        )
                    })
                case typeApplication @ TypeApply(function, arguments) =>
                    lowerCallee(function, markersFollow, environment)(cont(k.result)(callee =>
                        k(TypeApply.copy(typeApplication)(callee, arguments))
                    ))
                case selection @ Select(qualifier, name) =>
                    if hasMarker(qualifier) then
                        lowerTerm(qualifier, environment)(cont(k.result)(bound =>
                            k(Select.copy(selection)(bound, name))
                        ))
                    else if markersFollow && !trivial(qualifier) then
                        let(rebindTerm(qualifier, environment), k.result)(local => k(Select.copy(selection)(local, name)))
                    else k(rebindTerm(selection, environment))
                case other =>
                    if hasMarker(other) then lowerTerm(other, environment)(k)
                    else if markersFollow && !trivial(other) then
                        let(rebindTerm(other, environment), k.result)(local => k(local))
                    else k(rebindTerm(other, environment))
        end lowerCallee

        private def lowerArguments(
            arguments: List[Term],
            parameters: List[TypeRepr],
            environment: Environment,
            markersFollow: Boolean,
            result: TypeRepr
        )(k: List[Term] => Term): Term =
            val lastMarker                                      = arguments.lastIndexWhere(hasMarker)
            def loop(index: Int, accumulated: List[Term]): Term =
                if index == arguments.length then k(accumulated.reverse)
                else
                    val argument = arguments(index)
                    val byName   = parameters.lift(index) match
                        case Some(ByNameType(_)) => true
                        case _                   => false
                    if byName then
                        if hasMarker(argument) then
                            report.errorAndAbort(
                                "An effect marker inside a by-name argument is not supported; bind the value first",
                                argument.pos
                            )
                        end if
                        loop(index + 1, rebindTerm(argument, environment) :: accumulated)
                    else if hasMarker(argument) then
                        lowerTerm(argument, environment)(cont(result)(bound => loop(index + 1, bound :: accumulated)))
                    else if (index < lastMarker || markersFollow) && !trivial(argument) then
                        let(rebindTerm(argument, environment), result)(local => loop(index + 1, local :: accumulated))
                    else loop(index + 1, rebindTerm(argument, environment) :: accumulated)
                    end if
            loop(0, Nil)
        end lowerArguments

        private def lowerSequence(elements: List[Term], environment: Environment, result: TypeRepr)(k: List[Term] => Term): Term =
            lowerArguments(elements, elements.map(_ => TypeRepr.of[Any]), environment, markersFollow = false, result)(k)
    end Boundary
end Lower
