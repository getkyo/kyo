package kyo.internal

import kyo.*
import scala.quoted.*

object LayerMacros:
    // TODO: make sure that `Target` is provided ie not Nothing
    transparent inline def layersToNodesTest[Target](inline layers: Layer[?, ?]*): Layer[Target, ?] =
        ${ layersToNodesImpl[Target]('layers) }

    transparent inline def reflect(using q: Quotes): q.reflectModule = q.reflect

    def debugNode[Out, S](using Quotes)(node: Node[quotes.reflect.TypeRepr, Expr[Layer[Out, S]]]): String =
        s"Node(In: ${node.inputs.map(_.show).mkString("{", ", ", "}")}, Out: ${node.outputs.map(_.show).mkString("{", ", ", "}")}, ${node.value.show})"

    def layersToNodesImpl[Target: Type](using Quotes)(expr: Expr[Seq[Layer[?, ?]]]): Expr[Layer[Target, ?]] =
        import reflect.*
        expr match
            case Varargs(layers) =>
                val nodes = layers.map(layerToNode(_))
                val graph = Graph(nodes.toSet)(_ =:= _)

                val targets     = flattenAnd(TypeRepr.of[Target])
                val targetLayer = graph.buildTarget(targets)

                val debugFold = targetLayer.fold[String]("(" + _ + " and " + _ + ")", "(" + _ + " to " + _ + ")", _.value.show, "Empty")
                val exprFold = targetLayer.fold[Expr[Layer[?, ?]]](
                    { case ('{ $left: Layer[out1, s1] }, '{ $right: Layer[out2, s2] }) =>
                        '{ $left.and($right) }
                    },
                    { case ('{ $left: Layer[out1, s1] }, right) =>
                        right match
                            case '{ $right: Layer[out2, Envs[`out1`] & s2] } =>
                                '{ $left.to($right) }
                    },
                    _.value,
                    report.errorAndAbort("No target found. Cannot produce Empty layer ")
                )

                exprFold.asInstanceOf[Expr[Layer[Target, ?]]]
            //  val message = nodes.map(debugNode).mkString("\n")
            //  report.errorAndAbort(s"""
            //  targets: ${targets.map(_.show).mkString("{", ", ", "}")}
            //  input: ${message}
            //  output: ${debugFold}
            //  expr: ${exprFold.show}
            //  """)

            case _ =>
                report.errorAndAbort("NO LAYERS")
        end match
    end layersToNodesImpl

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
            case '[Envs[tpe]] => flattenAnd(TypeRepr.of[tpe])
            case _            => Set.empty
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
end LayerMacros

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

case class Node[Key, Value](inputs: Set[Key], outputs: Set[Key], value: Value)

case class Graph[Key, Value](nodes: Set[Node[Key, Value]])(equals: (Key, Key) => Boolean):
    def buildTarget(targets: Set[Key]): LayerLike[Node[Key, Value]] =
        val values = findNodesWithOutputs(targets).map { node =>
            if node.inputs.isEmpty then LayerLike.Value(node)
            else
                val inputNode = buildTarget(node.inputs)
                inputNode to LayerLike.Value(node)
        }
        values.reduceOption { (left, right) =>
            left and right
        }.getOrElse(LayerLike.Empty)
    end buildTarget

    def findNodesWithOutputs(targets: Set[Key]): Set[Node[Key, Value]] =
        nodes.filter(node => node.outputs.exists(output => targets.exists(target => equals(output, target))))
end Graph
