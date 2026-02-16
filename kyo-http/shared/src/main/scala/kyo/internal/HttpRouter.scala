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
  *
  * Features:
  *   - O(path-segments) lookup time
  *   - Zero allocation during lookup (no split, no List, no Option)
  *   - Binary search for literal children
  *   - Literal segments have priority over captures
  *   - Method-based dispatch at leaf nodes
  */
final private[kyo] class HttpRouter private (
    private val nodes: Array[HttpRouter.Node],
    private val handlers: Array[HttpHandler[Any]]
):
    import HttpRouter.*

    /** Find a handler matching the given method and path - zero allocation for success case.
      *
      * Per RFC 9110 §9.3.2, HEAD is implicitly supported wherever GET is registered.
      */
    def find(method: Method, path: String): Result[FindError, HttpHandler[Any]] =
        val methodIdx = HttpRouter.methodIndex(method)
        val nodeIdx   = findNodeIndex(path)

        if nodeIdx < 0 then NotFoundResult
        else
            val node       = nodes(nodeIdx)
            val handlerIdx = node.handlerIndices(methodIdx)
            if handlerIdx >= 0 then Result.succeed(handlers(handlerIdx))
            else if methodIdx == HeadMethodIdx then
                // HEAD falls back to GET handler per RFC 9110 §9.3.2
                val getHandlerIdx = node.handlerIndices(GetMethodIdx)
                if getHandlerIdx >= 0 then Result.succeed(handlers(getHandlerIdx))
                else if node.allowedMethods.nonEmpty then Result.fail(FindError.MethodNotAllowed(node.allowedMethods))
                else NotFoundResult
            else if node.allowedMethods.nonEmpty then Result.fail(FindError.MethodNotAllowed(node.allowedMethods))
            else NotFoundResult
            end if
        end if
    end find

    /** Find node index by parsing path in-place without allocation */
    private def findNodeIndex(path: String): Int =
        val len = path.length

        @tailrec def loop(nodeIdx: Int, pos: Int): Int =
            // Skip leading slashes
            val start = skipSlashes(path, pos, len)
            if start >= len then
                // Reached end of path
                nodeIdx
            else
                // Find end of current segment
                val segEnd = findSegmentEnd(path, start, len)
                val node   = nodes(nodeIdx)

                // Try literal match first (binary search)
                val literalIdx = binarySearchSegment(node.literalSegments, node.literalIndices, path, start, segEnd)
                if literalIdx >= 0 then loop(node.literalIndices(literalIdx), segEnd)
                else if node.captureChild >= 0 then
                    // Fall back to capture
                    loop(node.captureChild, segEnd)
                else -1 // Not found
                end if
            end if
        end loop

        loop(0, 0)
    end findNodeIndex

    private def skipSlashes(path: String, pos: Int, len: Int): Int =
        HttpPath.skipSlashes(path, pos, len)

    private def findSegmentEnd(path: String, pos: Int, len: Int): Int =
        HttpPath.findSegmentEnd(path, pos, len)

    /** Binary search for segment in sorted literal children (parallel arrays) */
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

    /** Compare segment string with substring of path */
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

    /** Error types for route lookup */
    enum FindError derives CanEqual:
        case MethodNotAllowed(allowedMethods: Set[Method])
        case NotFound
    end FindError

    /** Cached result to avoid allocation on 404s */
    private val NotFoundResult: Result[FindError, Nothing] = Result.fail(FindError.NotFound)

    /** Method indices for fixed-size array lookup (avoids Map) */
    private val MethodCount   = 9
    private val GetMethodIdx  = 0
    private val HeadMethodIdx = 5

    private def methodIndex(method: Method): Int =
        // Use first char + length as simple hash - all HTTP methods are unique this way
        val name = method.name
        name.charAt(0) match
            case 'G' => 0 // GET
            case 'P' =>
                name.length match
                    case 3 => 1 // PUT
                    case 4 => 2 // POST
                    case 5 => 3 // PATCH
                    case _ => throw new IllegalArgumentException(s"Unknown HTTP method: $name")
            case 'D' => 4 // DELETE
            case 'H' => 5 // HEAD
            case 'O' => 6 // OPTIONS
            case 'T' => 7 // TRACE
            case 'C' => 8 // CONNECT
            case _   => throw new IllegalArgumentException(s"Unknown HTTP method: $name")
        end match
    end methodIndex

    /** A node in the flat trie structure with parallel arrays for cache-friendly search */
    final private[internal] class Node(
        val literalSegments: Array[String], // segment names - sorted for binary search
        val literalIndices: Array[Int],     // corresponding node indices
        val captureChild: Int,              // nodeIndex or -1 if absent
        val handlerIndices: Array[Int],     // indexed by methodIndex, -1 if no handler
        val allowedMethods: Set[Method]     // pre-computed for MethodNotAllowed responses
    )

    /** Build a router from a sequence of handlers */
    def apply(handlers: Seq[HttpHandler[Any]]): HttpRouter =
        if handlers.isEmpty then
            val emptyNode = new Node(Array.empty, Array.empty, -1, Array.fill(MethodCount)(-1), Set.empty)
            new HttpRouter(Array(emptyNode), Array.empty)
        else
            // Phase 1: Build mutable trie
            val root = new MutableNode()
            handlers.foreach { handler =>
                val segments = pathToSegments(handler.route.path)
                insert(root, segments, handler.route.method, handler)
            }

            // Phase 2: Count nodes and handlers
            val (nodeCount, handlerCount) = countNodes(root)

            // Phase 3: Allocate arrays
            val flatNodes    = new Array[Node](nodeCount)
            val flatHandlers = new Array[HttpHandler[Any]](handlerCount)

            // Phase 4: Serialize trie into flat arrays
            val state = new SerializeState(flatNodes, flatHandlers)
            discard(serialize(root, state))

            new HttpRouter(flatNodes, flatHandlers)
        end if
    end apply

    // ==================== Build Phase ====================

    /** Mutable node used only during construction */
    final private class MutableNode(
        var handlers: Map[Method, HttpHandler[Any]] = Map.empty,
        var literalChildren: Map[String, MutableNode] = Map.empty,
        var captureChild: MutableNode | Null = null
    )

    /** Insert a handler into the mutable trie */
    private def insert(node: MutableNode, segments: List[Segment], method: Method, handler: HttpHandler[Any]): Unit =
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

    /** Count total nodes and handlers in the trie */
    private def countNodes(root: MutableNode): (Int, Int) =
        var nodeCount    = 0
        var handlerCount = 0

        def visit(node: MutableNode): Unit =
            nodeCount += 1
            handlerCount += node.handlers.size
            node.literalChildren.values.foreach(visit)
            node.captureChild match
                case null     => ()
                case existing => visit(existing)
        end visit

        visit(root)
        (nodeCount, handlerCount)
    end countNodes

    /** State for serialization phase */
    private class SerializeState(
        val nodes: Array[Node],
        val handlers: Array[HttpHandler[Any]]
    ):
        var nextNodeIdx: Int    = 0
        var nextHandlerIdx: Int = 0

        def allocNodeIdx(): Int =
            val idx = nextNodeIdx
            nextNodeIdx += 1
            idx
        end allocNodeIdx

        def allocHandlerIdx(): Int =
            val idx = nextHandlerIdx
            nextHandlerIdx += 1
            idx
        end allocHandlerIdx
    end SerializeState

    /** Serialize mutable trie into flat arrays via DFS (pre-order: allocate index, then recurse children). */
    private def serialize(node: MutableNode, state: SerializeState): Int =
        val nodeIdx = state.allocNodeIdx()

        // Literal children sorted for binary search during lookup
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

        // Build handler indices array and collect allowed methods
        val handlerIndices = Array.fill(MethodCount)(-1)
        var allowedMethods = Set.empty[Method]
        node.handlers.foreach { case (method, handler) =>
            val handlerIdx = state.allocHandlerIdx()
            state.handlers(handlerIdx) = handler
            handlerIndices(methodIndex(method)) = handlerIdx
            allowedMethods = allowedMethods + method
        }

        // Create flat node
        state.nodes(nodeIdx) = new Node(literalSegments, literalIndices, captureChildIdx, handlerIndices, allowedMethods)
        nodeIdx
    end serialize

    // ==================== Path Parsing ====================

    /** Internal segment representation */
    sealed private trait Segment
    private object Segment:
        case class Literal(value: String) extends Segment
        case object Capture               extends Segment

    /** Convert a route path to segments */
    private def pathToSegments(path: HttpPath[Any]): List[Segment] =
        path match
            case s: String =>
                s.split('/').filter(_.nonEmpty).map(Segment.Literal(_)).toList
            case segment: HttpPath.Segment[?] =>
                segmentToList(segment)

    private def segmentToList(segment: HttpPath.Segment[?]): List[Segment] =
        segment match
            case HttpPath.Segment.Literal(value) =>
                value.split('/').filter(_.nonEmpty).map(Segment.Literal(_)).toList
            case HttpPath.Segment.Capture(_, _) =>
                List(Segment.Capture)
            case HttpPath.Segment.Concat(left, right) =>
                segmentToList(left.asInstanceOf[HttpPath.Segment[?]]) ++
                    segmentToList(right.asInstanceOf[HttpPath.Segment[?]])

end HttpRouter
