package kyo.lang.internal

import kyo.<
import scala.quoted.*

/** The typed macro that validates and lowers a `.kyo` definition body.
  *
  * The `kyo-lang` plugin wraps every method body in `Body.apply` and every field initializer in `Body.field`, after wrapping each
  * expression in a [[Markers]] marker. Normal typing then resolves the markers, and this macro receives the typed body. It:
  *
  *   - validates every resolved call and reference through [[Validate]];
  *   - collects the effect rows recorded by the pending markers into the body's required row `S`;
  *   - returns the body unchanged when no marker is pending, so a pure definition keeps its ordinary type;
  *   - otherwise lowers the body into Kyo composition: each pending marker becomes a `map` over the computation it wraps, with the rest
  *     of the body as the continuation, preserving binding, evaluation order, and conditional execution.
  *
  * The macro is `transparent inline`, so the definition's inferred result type is the precise `A < S` of the lowered body rather than a
  * widened bound. An explicit result annotation on the definition participates in typing as usual: a pure annotation cannot erase a
  * pending effect, and an effectful annotation constrains the inferred row.
  *
  * Supported lowering covers literals and references, blocks with immutable locals, conditionals, strict applications (receiver and
  * arguments evaluated in source order), type applications, selections, ascriptions, repeated arguments, and pure closures. Effectful
  * closures, effectful by-name arguments, and effectful field initializers are reported as unsupported at their source position. Those
  * are not permanent exclusions: they are the next rules the design calls for.
  */
