package kyo.internal

import kyo.*
import kyo.kernel.Loop
import scala.collection.WithFilter
import scala.quoted.*

/** Lowers the body of a `direct` block into Kyo composition.
  *
  * The input is the typed body with its `.now` and `.later` markers. The output is a term of type `A < S`, where `S` is the row recorded
  * by the `.now` markers of this block. `.later` is erased first, wherever it appears. Then each construct containing a `.now` is
  * rewritten (see kyo-direct/DESIGN.md): a `.now` becomes a `map` over its computation with the rest of the block as the continuation;
  * blocks bind in order; conditionals, boolean operators, and matches join their branches into one computation bound once; `while`
  * becomes a stack-safe `Loop`; applications evaluate receiver and arguments in source order; a standard-library method whose callback
  * contains a `.now` becomes its Kyo counterpart. A subtree without a `.now` is left as it is.
  *
  * Locals keep their own symbols: a `val` bound from a `.now` is redefined, with the same symbol, inside the continuation, so references
  * to it need no rewriting. The body, and the body of every callback the lowering dissolves, is first re-owned to the macro, so that
  * everything the lowering emits or keeps has the one owner the quotes move to its final place. Every generated `map`, `Loop`, and
  * counterpart call receives a `Frame` built from the position of the marker or construct it stands for.
  */
