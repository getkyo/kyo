package kyo.internal

import kyo.*
import kyo.HttpHandler
import kyo.HttpPath
import kyo.HttpRequest.Method
import kyo.HttpRoute
import scala.annotation.tailrec

/** Flat array-based HTTP router for efficient, zero-allocation path matching.
  *
  * The router uses a prefix tree (trie) structure serialized into flat arrays for cache-friendly traversal. All nodes are stored
  * contiguously in a single array, with integer indices for navigation instead of object pointers.
  */
final private[kyo] class HttpRouter private (
    private val nodes: Array[HttpRouter.Node],
    private val handlers: Array[HttpHandler[?]]
):
    import HttpRouter.*

    /** Find a handler matching the given method and path - zero allocation for success case.
      *
      * Per RFC 9110 §9.3.2, HEAD is implicitly supported wherever GET is registered.
      */
    def find(method: Method, path: String): Result[FindError, HttpHandler[?]] =
        val methodIdx = HttpRouter.methodIndex(method)
        val nodeIdx   = findNodeIndex(path)

        if nodeIdx < 0 then NotFoundResult
        else
            val node       = nodes(nodeIdx)
            val handlerIdx = node.handlerIndices(methodIdx)
            if handlerIdx >= 0 then Result.succeed(handlers(handlerIdx))
            else if methodIdx == HeadMethodIdx then
                val getHandlerIdx = node.handlerIndices(GetMethodIdx)
                if getHandlerIdx >= 0 then Result.succeed(handlers(getHandlerIdx))
                else if node.allowedMethods.nonEmpty then Result.fail(FindError.MethodNotAllowed(node.allowedMethods))
                else NotFoundResult
            else if node.allowedMethods.nonEmpty then Result.fail(FindError.MethodNotAllowed(node.allowedMethods))
            else NotFoundResult
            end if
        end if
    end find

    private def findNodeIndex(path: String): Int =
        val len = path.length

        @tailrec def loop(nodeIdx: Int, pos: Int): Int =
            val start = HttpPath.skipSlashes(path, pos, len)
            if start >= len then nodeIdx
            else
                val segEnd = HttpPath.findSegmentEnd(path, start, len)
                val node   = nodes(nodeIdx)

                val literalIdx = binarySearchSegment(node.literalSegments, node.literalIndices, path, start, segEnd)
                if literalIdx >= 0 then loop(node.literalIndices(literalIdx), segEnd)
                else if node.captureChild >= 0 then loop(node.captureChild, segEnd)
                else -1
                end if
            end if
        end loop

        loop(0, 0)
    end findNodeIndex

    private def binarySearchSegment(
        segments: Array[String],
        indices: Array[Int],
        path: String,
        start: Int,
        end: Int
    ): Int =
        @tailrec def search(lo: Int, hi: Int): Int =
            if lo > hi then -1
            else
                val mid = (lo + hi) >>> 1
                val cmp = compareSegment(segments(mid), path, start, end)
                if cmp < 0 then search(mid + 1, hi)
                else if cmp > 0 then search(lo, mid - 1)
                else mid
        search(0, segments.length - 1)
    end binarySearchSegment

    private def compareSegment(segment: String, path: String, start: Int, end: Int): Int =
        val segLen  = segment.length
        val pathLen = end - start
        val minLen  = Math.min(segLen, pathLen)

        @tailrec def loop(i: Int): Int =
            if i >= minLen then segLen - pathLen
            else
                val c1 = segment.charAt(i)
                val c2 = path.charAt(start + i)
                if c1 != c2 then c1 - c2
                else loop(i + 1)
        loop(0)
    end compareSegment

end HttpRouter