object Body:

    transparent inline def apply[A](inline value: A) = ${ transform('value, false) }

    transparent inline def field[A](inline value: A) = ${ transform('value, true) }

    private def transform[A: Type](value: Expr[A], field: Boolean)(using Quotes): Expr[?] =
        import quotes.reflect.*

        val markers = Symbol.requiredModule("kyo.lang.internal.Markers")
        val pending = markers.methodMember("force")
            .find(_.owner.equals(markers.moduleClass))
            .getOrElse(report.errorAndAbort("Missing pending marker"))
        val pure = Symbol.requiredClass("kyo.lang.internal.PureMarker").methodMember("force").headOption
            .getOrElse(report.errorAndAbort("Missing pure marker"))

        /** The effect rows recorded by every pending marker under `tree`, in source order. */
        def effects(tree: Tree): List[TypeRepr] = tree match
            case Apply(TypeApply(fun, List(_, effect)), List(arg)) if fun.symbol.equals(pending) =>
                effect.tpe :: effects(arg)
            case _ =>
                val accumulator = new TreeAccumulator[List[TypeRepr]]:
                    def foldTree(found: List[TypeRepr], child: Tree)(owner: Symbol): List[TypeRepr] =
                        found ::: effects(child)
                accumulator.foldOverTree(Nil, tree)(Symbol.spliceOwner)

        val required = effects(value.asTerm)
        Validate(value)
        if field && required.nonEmpty then
            report.errorAndAbort("Effectful fields are not supported in .kyo; define a method", value.asTerm.pos)
        if required.isEmpty then return value
        val row = required.foldLeft(TypeRepr.of[Any])((left, right) => AndType(left, right)).simplified

        row.asType match
            case '[s] =>
                type Environment = Map[Symbol, Term]

                /** Rebinds references to lowered locals, whose definitions now live in a generated continuation. */
                def replace(tree: Term, environment: Environment): Term =
                    val mapper = new TreeMap:
                        override def transformTerm(term: Term)(owner: Symbol): Term = term match
                            case id: Ident => environment.getOrElse(id.symbol, id)
                            case _         => super.transformTerm(term)(owner)
                    mapper.transformTerm(tree)(Symbol.spliceOwner)
                end replace

                /** Lowers `tree` and passes its pure result term to `next`, which builds the continuation. */
                def lower(tree: Term, environment: Environment)(next: Term => Expr[A < s]): Expr[A < s] = tree match
                    case Inlined(_, Nil, body)                                                           => lower(body, environment)(next)
                    case Apply(TypeApply(fun, List(result, _)), List(arg)) if fun.symbol.equals(pending) =>
                        lower(arg, environment) { computation =>
                            result.tpe.asType match
                                case '[a] =>
                                    val op = computation.asExprOf[a < s]
                                    '{ $op.map[A, s]((bound: a) => ${ next('bound.asTerm) }) }
                        }
                    case Apply(TypeApply(fun, _), List(arg)) if fun.symbol.equals(pure) =>
                        lower(arg, environment)(next)
                    case closure @ Block(_, _: Closure) if effects(closure).isEmpty =>
                        next(replace(closure, environment))
                    case closure @ Block(_, _: Closure) =>
                        report.errorAndAbort("Effectful callbacks are not supported in .kyo", closure.pos)
                    case Block(statements, result) =>
                        def block(rest: List[Statement], env: Environment): Expr[A < s] = rest match
                            case Nil                          => lower(result, env)(next)
                            case (definition: ValDef) :: tail =>
                                definition.rhs match
                                    case Some(rhs) =>
                                        lower(rhs, env) { bound =>
                                            ValDef.let(Symbol.spliceOwner, definition.name, bound) { local =>
                                                block(tail, env.updated(definition.symbol, local)).asTerm
                                            }.asExprOf[A < s]
                                        }
                                    case None => report.errorAndAbort("Abstract local value", definition.pos)
                            case (statement: Term) :: tail => lower(statement, env)(_ => block(tail, env))
                            case statement :: _            =>
                                report.errorAndAbort("Local definitions other than values are not supported in .kyo", statement.pos)
                        block(statements, environment)
                    case If(condition, yes, no) =>
                        lower(condition, environment) { test =>
                            '{
                                if ${ test.asExprOf[Boolean] } then ${ lower(yes, environment)(next) }
                                else ${ lower(no, environment)(next) }
                            }
                        }
                    case application @ Apply(function, arguments) =>
                        function.tpe.widen match
                            case method: MethodType =>
                                method.paramTypes.zip(arguments).foreach {
                                    case (_: ByNameType, arg) if effects(arg).nonEmpty =>
                                        report.errorAndAbort("Effectful by-name arguments are not supported in .kyo", arg.pos)
                                    case _ => ()
                                }
                            case _ => ()
                        end match
                        def args(rest: List[Term], accumulated: List[Term], fun: Term): Expr[A < s] = rest match
                            case Nil          => next(Apply.copy(application)(fun, accumulated.reverse))
                            case head :: tail => lower(head, environment)(arg => args(tail, arg :: accumulated, fun))
                        lower(function, environment)(fun => args(arguments, Nil, fun))
                    case application @ TypeApply(function, arguments) =>
                        lower(function, environment)(fun => next(TypeApply.copy(application)(fun, arguments)))
                    case selection @ Select(qualifier, name) =>
                        lower(qualifier, environment)(qual => next(Select.copy(selection)(qual, name)))
                    case Typed(expression, tpt) =>
                        lower(expression, environment)(term => next(Typed(term, tpt)))
                    case repeated @ Repeated(elements, tpt) =>
                        def loop(rest: List[Term], accumulated: List[Term]): Expr[A < s] = rest match
                            case Nil          => next(Repeated.copy(repeated)(accumulated.reverse, tpt))
                            case head :: tail => lower(head, environment)(term => loop(tail, term :: accumulated))
                        loop(elements, Nil)
                    case _: Ident | _: Literal | _: This => next(replace(tree, environment))
                    case _ if effects(tree).isEmpty      => next(replace(tree, environment))
                    case _                               => report.errorAndAbort("Effectful construct is not supported in .kyo", tree.pos)

                lower(value.asTerm, Map.empty)(term => '{ kyo.Kyo.lift[A, s](${ term.asExprOf[A] }) })
        end match
    end transform
end Body