private[kyo] object Lower:

    /** The lowered block. Its type is the precise `A < S`; `direct` is transparent, so that is the type the caller sees. */
    def apply[A: Type](body: Expr[A])(using Quotes): Expr[Any] =
        import quotes.reflect.*
        val erased = erase(body.asTerm)
        rowOf(erased) match
            case None      => '{ Kyo.lift[A, Any](${ erased.asExprOf[A] }) }
            case Some(row) =>
                // A `.now` on a computation with no effects still sequences: its row is `Any`.
                row.foldLeft(TypeRepr.of[Any])(AndType(_, _)).simplified.asType match
                    case '[s] => new Lowering[A, s].apply(erased.asExpr)
        end match
    end apply

    /** Replaces every `.later` by the computation it wraps, in every position of the tree. */
    private def erase(using Quotes)(tree: quotes.reflect.Term): quotes.reflect.Term =
        import quotes.reflect.*
        val mapper = new TreeMap:
            override def transformTerm(term: Term)(owner: Symbol): Term =
                DirectSyntax.later(term) match
                    case Some(inner) => transformTerm(inner)(owner)
                    case None        => super.transformTerm(term)(owner)
        mapper.transformTerm(tree)(Symbol.spliceOwner)
    end erase

    /** The rows recorded by the `.now` markers of the tree, split and deduplicated in source order; `None` when there is no marker. */
    private def rowOf(using Quotes)(tree: quotes.reflect.Term): Option[List[quotes.reflect.TypeRepr]] =
        import quotes.reflect.*
        val accumulator = new TreeAccumulator[Option[List[TypeRepr]]]:
            def foldTree(rows: Option[List[TypeRepr]], child: Tree)(owner: Symbol): Option[List[TypeRepr]] =
                DirectSyntax.now(child) match
                    case Some((computation, _, row)) => foldTree(Some(row :: rows.getOrElse(Nil)), computation)(owner)
                    case None                        => foldOverTree(rows, child)(owner)
        def split(row: TypeRepr): List[TypeRepr] = row.dealias match
            case AndType(left, right) => split(left) ::: split(right)
            case other                => List(other)
        accumulator.foldTree(None, tree)(Symbol.spliceOwner).map { found =>
            found.reverse.flatMap(split).foldLeft(List.empty[TypeRepr]) { (seen, row) =>
                if row =:= TypeRepr.of[Any] || seen.exists(_ =:= row) then seen else seen :+ row
            }
        }
    end rowOf

    /** The lowering of one block, for a body of type `A` and a row `S`. */
    final private class Lowering[A: Type, S: Type](using val quotes: Quotes):
        import quotes.reflect.*

        /** The rest of the block after a lowered term: a function from the term to the rest, and the type the rest produces. The final
          * continuation lifts into `A < S`; the join of a branch construct lifts into the construct's own type.
          */
        final private class Cont(val result: TypeRepr, val isFinal: Boolean)(continue: Term => Term):
            def apply(term: Term): Term = continue(term)

        def apply(body: Expr[Any]): Expr[A < S] =
            val end = new Cont(TypeRepr.of[A], isFinal = true)(term => '{ Kyo.lift[A, S](${ term.asExprOf[A] }) }.asTerm)
            lower(owned(body.asTerm))(end).asExprOf[A < S]

        private def cont(result: TypeRepr)(continue: Term => Term): Cont = new Cont(result, isFinal = false)(continue)

        private def lift(result: TypeRepr): Cont =
            result.asType match
                case '[r] => cont(result)(term => '{ Kyo.lift[r, S](${ term.asExprOf[r] }) }.asTerm)

        private def frameAt(tree: Tree): Expr[Frame] = Frame.fromPosition(tree.pos)

        private def hasNow(tree: Tree): Boolean =
            val accumulator = new TreeAccumulator[Boolean]:
                def foldTree(found: Boolean, child: Tree)(owner: Symbol): Boolean =
                    found || DirectSyntax.now(child).isDefined || foldOverTree(false, child)(owner)
            accumulator.foldTree(false, tree)(Symbol.spliceOwner)
        end hasNow

        /** A term whose evaluation has no effect and no cost worth a binding: references, literals, stable paths, closures. */
        private def trivial(term: Term): Boolean = term match
            case _: Ident | _: Literal | _: This    => true
            case Typed(inner, _)                    => trivial(inner)
            case Block(List(_: DefDef), _: Closure) => true
            case Inlined(_, Nil, inner)             => trivial(inner)
            case _                                  => term.tpe.isSingleton

        /** The tree with the definitions it holds re-owned to the macro: every definition the lowering emits or keeps is owned by the
          * macro while the tree is built, so a spliced tree has one owner for the quote to move to its place.
          */
        private def owned[T <: Tree](tree: T): T = tree.changeOwner(Symbol.spliceOwner)

        /** Replaces references to the parameters of a rebuilt lambda by references to the new ones. */
        private def substitute(tree: Term, replacements: Map[Symbol, Symbol]): Term =
            val mapper = new TreeMap:
                override def transformTerm(term: Term)(owner: Symbol): Term = term match
                    case id: Ident if replacements.contains(id.symbol) => Ref(replacements(id.symbol))
                    case _                                             => super.transformTerm(term)(owner)
            mapper.transformTerm(tree)(Symbol.spliceOwner)
        end substitute

        /** Binds a computation and continues with its exposed result: `computation.map(x => k(x))`. */
        private def bind(computation: Term, exposed: TypeRepr, frame: Expr[Frame])(k: Cont): Term =
            exposed.asType match
                case '[a] =>
                    k.result.asType match
                        case '[r] =>
                            val op = computation.asExprOf[a < S]
                            '{ $op.map[r, S]((x: a) => ${ owned(k(('x).asTerm)).asExprOf[r < S] })(using $frame) }.asTerm

        /** Introduces a fresh local for a value that must be evaluated at this point, before the rest of type `result < S`. */
        private def let(value: Term, result: TypeRepr)(body: Term => Term): Term =
            value.tpe.widen.asType match
                case '[v] =>
                    result.asType match
                        case '[r] =>
                            '{
                                val local: v = ${ value.asExprOf[v] }
                                ${ owned(body(('local).asTerm)).asExprOf[r < S] }
                            }.asTerm

        private def lower(tree: Term)(k: Cont): Term =
            if !hasNow(tree) then k(tree)
            else
                DirectSyntax.now(tree) match
                    case Some((computation, exposed, _)) =>
                        lower(computation)(cont(k.result)(bound => bind(bound, exposed, frameAt(tree))(k)))
                    case None =>
                        tree match
                            case Inlined(_, bindings, expansion)    => lowerBlock(bindings, expansion)(k)
                            case Block(List(_: DefDef), _: Closure) =>
                                report.errorAndAbort("A lambda containing .now needs its own `direct` block", tree.pos)
                            case Block(statements, expression)         => lowerBlock(statements, expression)(k)
                            case If(condition, thenBranch, elseBranch) => lowerIf(tree, condition, thenBranch, elseBranch)(k)
                            case Apply(select @ Select(left, name), List(right)) if isBooleanOperator(select) && hasNow(right) =>
                                lowerBoolean(tree, left, right, name == "&&")(k)
                            case Match(scrutinee, cases)             => lowerMatch(tree, scrutinee, cases)(k)
                            case While(condition, body)              => lowerWhile(tree, condition, body)(k)
                            case Counterpart(call)                   => lowerCounterpart(tree, call)(k)
                            case _: Apply | _: TypeApply | _: Select => lowerCallee(tree, nowFollows = false)(k)
                            case Typed(expression, tpt)   => lower(expression)(cont(k.result)(term => k(Typed.copy(tree)(term, tpt))))
                            case NamedArg(name, argument) =>
                                lower(argument)(cont(k.result)(term => k(NamedArg.copy(tree)(name, term))))
                            case Repeated(elements, tpt) =>
                                lowerArguments(elements, Nil, nowFollows = false, k.result)(terms => k(Repeated.copy(tree)(terms, tpt)))
                            case _ =>
                                report.errorAndAbort(s".now is not supported in this position: ${tree.getClass.getSimpleName}", tree.pos)
                end match
        end lower

        private def isBooleanOperator(select: Select): Boolean =
            val symbol = select.symbol
            (symbol.name == "&&" || symbol.name == "||") && symbol.owner.equals(TypeRepr.of[Boolean].typeSymbol)

        // --- Blocks ---

        private def lowerBlock(statements: List[Statement], expression: Term)(k: Cont): Term =
            statements match
                case Nil => lower(expression)(k)
                // The definitions of the right-hand side leave the local's scope for the enclosing one, so they are re-owned first.
                case (definition @ ValDef(name, tpt, Some(rhs))) :: rest if hasNow(rhs) =>
                    lower(owned(rhs))(cont(k.result)(bound =>
                        Block(List(ValDef.copy(definition)(name, tpt, Some(bound))), lowerBlock(rest, expression)(k))
                    ))
                case (term: Term) :: rest if hasNow(term) =>
                    lower(term)(cont(k.result)(_ => lowerBlock(rest, expression)(k)))
                case statement :: rest =>
                    if hasNow(statement) then
                        report.errorAndAbort("A local definition containing .now is not supported; move it out of the block", statement.pos)
                    Block(List(statement), lowerBlock(rest, expression)(k))
        end lowerBlock

        // --- Branches: one continuation, emitted once, after a join ---

        private def lowerIf(tree: Term, condition: Term, thenBranch: Term, elseBranch: Term)(k: Cont): Term =
            lower(condition)(cont(k.result) { decided =>
                val branches = if k.isFinal then k else lift(tree.tpe.widen)
                val joined   = If.copy(tree)(decided, lower(thenBranch)(branches), lower(elseBranch)(branches))
                if k.isFinal then joined else bind(joined, tree.tpe.widen, frameAt(tree))(k)
            })

        private def lowerBoolean(tree: Term, left: Term, right: Term, conjunction: Boolean)(k: Cont): Term =
            lower(left)(cont(k.result) { decided =>
                val test   = decided.asExprOf[Boolean]
                val rest   = lower(right)(lift(TypeRepr.of[Boolean])).asExprOf[Boolean < S]
                val joined =
                    if conjunction then '{ if $test then $rest else Kyo.lift[Boolean, S](false) }
                    else '{ if $test then Kyo.lift[Boolean, S](true) else $rest }
                bind(joined.asTerm, TypeRepr.of[Boolean], frameAt(tree))(k)
            })

        private def lowerMatch(tree: Term, scrutinee: Term, cases: List[CaseDef])(k: Cont): Term =
            cases.flatMap(_.guard).filter(hasNow).foreach { guard =>
                report.errorAndAbort("A .now in a pattern guard is not supported; bind the value before the match", guard.pos)
            }
            lower(scrutinee)(cont(k.result) { selector =>
                val branches = if k.isFinal then k else lift(tree.tpe.widen)
                val rebuilt  = cases.map(c => CaseDef.copy(c)(c.pattern, c.guard, lower(c.rhs)(branches)))
                val joined   = Match.copy(tree)(selector, rebuilt)
                if k.isFinal then joined else bind(joined, tree.tpe.widen, frameAt(tree))(k)
            })
        end lowerMatch

        // --- Loops ---

        private def lowerWhile(tree: Term, condition: Term, body: Term)(k: Cont): Term =
            val outcome = TypeRepr.of[Loop.Outcome[Unit, Unit]]
            val step    = lower(condition)(cont(outcome) { decided =>
                val repeat = lower(body)(cont(outcome)(_ => '{ Loop.continue[Unit] }.asTerm))
                '{ if ${ decided.asExprOf[Boolean] } then ${ repeat.asExprOf[Loop.Outcome[Unit, Unit] < S] } else Loop.done[Unit] }.asTerm
            })
            val frame = frameAt(tree)
            val loop  = '{ Loop[Unit, Unit, S](())((_: Unit) => ${ owned(step).asExprOf[Loop.Outcome[Unit, Unit] < S] })(using $frame) }
            bind(loop.asTerm, TypeRepr.of[Unit], frame)(k)
        end lowerWhile

        // --- Applications: receiver first, then arguments in order ---

        /** Lowers the function position of an application chain. `nowFollows` says whether an argument later in the chain has a `.now`,
          * in which case pure receivers and arguments before it are bound to keep their evaluation position.
          */
        private def lowerCallee(tree: Term, nowFollows: Boolean)(k: Cont): Term =
            tree match
                case application @ Apply(function, arguments) =>
                    val parameters = function.tpe.widen match
                        case method: MethodType => method.paramTypes
                        case _                  => Nil
                    lowerCallee(function, nowFollows || arguments.exists(hasNow))(cont(k.result) { callee =>
                        lowerArguments(arguments, parameters, nowFollows, k.result)(terms => k(Apply.copy(application)(callee, terms)))
                    })
                case typeApplication @ TypeApply(function, arguments) =>
                    lowerCallee(function, nowFollows)(cont(k.result)(callee => k(TypeApply.copy(typeApplication)(callee, arguments))))
                case selection @ Select(qualifier, name) if hasNow(qualifier) =>
                    lower(qualifier)(cont(k.result)(bound => k(Select.copy(selection)(bound, name))))
                case selection @ Select(qualifier, name) if nowFollows && !trivial(qualifier) =>
                    let(qualifier, k.result)(local => k(Select.copy(selection)(local, name)))
                case other if hasNow(other)                 => lower(other)(k)
                case other if nowFollows && !trivial(other) => let(other, k.result)(k.apply)
                case other                                  => k(other)
        end lowerCallee

        /** Lowers arguments in order: one with a `.now` is bound before the next is evaluated; a pure one that precedes a `.now` is bound
          * to a local so it keeps its position; a by-name one stays delayed and may not contain a `.now`.
          */
        private def lowerArguments(arguments: List[Term], parameters: List[TypeRepr], nowFollows: Boolean, result: TypeRepr)(
            k: List[Term] => Term
        ): Term =
            val lastNow                                         = arguments.lastIndexWhere(hasNow)
            def loop(index: Int, accumulated: List[Term]): Term =
                if index == arguments.length then k(accumulated.reverse)
                else
                    val argument = arguments(index)
                    val byName   = parameters.lift(index) match
                        case Some(ByNameType(_)) => true
                        case _                   => false
                    if byName && hasNow(argument) then
                        report.errorAndAbort("A .now inside a by-name argument is not supported; bind the value first", argument.pos)
                    else if hasNow(argument) then lower(argument)(cont(result)(bound => loop(index + 1, bound :: accumulated)))
                    else if !byName && (index < lastNow || nowFollows) && !trivial(argument) then
                        let(argument, result)(local => loop(index + 1, local :: accumulated))
                    else loop(index + 1, argument :: accumulated)
                    end if
            loop(0, Nil)
        end lowerArguments

        // --- Counterparts: the callbacks run through the Kyo operation that replaces the method ---

        private val counterparts = Counterparts[quotes.type]()

        /** A call with a Kyo counterpart whose callbacks or by-name arguments contain a `.now`. */
        private object Counterpart:
            def unapply(tree: Term): Option[counterparts.Call] =
                counterparts.call(tree).filter(_.arguments.exists(_.exists(effectfulCallback)))

        private def effectfulCallback(argument: Term, parameter: TypeRepr): Boolean =
            lambda(argument) match
                case Some((definition, _)) => hasNow(definition)
                case None                  =>
                    parameter match
                        case ByNameType(_) => hasNow(argument)
                        case _             => false

        /** A lambda literal: its method and, for a partial-function literal, the function type the closure was given. */
        private def lambda(term: Term): Option[(DefDef, Option[TypeRepr])] = term match
            case Block(List(definition: DefDef), closure: Closure) => Some((definition, closure.tpeOpt))
            case Block(Nil, inner)                                 => lambda(inner)
            case Typed(inner, _)                                   => lambda(inner)
            case Inlined(_, Nil, inner)                            => lambda(inner)
            case _                                                 => None

        /** The receiver first, then the arguments in order: a callback becomes a lambda returning a computation, a by-name argument a
          * computation, a strict argument is lowered in place. The operation is applied to its type arguments explicitly, then to the
          * receiver, the argument lists, and the frame. The `Ordering` of `maxByOption` joins the frame in the using clause.
          */
        private def lowerCounterpart(tree: Term, call: counterparts.Call)(k: Cont): Term =
            val frame     = frameAt(tree)
            val arguments =
                call.arguments.zipWithIndex.flatMap((list, index) => list.map((argument, parameter) => (argument, parameter, index)))
            lower(call.receiver)(cont(k.result) { source =>
                def loop(rest: List[(Term, TypeRepr, Int)], done: List[(Term, Int)]): Term = rest match
                    case Nil =>
                        val lists =
                            call.arguments.indices.map(index => done.reverse.collect { case (term, i) if i == index => term }).toList
                        val (explicit, implicits) = if call.method == "maxByOption" then (lists.init, lists.last) else (lists, Nil)
                        val types                 = call.receiverTypes ::: call.methodTypes ::: List(TypeRepr.of[S])
                        val applied               = explicit
                            .foldLeft(
                                Ref(counterparts.kyo).select(call.operation).appliedToTypes(types).appliedTo(source)
                            )(_.appliedToArgs(_))
                            .appliedToArgs(frame.asTerm :: implicits)
                        val adapted = call.method match
                            case "find" | "collectFirst" | "maxByOption" => toOption(applied, tree.tpe.widen, frame)
                            case "withFilter"                            => withFilter(applied, call.receiverTypes, tree.tpe.widen, frame)
                            case _                                       => applied
                        bind(adapted, tree.tpe.widen, frame)(k)
                    case (argument, parameter, index) :: tail =>
                        lambda(argument) match
                            case Some((definition, closureType)) => loop(tail, (lowerLambda(definition, closureType), index) :: done)
                            case None                            =>
                                parameter match
                                    case ByNameType(underlying) => loop(tail, (lower(argument)(lift(underlying)), index) :: done)
                                    case _ => lower(argument)(cont(k.result)(value => loop(tail, (value, index) :: done)))
                loop(arguments, Nil)
            })
        end lowerCounterpart

        /** `Kyo` answers a `Maybe` where the standard library answers an `Option`. */
        private def toOption(computation: Term, result: TypeRepr, frame: Expr[Frame]): Term =
            result match
                case AppliedType(_, List(element)) =>
                    element.asType match
                        case '[a] => '{ ${ computation.asExprOf[Maybe[a] < S] }.map(_.toOption)(using $frame) }.asTerm
                case _ => report.errorAndAbort(s"Expected an Option, found ${result.show}")

        /** `withFilter` answers a lazy filter over the collection; the collection `Kyo.filter` produces is wrapped as the standard library
          * would wrap it, with a predicate that accepts everything.
          */
        private def withFilter(computation: Term, receiverTypes: List[TypeRepr], result: TypeRepr, frame: Expr[Frame]): Term =
            val collection = receiverTypes.head.appliedTo(receiverTypes.last)
            collection.asType match
                case '[c] =>
                    result.asType match
                        case '[w] =>
                            val wrap = Lambda(
                                Symbol.spliceOwner,
                                MethodType(List("filtered"))(_ => List(collection), _ => TypeRepr.of[w < S]),
                                (method, parameters) =>
                                    val accept = Lambda(
                                        method,
                                        MethodType(List("element"))(_ => List(receiverTypes.last), _ => TypeRepr.of[Boolean]),
                                        (_, _) => Literal(BooleanConstant(true))
                                    )
                                    val wrapped = Select.unique(parameters.head.asInstanceOf[Term], "withFilter").appliedTo(accept)
                                    '{ Kyo.lift[w, S](${ wrapped.asExprOf[w] }) }.asTerm.changeOwner(method)
                            )
                            '{ ${ computation.asExprOf[c < S] }.map[w, S](${ wrap.asExprOf[c => w < S] })(using $frame) }.asTerm
            end match
        end withFilter

        /** A lambda whose body returns a computation: `(p) => lowered body : B < S`. A partial-function literal becomes a total function
          * to `Maybe[B]`, present for the cases it covers and absent otherwise.
          */
        private def lowerLambda(definition: DefDef, closureType: Option[TypeRepr]): Term =
            val parameters = definition.termParamss.flatMap(_.params)
            val body       = definition.rhs.getOrElse(report.errorAndAbort("A lambda needs a body", definition.pos))
            val returnType = definition.returnTpt.tpe
            val partial    = closureType.exists(_ <:< TypeRepr.of[PartialFunction[?, ?]])
            val resultType = if partial then TypeRepr.of[Maybe].appliedTo(returnType) else returnType

            /** The partial function's match made total: covered cases yield `Maybe(value)`, everything else `Maybe.empty`. The
              * fallback is left out when the last case already covers everything, so no case is unreachable.
              */
            def total(selector: Term): Term = body match
                case matched @ Match(_, cases) =>
                    returnType.asType match
                        case '[b] =>
                            val covered  = cases.map(c => CaseDef.copy(c)(c.pattern, c.guard, '{ Maybe(${ c.rhs.asExprOf[b] }) }.asTerm))
                            val fallback = CaseDef(Wildcard(), None, '{ Maybe.empty[b] }.asTerm)
                            Match.copy(matched)(selector, if cases.exists(irrefutable(selector.tpe)) then covered else covered :+ fallback)
                case _ => report.errorAndAbort("A partial function literal needs cases", body.pos)

            def lowered(selector: Option[Term], replacements: Map[Symbol, Symbol]): Term =
                val term = owned(substitute(if partial then total(selector.get) else body, replacements))
                owned(lower(term)(lift(resultType)))

            (parameters, resultType.asType) match
                case (Nil, '[b])      => '{ () => ${ lowered(None, Map.empty).asExprOf[b < S] } }.asTerm
                case (List(p0), '[b]) =>
                    p0.tpt.tpe.asType match
                        case '[a0] =>
                            '{ (x0: a0) =>
                                ${ lowered(Some(('x0).asTerm), Map(p0.symbol -> ('x0).asTerm.symbol)).asExprOf[b < S] }
                            }.asTerm
                case (List(p0, p1), '[b]) =>
                    p0.tpt.tpe.asType match
                        case '[a0] =>
                            p1.tpt.tpe.asType match
                                case '[a1] =>
                                    '{ (x0: a0, x1: a1) =>
                                        ${
                                            lowered(None, Map(p0.symbol -> ('x0).asTerm.symbol, p1.symbol -> ('x1).asTerm.symbol))
                                                .asExprOf[b < S]
                                        }
                                    }.asTerm
                case _ => report.errorAndAbort("A callback with more than two parameters is not supported with .now", definition.pos)
            end match
        end lowerLambda

        /** True for an unguarded case whose pattern matches every value of the scrutinee's type. */
        private def irrefutable(scrutinee: TypeRepr)(c: CaseDef): Boolean =
            def covers(pattern: Tree): Boolean = pattern match
                case Wildcard()             => true
                case Bind(_, inner)         => covers(inner)
                case Typed(inner, tpt)      => covers(inner) && scrutinee <:< tpt.tpe
                case Alternatives(patterns) => patterns.exists(covers)
                case _                      => false
            c.guard.isEmpty && covers(c.pattern)
        end irrefutable
    end Lowering
end Lower
