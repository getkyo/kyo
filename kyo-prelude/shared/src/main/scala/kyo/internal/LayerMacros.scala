package kyo.internal

import kyo.*
import kyo.Ansi.*
import scala.quoted.*

private[kyo] object LayerMacros:

    transparent inline def reflect(using q: Quotes): q.reflectModule = q.reflect

    def reportErrors(using Quotes)(errors: ::[GraphError[reflect.TypeRepr, Expr[Layer[?, ?]]]]): Nothing =
        import reflect.*
        val messages = errors.map { error =>
            error match
                case GraphError.MissingInput(input, Some(parent)) => // TODO: show full type of parent
                    s"Missing Input: ${input.show} for Layer: ${parent.value.show}".red

                case GraphError.MissingInput(input, None) =>
                    s"Missing Input: ${input.show}".red

                case GraphError.AmbiguousInputs(target, found, parent) => // TODO: show full type of parent
                    s"Ambigious Inputs: ${found.map(_.value.show).mkString(", ")} for: ${target.show}".red

                case GraphError.CircularDependency(node) => // TODO: improve error message to include multiple nodes
                    s"Circular dependencies found: ${node.value.show} with Inputs: ${node.inputs.map(_.show).mkString(", ")} and Outputs: ${node
                            .outputs.map(_.show).mkString(", ")}".red
        }.distinct
        report.errorAndAbort(messages.mkString("\n"))
    end reportErrors

    transparent inline def make[Target](inline layers: Layer[?, ?]*): Layer[Target, ?] =
        ${ kyo.internal.LayerMacros.makeImpl[Target]('layers) }

    def makeImpl[Target: Type](using Quotes)(expr: Expr[Seq[Layer[?, ?]]]): Expr[Layer[Target, ?]] =
        import reflect.*
        if TypeRepr.of[Target] =:= TypeRepr.of[Nothing] then
            report.errorAndAbort("Missing Target Type; Did you forget to provide it?".red)
        expr match
            case Varargs(layers) =>
                val nodes = layers.map(layerToNode(_))
                val graph = Graph(nodes.toSet)(_ <:< _)

                val targets = flattenAnd(TypeRepr.of[Target])
                val targetLayer = graph.buildTargets(targets, None) match
                    case Validated.Success(value) =>
                        value
                    case Validated.Error(errors) =>
                        reportErrors(errors)

                val exprFold = targetLayer.fold[Expr[Layer[?, ?]]](
                    {
                        case ('{ $left: Layer[out1, s1] }, '{ $right: Layer[out2, s2] }) =>
                            '{ $left.and($right) }
                        case _ => bug("macro expected layers")
                    },
                    {
                        case ('{ $left: Layer[out1, s1] }, right) =>
                            right match
                                case '{ $right: Layer[out2, Env[`out1`] & s2] } =>
                                    '{ $left.to($right) }
                                case _ => bug("macro expected layers")
                        case _ => bug("macro expected layers")
                    },
                    _.value, {
                        // TODO: MAke nIcEr PlZEaz
                        val debugFold =
                            targetLayer.fold[String]("(" + _ + " and " + _ + ")", "(" + _ + " to " + _ + ")", _.value.show, "Empty")

                        report.errorAndAbort(
                            s"""|
                                | Empty layer found as input to Layer with non-zero requirements. Did you fully resolve dependencies?
                                | Debug: $debugFold
                                |""".stripMargin
                        )
                    }
                )

                exprFold.asInstanceOf[Expr[Layer[Target, ?]]]

            case _ => report.errorAndAbort("No layers found. Did you forget to provide them?".red)
        end match
    end makeImpl

    def layerToNode(using Quotes)(expr: Expr[Layer[?, ?]]): Node[reflect.TypeRepr, Expr[Layer[?, ?]]] =
        import reflect.*
        expr match
            case '{ $layer: Layer[out, s] } =>
                val inputs  = extractInputsFromPending(TypeRepr.of[s])
                val outputs = flattenAnd(TypeRepr.of[out])
                Node(inputs, outputs, expr)
        end match
    end layerToNode

    def extractInputsFromPending(using Quotes)(typeRepr: reflect.TypeRepr): Set[reflect.TypeRepr] =
        flattenAnd(typeRepr).flatMap(extractEnvs)

    object AsType:
        def unapply(using Quotes)(typeRepr: reflect.TypeRepr): Option[Type[?]] =
            Some(typeRepr.asType)

    def extractEnvs(using Quotes)(typeRepr: reflect.TypeRepr): Set[reflect.TypeRepr] =
        import reflect.*
        typeRepr.asType match
            case '[Env[tpe]] => flattenAnd(TypeRepr.of[tpe])
            case _           => Set.empty
    end extractEnvs

    def flattenAnd(using Quotes)(typeRepr: reflect.TypeRepr): Set[reflect.TypeRepr] =
        import reflect.*
        typeRepr match
            case AndType(left, right) =>
                flattenAnd(left) ++ flattenAnd(right)
            case typeRepr =>
                Set(typeRepr)
        end match
    end flattenAnd

    enum LayerLike[+A]:
        case And(left: LayerLike[A], right: LayerLike[A])
        case To(left: LayerLike[A], right: LayerLike[A])
        case Value(value: A)
        case Empty

        infix def and[A1 >: A](that: LayerLike[A1]): LayerLike[A1] = And(this, that)
        infix def to[A1 >: A](that: LayerLike[A1]): LayerLike[A1]  = To(this, that)

        def fold[B](andCase: (B, B) => B, toCase: (B, B) => B, valueCase: A => B, emptyCase: => B): B =
            this match
                case And(left, right) =>
                    andCase(left.fold(andCase, toCase, valueCase, emptyCase), right.fold(andCase, toCase, valueCase, emptyCase))
                case To(left, right) =>
                    toCase(left.fold(andCase, toCase, valueCase, emptyCase), right.fold(andCase, toCase, valueCase, emptyCase))
                case Value(value) => valueCase(value)
                case Empty        => emptyCase
    end LayerLike

    object LayerLike:
        def apply[A](value: A): LayerLike[A]            = Value(value)
        given [A]: CanEqual[LayerLike[A], LayerLike[A]] = CanEqual.derived

    final case class Node[Key, Value](inputs: Set[Key], outputs: Set[Key], value: Value)
    object Node:
        given [Key, Value]: CanEqual[Node[Key, Value], Node[Key, Value]] = CanEqual.derived

    enum Validated[+E, +A]:
        case Success(value: A)
        case Error(errors: ::[E])

        def map[B](f: A => B): Validated[E, B] =
            this match
                case Success(value) => Success(f(value))
                case Error(errors)  => Error(errors)

        def flatMap[E1 >: E, B](f: A => Validated[E1, B]): Validated[E1, B] =
            this match
                case Success(value) => f(value)
                case Error(errors)  => Error(errors)

        def zipWith[E1 >: E, B, C](that: Validated[E1, B])(f: (A, B) => C): Validated[E1, C] =
            (this, that) match
                case (Success(value1), Success(value2)) => Success(f(value1, value2))
                case (Error(e :: es), Error(es2))       => Error(::(e, es ++ es2))
                case (Error(errors1), _)                => Error(errors1)
                case (_, Error(errors2))                => Error(errors2)
    end Validated

    object Validated:
        def succeed[E, A](value: A): Validated[E, A] = Success(value)
        def error[E, A](error: E): Validated[E, A]   = Error(::(error, Nil))

        def traverse[E, A, B](set: Set[A])(f: A => Validated[E, B]): Validated[E, Set[B]] =
            set.foldLeft[Validated[E, Set[B]]](Validated.Success(Set.empty)) { (acc, item) =>
                acc.zipWith(f(item)) { (accSet, b) =>
                    accSet + b
                }
            }

        def sequence[E, A](set: Set[Validated[E, A]]): Validated[E, Set[A]] =
            set.foldLeft(Validated.Success(Set.empty)) { (acc, item) =>
                acc.zipWith(item) { (accSet, b) =>
                    accSet + b
                }
            }
    end Validated

    enum GraphError[Key, Value]:
        case MissingInput(input: Key, parent: Option[Node[Key, Value]])
        case CircularDependency(node: Node[Key, Value]) // TODO: Maybe also trace the path.
        case AmbiguousInputs(target: Key, found: Set[Node[Key, Value]], parent: Option[Node[Key, Value]])
    end GraphError

    final case class Graph[Key, Value](nodes: Set[Node[Key, Value]])(equals: (Key, Key) => Boolean):

        def buildTargets(
            targets: Set[Key],
            parent: Option[Node[Key, Value]],
            seen: Set[Node[Key, Value]] = Set.empty
        ): Validated[GraphError[Key, Value], LayerLike[Node[Key, Value]]] =
            for
                nodes <- findNodesWithOutputs(targets, parent, seen)
                values <- Validated.traverse(nodes) { node =>
                    if node.inputs.isEmpty then Validated.succeed(LayerLike.Value(node))
                    else
                        buildTargets(node.inputs, Some(node), seen = seen + node)
                            .map { input => input to LayerLike.Value(node) }
                }
            yield values.reduceOption(_ and _).getOrElse(LayerLike.Empty)
        end buildTargets

        def findNodesWithOutputs(
            targets: Set[Key],
            parent: Option[Node[Key, Value]],
            seen: Set[Node[Key, Value]]
        ): Validated[GraphError[Key, Value], Set[Node[Key, Value]]] =
            Validated.traverse(targets) { target => findNodeWithOutputFor(target, parent, seen) }

        def findNodeWithOutputFor(
            target: Key,
            parent: Option[Node[Key, Value]],
            seen: Set[Node[Key, Value]]
        ): Validated[GraphError[Key, Value], Node[Key, Value]] =
            val matching = nodes.filter { node => node.outputs.exists(output => equals(output, target)) }
            matching.size match
                case 1 =>
                    val matched = matching.head
                    if seen.contains(matched) then Validated.error(GraphError.CircularDependency(matched))
                    else Validated.succeed(matched)
                case 0 => Validated.error(GraphError.MissingInput(target, parent))
                case _ => Validated.error(GraphError.AmbiguousInputs(target, matching, parent))
            end match
        end findNodeWithOutputFor
    end Graph
end LayerMacros
