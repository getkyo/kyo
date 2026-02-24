package kyo.http2.internal

import kyo.Dict
import kyo.DictBuilder
import kyo.Maybe
import kyo.Maybe.Absent
import kyo.Maybe.Present
import kyo.Result
import kyo.Span
import kyo.discard
import kyo.http2.HttpHandler
import kyo.http2.HttpMethod
import kyo.http2.HttpPath
import scala.annotation.tailrec

/** Flat array-based HTTP router for efficient, zero-allocation path matching.
  *
  * Uses a prefix tree (trie) serialized into flat arrays for cache-friendly traversal. All nodes are stored contiguously in a single array,
  * with integer indices for navigation instead of object pointers.
  *
  * Path capture values are extracted during the trie walk (zero second-pass). Streaming flags are pre-computed per endpoint at build time.
  */
final class HttpRouter private (
    private val nodes: Span[HttpRouter.Node],
    private val endpoints: Span[HttpHandler[?, ?, ?]],
    private val captureWireNames: Span[Span[String]],
    private val streamingReqFlags: Span[Boolean],
    private val streamingRespFlags: Span[Boolean]
):
    import HttpRouter.*

    /** Find an endpoint matching the given method and path.
      *
      * Per RFC 9110 §9.3.2, HEAD is implicitly supported wherever GET is registered.
      */
    def find(method: HttpMethod, path: String): Result[FindError, RouteMatch] =
        val methodIdx = HttpRouter.methodIndex(method)
        if maxCaptures == 0 then
            val nodeIdx = findNodeIndexNoCaptures(path)
            resolveEndpoint(nodeIdx, methodIdx, emptyCaptures, 0)
        else
            val captureValues = new Array[String](maxCaptures)
            val nodeAndCount  = findNodeIndex(path, captureValues)
            val nodeIdx       = nodeAndCount >> 16
            val captureCount  = nodeAndCount & 0xffff
            resolveEndpoint(nodeIdx, methodIdx, captureValues, captureCount)
        end if
    end find

    private def resolveEndpoint(
        nodeIdx: Int,
        methodIdx: Int,
        captureValues: Array[String],
        captureCount: Int
    ): Result[FindError, RouteMatch] =
        if nodeIdx < 0 then NotFoundResult
        else
            val node       = nodes(nodeIdx)
            val handlerIdx = node.endpointIndices(methodIdx)
            if handlerIdx >= 0 then Result.succeed(buildMatch(handlerIdx, captureValues, captureCount))
            else if methodIdx == HeadMethodIdx then
                val getIdx = node.endpointIndices(GetMethodIdx)
                if getIdx >= 0 then Result.succeed(buildMatch(getIdx, captureValues, captureCount))
                else node.methodNotAllowedResult
            else node.methodNotAllowedResult
            end if
        end if
    end resolveEndpoint

    private val maxCaptures: Int =
        @tailrec def loop(i: Int, max: Int): Int =
            if i >= captureWireNames.size then max
            else
                val len = captureWireNames(i).size
                loop(i + 1, if len > max then len else max)
        loop(0, 0)
    end maxCaptures

    private val emptyCaptures: Array[String] = Array.empty[String]

    private def buildMatch(endpointIdx: Int, captureValues: Array[String], captureCount: Int): RouteMatch =
        val wireNames = captureWireNames(endpointIdx)
        val captures =
            if captureCount == 0 then Dict.empty[String, String]
            else
                val builder = DictBuilder.init[String, String]
                @tailrec def loop(i: Int): Unit =
                    if i < captureCount && i < wireNames.size then
                        discard(builder.add(wireNames(i), captureValues(i)))
                        loop(i + 1)
                loop(0)
                builder.result()
        RouteMatch(
            endpoints(endpointIdx),
            captures,
            streamingReqFlags(endpointIdx),
            streamingRespFlags(endpointIdx)
        )
    end buildMatch

    /** Trie walk with capture extraction. Returns packed (nodeIdx << 16 | captureCount). nodeIdx is -1 on miss. */
    private def findNodeIndex(path: String, captureValues: Array[String]): Int =
        val len = path.length

        @tailrec def loop(nodeIdx: Int, pos: Int, captureCount: Int): Int =
            val start = skipSlashes(path, pos, len)
            if start >= len then (nodeIdx << 16) | captureCount
            else
                val segEnd = findSegmentEnd(path, start, len)
                val node   = nodes(nodeIdx)

                val literalIdx = binarySearchSegment(node.literalSegments, path, start, segEnd)
                if literalIdx >= 0 then loop(node.literalIndices(literalIdx), segEnd, captureCount)
                else if node.captureChild >= 0 then
                    if captureCount < captureValues.length then
                        captureValues(captureCount) = urlDecodeSegment(path, start, segEnd)
                    end if
                    loop(node.captureChild, segEnd, captureCount + 1)
                else if node.restChild >= 0 then
                    if captureCount < captureValues.length then
                        captureValues(captureCount) = path.substring(start)
                    end if
                    (node.restChild << 16) | (captureCount + 1)
                else -1 << 16
                end if
            end if
        end loop

        loop(0, 0, 0)
    end findNodeIndex

    /** Trie walk without capture extraction — avoids array allocation entirely. */
    private def findNodeIndexNoCaptures(path: String): Int =
        val len = path.length

        @tailrec def loop(nodeIdx: Int, pos: Int): Int =
            val start = skipSlashes(path, pos, len)
            if start >= len then nodeIdx
            else
                val segEnd = findSegmentEnd(path, start, len)
                val node   = nodes(nodeIdx)

                val literalIdx = binarySearchSegment(node.literalSegments, path, start, segEnd)
                if literalIdx >= 0 then loop(node.literalIndices(literalIdx), segEnd)
                else if node.captureChild >= 0 then loop(node.captureChild, segEnd)
                else if node.restChild >= 0 then node.restChild
                else -1
                end if
            end if
        end loop

        loop(0, 0)
    end findNodeIndexNoCaptures

    private def binarySearchSegment(
        segments: Span[String],
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
        search(0, segments.size - 1)
    end binarySearchSegment

end HttpRouter

object HttpRouter:

    case class RouteMatch(
        endpoint: HttpHandler[?, ?, ?],
        pathCaptures: Dict[String, String],
        isStreamingRequest: Boolean,
        isStreamingResponse: Boolean
    )

    enum FindError derives CanEqual:
        case MethodNotAllowed(allowedMethods: Set[HttpMethod])
        case NotFound
    end FindError

    private val NotFoundResult: Result[FindError, Nothing] = Result.fail(FindError.NotFound)

    private val MethodCount   = 9
    private val GetMethodIdx  = 0
    private val HeadMethodIdx = 5

    private[http2] def methodIndex(method: HttpMethod): Int =
        val name = method.name
        name.charAt(0) match
            case 'G' => 0
            case 'P' =>
                name.length match
                    case 3 => 1 // PUT
                    case 4 => 2 // POST
                    case 5 => 3 // PATCH
                    case _ => 8 // fallback
            case 'D' => 4
            case 'H' => 5
            case 'O' => 6
            case 'T' => 7
            case 'C' => 8
            case _   => 8 // fallback for unknown methods
        end match
    end methodIndex

    def apply(endpointSeq: Seq[HttpHandler[?, ?, ?]]): HttpRouter =
        if endpointSeq.isEmpty then
            val emptyNode = new Node(Span.empty, Span.empty, -1, -1, Span.fromUnsafe(Array.fill(MethodCount)(-1)), Set.empty)
            new HttpRouter(Span(emptyNode), Span.empty, Span.empty, Span.empty, Span.empty)
        else
            val root = new MutableNode()
            endpointSeq.foreach { ep =>
                val segments = pathToSegments(ep.route.request.path)
                insert(root, segments, ep.route.method, ep)
            }

            var nodeCount = 0
            var epCount   = 0
            countNodes(
                root,
                (n, e) =>
                    nodeCount = n; epCount = e
            )

            val flatNodes        = new Array[Node](nodeCount)
            val flatEndpoints    = new Array[HttpHandler[?, ?, ?]](epCount)
            val flatCaptureNames = new Array[Span[String]](epCount)
            val flatStreamReq    = new Array[Boolean](epCount)
            val flatStreamResp   = new Array[Boolean](epCount)
            val state            = new SerializeState(flatNodes, flatEndpoints, flatCaptureNames, flatStreamReq, flatStreamResp)
            discard(serialize(root, state))

            new HttpRouter(
                Span.fromUnsafe(flatNodes),
                Span.fromUnsafe(flatEndpoints),
                Span.fromUnsafe(flatCaptureNames),
                Span.fromUnsafe(flatStreamReq),
                Span.fromUnsafe(flatStreamResp)
            )
        end if
    end apply

    // ==================== Node ====================

    final private[http2] class Node(
        val literalSegments: Span[String],
        val literalIndices: Span[Int],
        val captureChild: Int,
        val restChild: Int,
        val endpointIndices: Span[Int],
        val allowedMethods: Set[HttpMethod]
    ):
        val hasAllowedMethods: Boolean = allowedMethods.nonEmpty

        /** Pre-computed error result to avoid allocation on the hot path. */
        val methodNotAllowedResult: Result[FindError, Nothing] =
            if hasAllowedMethods then Result.fail(FindError.MethodNotAllowed(allowedMethods))
            else NotFoundResult
    end Node

    // ==================== Build Phase ====================

    final private class MutableNode(
        var endpoints: Map[HttpMethod, HttpHandler[?, ?, ?]] = Map.empty,
        var literalChildren: Map[String, MutableNode] = Map.empty,
        var captureChild: Maybe[MutableNode] = Absent,
        var restChild: Maybe[MutableNode] = Absent
    )

    sealed private trait Segment derives CanEqual
    private object Segment:
        case class Literal(value: String) extends Segment
        case object Capture               extends Segment
        case object Rest                  extends Segment
    end Segment

    /** Not tailrec — depth equals HttpPath nesting which mirrors route segments (typically < 10). */
    private def pathToSegments(path: HttpPath[?]): List[Segment] =
        path match
            case HttpPath.Literal(s) =>
                s.split('/').filter(_.nonEmpty).map(Segment.Literal(_)).toList
            case _: HttpPath.Capture[?, ?] =>
                List(Segment.Capture)
            case HttpPath.Concat(left, right) =>
                pathToSegments(left) ++ pathToSegments(right)
            case _: HttpPath.Rest[?] =>
                List(Segment.Rest)
    end pathToSegments

    /** Extracts capture wire names from a path in order. Not tailrec — same bounded depth as pathToSegments. */
    private def extractCaptureNames(path: HttpPath[?]): Span[String] =
        val builder = scala.collection.mutable.ArrayBuffer.empty[String]
        def walk(p: HttpPath[?]): Unit =
            p match
                case HttpPath.Literal(_)          =>
                case c: HttpPath.Capture[?, ?]    => discard(builder += (if c.wireName.nonEmpty then c.wireName else c.fieldName))
                case r: HttpPath.Rest[?]          => discard(builder += r.fieldName)
                case HttpPath.Concat(left, right) => walk(left); walk(right)
        walk(path)
        Span.fromUnsafe(builder.toArray)
    end extractCaptureNames

    /** Not tailrec — depth equals the number of segments in a single route (typically < 10). */
    private def insert(
        node: MutableNode,
        segments: List[Segment],
        method: HttpMethod,
        endpoint: HttpHandler[?, ?, ?]
    ): Unit =
        segments match
            case Nil =>
                node.endpoints = node.endpoints + (method -> endpoint)
            case seg :: rest =>
                seg match
                    case Segment.Literal(value) =>
                        val childNode = node.literalChildren.get(value) match
                            case Some(n) => n
                            case None =>
                                val newNode = new MutableNode()
                                node.literalChildren = node.literalChildren + (value -> newNode)
                                newNode
                        insert(childNode, rest, method, endpoint)
                    case Segment.Capture =>
                        val childNode = node.captureChild match
                            case Present(existing) => existing
                            case Absent =>
                                val newNode = new MutableNode()
                                node.captureChild = Present(newNode)
                                newNode
                        insert(childNode, rest, method, endpoint)
                    case Segment.Rest =>
                        val childNode = node.restChild match
                            case Present(existing) => existing
                            case Absent =>
                                val newNode = new MutableNode()
                                node.restChild = Present(newNode)
                                newNode
                        childNode.endpoints = childNode.endpoints + (method -> endpoint)
        end match
    end insert

    /** Counts nodes and endpoints. Uses callback to avoid tuple allocation. Not tailrec — depth equals max route depth (typically < 10). */
    private inline def countNodes(root: MutableNode, inline callback: (Int, Int) => Unit): Unit =
        var totalNodes     = 0
        var totalEndpoints = 0
        def visit(node: MutableNode): Unit =
            totalNodes += 1
            totalEndpoints += node.endpoints.size
            node.literalChildren.values.foreach(visit)
            node.captureChild match
                case Present(child) => visit(child)
                case Absent         =>
            node.restChild match
                case Present(child) => visit(child)
                case Absent         =>
        end visit
        visit(root)
        callback(totalNodes, totalEndpoints)
    end countNodes

    // ==================== Serialization ====================

    private class SerializeState(
        val nodes: Array[Node],
        val endpoints: Array[HttpHandler[?, ?, ?]],
        val captureNames: Array[Span[String]],
        val streamReq: Array[Boolean],
        val streamResp: Array[Boolean]
    ):
        var nextNodeIdx: Int = 0
        var nextEpIdx: Int   = 0
        def allocNodeIdx(): Int =
            val idx = nextNodeIdx; nextNodeIdx += 1; idx
        def allocEpIdx(): Int =
            val idx = nextEpIdx; nextEpIdx += 1; idx
    end SerializeState

    /** Not tailrec — depth equals trie depth which mirrors max route segments (typically < 10). */
    private def serialize(node: MutableNode, state: SerializeState): Int =
        val nodeIdx = state.allocNodeIdx()

        val sortedKeys      = node.literalChildren.keys.toArray.sorted
        val literalSegments = new Array[String](sortedKeys.length)
        val literalIndices  = new Array[Int](sortedKeys.length)

        @tailrec def serializeLiterals(i: Int): Unit =
            if i < sortedKeys.length then
                val key = sortedKeys(i)
                literalSegments(i) = key
                literalIndices(i) = serialize(node.literalChildren(key), state)
                serializeLiterals(i + 1)
        serializeLiterals(0)

        val captureChildIdx = node.captureChild match
            case Present(existing) => serialize(existing, state)
            case Absent            => -1

        val restChildIdx = node.restChild match
            case Present(existing) => serialize(existing, state)
            case Absent            => -1

        val endpointIndices = Array.fill(MethodCount)(-1)
        val allowedMethods = node.endpoints.foldLeft(Set.empty[HttpMethod]) { case (methods, (method, endpoint)) =>
            val epIdx = state.allocEpIdx()
            state.endpoints(epIdx) = endpoint
            state.captureNames(epIdx) = extractCaptureNames(endpoint.route.request.path)
            state.streamReq(epIdx) = RouteUtil.isStreamingRequest(endpoint.route)
            state.streamResp(epIdx) = RouteUtil.isStreamingResponse(endpoint.route)
            endpointIndices(methodIndex(method)) = epIdx
            methods + method
        }

        state.nodes(nodeIdx) = new Node(
            Span.fromUnsafe(literalSegments),
            Span.fromUnsafe(literalIndices),
            captureChildIdx,
            restChildIdx,
            Span.fromUnsafe(endpointIndices),
            allowedMethods
        )
        nodeIdx
    end serialize

    // ==================== Path Utilities ====================

    @tailrec private def skipSlashes(path: String, pos: Int, len: Int): Int =
        if pos < len && path.charAt(pos) == '/' then skipSlashes(path, pos + 1, len)
        else pos

    @tailrec private def findSegmentEnd(path: String, pos: Int, len: Int): Int =
        if pos >= len || path.charAt(pos) == '/' then pos
        else findSegmentEnd(path, pos + 1, len)

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

    /** URL-decode a path segment, avoiding allocation when no encoding is present. */
    private def urlDecodeSegment(path: String, start: Int, end: Int): String =
        // Fast check: if no '%' in the segment, substring is sufficient
        @tailrec def hasPercent(i: Int): Boolean =
            if i >= end then false
            else if path.charAt(i) == '%' then true
            else hasPercent(i + 1)
        val raw = path.substring(start, end)
        if hasPercent(start) then java.net.URLDecoder.decode(raw, "UTF-8")
        else raw
    end urlDecodeSegment

end HttpRouter