private[kyo] object HttpRouter:

    enum FindError derives CanEqual:
        case MethodNotAllowed(allowedMethods: Set[Method])
        case NotFound
    end FindError

    private val NotFoundResult: Result[FindError, Nothing] = Result.fail(FindError.NotFound)

    private val MethodCount   = 9
    private val GetMethodIdx  = 0
    private val HeadMethodIdx = 5

    private def methodIndex(method: Method): Int =
        val name = method.name
        name.charAt(0) match
            case 'G' => 0
            case 'P' =>
                name.length match
                    case 3 => 1
                    case 4 => 2
                    case 5 => 3
                    case _ => throw new IllegalArgumentException(s"Unknown HTTP method: $name")
            case 'D' => 4
            case 'H' => 5
            case 'O' => 6
            case 'T' => 7
            case 'C' => 8
            case _   => throw new IllegalArgumentException(s"Unknown HTTP method: $name")
        end match
    end methodIndex

    final private[internal] class Node(
        val literalSegments: Array[String],
        val literalIndices: Array[Int],
        val captureChild: Int,
        val handlerIndices: Array[Int],
        val allowedMethods: Set[Method]
    )

    def apply(handlers: Seq[HttpHandler[?]]): HttpRouter =
        if handlers.isEmpty then
            val emptyNode = new Node(Array.empty, Array.empty, -1, Array.fill(MethodCount)(-1), Set.empty)
            new HttpRouter(Array(emptyNode), Array.empty)
        else
            val root = new MutableNode()
            handlers.foreach { handler =>
                val segments = pathToSegments(handler.route.path)
                insert(root, segments, handler.route.method, handler)
            }

            val (nodeCount, handlerCount) = countNodes(root)
            val flatNodes                 = new Array[Node](nodeCount)
            val flatHandlers              = new Array[HttpHandler[?]](handlerCount)
            val state                     = new SerializeState(flatNodes, flatHandlers)
            discard(serialize(root, state))

            new HttpRouter(flatNodes, flatHandlers)
        end if
    end apply

    // ==================== Build Phase ====================

    final private class MutableNode(
        var handlers: Map[Method, HttpHandler[?]] = Map.empty,
        var literalChildren: Map[String, MutableNode] = Map.empty,
        var captureChild: MutableNode | Null = null
    )

    private def insert(node: MutableNode, segments: List[Segment], method: Method, handler: HttpHandler[?]): Unit =
        segments match
            case Nil =>
                node.handlers = node.handlers + (method -> handler)
            case seg :: rest =>
                seg match
                    case Segment.Literal(value) =>
                        val childNode = node.literalChildren.get(value) match
                            case Some(n) => n
                            case None =>
                                val newNode = new MutableNode()
                                node.literalChildren = node.literalChildren + (value -> newNode)
                                newNode
                        insert(childNode, rest, method, handler)
                    case _: Segment.Capture.type =>
                        val childNode = node.captureChild match
                            case null =>
                                val newNode = new MutableNode()
                                node.captureChild = newNode
                                newNode
                            case existing => existing
                        insert(childNode, rest, method, handler)
        end match
    end insert

    private def countNodes(root: MutableNode): (Int, Int) =
        def visit(node: MutableNode): (Int, Int) =
            val childCounts = node.literalChildren.values.foldLeft((0, 0)) { case ((n, h), child) =>
                val (cn, ch) = visit(child)
                (n + cn, h + ch)
            }
            val captureCounts = node.captureChild match
                case null     => (0, 0)
                case existing => visit(existing)
            (1 + childCounts._1 + captureCounts._1, node.handlers.size + childCounts._2 + captureCounts._2)
        end visit
        visit(root)
    end countNodes

    private class SerializeState(
        val nodes: Array[Node],
        val handlers: Array[HttpHandler[?]]
    ):
        var nextNodeIdx: Int    = 0
        var nextHandlerIdx: Int = 0
        def allocNodeIdx(): Int =
            val idx = nextNodeIdx; nextNodeIdx += 1; idx
        def allocHandlerIdx(): Int =
            val idx = nextHandlerIdx; nextHandlerIdx += 1; idx
    end SerializeState

    private def serialize(node: MutableNode, state: SerializeState): Int =
        val nodeIdx = state.allocNodeIdx()

        val sortedLiteralKeys = node.literalChildren.keys.toArray.sorted
        val literalSegments   = new Array[String](sortedLiteralKeys.length)
        val literalIndices    = new Array[Int](sortedLiteralKeys.length)

        @tailrec def serializeLiterals(i: Int): Unit =
            if i < sortedLiteralKeys.length then
                val key = sortedLiteralKeys(i)
                literalSegments(i) = key
                literalIndices(i) = serialize(node.literalChildren(key), state)
                serializeLiterals(i + 1)
        serializeLiterals(0)

        val captureChildIdx = node.captureChild match
            case null     => -1
            case existing => serialize(existing, state)

        val handlerIndices = Array.fill(MethodCount)(-1)
        val allowedMethods = node.handlers.foldLeft(Set.empty[Method]) { case (methods, (method, handler)) =>
            val handlerIdx = state.allocHandlerIdx()
            state.handlers(handlerIdx) = handler
            handlerIndices(methodIndex(method)) = handlerIdx
            methods + method
        }

        state.nodes(nodeIdx) = new Node(literalSegments, literalIndices, captureChildIdx, handlerIndices, allowedMethods)
        nodeIdx
    end serialize

    // ==================== Path Parsing ====================

    sealed private trait Segment
    private object Segment:
        case class Literal(value: String) extends Segment
        case object Capture               extends Segment

    private def pathToSegments(path: HttpPath[?]): List[Segment] =
        path match
            case HttpPath.Literal(s) =>
                s.split('/').filter(_.nonEmpty).map(Segment.Literal(_)).toList
            case HttpPath.Capture(_, _, _) =>
                List(Segment.Capture)
            case HttpPath.Concat(left, right) =>
                pathToSegments(left) ++ pathToSegments(right)
            case HttpPath.Rest(_) =>
                List(Segment.Capture) // Rest matches any remaining segment

end HttpRouter
