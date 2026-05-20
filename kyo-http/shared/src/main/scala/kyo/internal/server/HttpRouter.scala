package kyo.internal.server

import kyo.*
import kyo.internal.codec.*
import scala.annotation.tailrec

/** Flat array-based HTTP router for efficient, zero-allocation path matching.
  *
  * Uses a prefix trie serialized into flat arrays for cache-friendly traversal. All nodes are stored contiguously with integer child
  * indices instead of object pointers, avoiding pointer chasing during hot path routing.
  *
  * Two lookup paths:
  *   - `find` (String path): allocates capture strings, used externally and in tests
  *   - `findParsed` (ParsedRequest + RouteLookup): zero-allocation hot path, stores segment indices into the already-parsed request instead
  *     of decoding strings
  *
  * Path capture values are extracted during the trie walk (zero second-pass). Streaming flags are pre-computed per endpoint at build time.
  * HEAD is implicitly handled wherever GET is registered (RFC 9110 §9.3.2). OPTIONS is auto-generated from registered methods.
  */
final private[kyo] class HttpRouter private (
    private val nodes: Span[HttpRouter.Node],
    private val endpoints: Span[HttpHandler[?, ?, ?]],
    private val captureWireNames: Span[Span[String]],
    private val streamingReqFlags: Span[Boolean],
    private val streamingRespFlags: Span[Boolean],
    private[kyo] val corsConfig: Maybe[HttpServerConfig.Cors] = Absent
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

    /** Find endpoint using ParsedRequest byte-level matching. Populates the reusable RouteLookup instead of allocating RouteMatch. Result
      * is stored in the lookup object.
      */
    def findParsed(method: HttpMethod, request: ParsedRequest, lookup: RouteLookup): Result[FindError, Unit] =
        val methodIdx = HttpRouter.methodIndex(method)
        lookup.reset()
        val nodeIdx = findNodeIndexParsed(request, lookup)
        resolveParsedEndpoint(nodeIdx, methodIdx, lookup)
    end findParsed

    /** Accessor: returns the endpoint handler for the matched route. */
    def endpoint(lookup: RouteLookup): HttpHandler[?, ?, ?] = endpoints(lookup.endpointIdx)

    /** Accessor: returns the capture wire names for the matched route. */
    def captureNames(lookup: RouteLookup): Span[String] = captureWireNames(lookup.endpointIdx)

    /** Trie walk using ParsedRequest segment-level comparison. */
    private def findNodeIndexParsed(request: ParsedRequest, lookup: RouteLookup): Int =
        val segCount = request.pathSegmentCount

        @tailrec def loop(nodeIdx: Int, segIdx: Int): Int =
            if segIdx >= segCount then nodeIdx
            else
                val node       = nodes(nodeIdx)
                val segStr     = request.pathSegmentAsString(segIdx)
                val literalIdx = binarySearchSegment(node.literalSegments, segStr, 0, segStr.length)
                if literalIdx >= 0 then loop(node.literalIndices(literalIdx), segIdx + 1)
                else if node.captureChild >= 0 then
                    if lookup.captureCount < lookup.captureSegmentIndices.length then
                        lookup.captureSegmentIndices(lookup.captureCount) = segIdx
                    lookup.captureCount += 1
                    loop(node.captureChild, segIdx + 1)
                else if node.restChild >= 0 then
                    if lookup.captureCount < lookup.captureSegmentIndices.length then
                        lookup.captureSegmentIndices(lookup.captureCount) = segIdx
                    lookup.restCaptureIdx = lookup.captureCount
                    lookup.captureCount += 1
                    node.restChild
                else -1
                end if
            end if
        end loop

        loop(0, 0)
    end findNodeIndexParsed

    private def resolveParsedEndpoint(nodeIdx: Int, methodIdx: Int, lookup: RouteLookup): Result[FindError, Unit] =
        if nodeIdx < 0 then NotFoundResult.asInstanceOf[Result[FindError, Unit]]
        else
            val node       = nodes(nodeIdx)
            val handlerIdx = node.endpointIndices(methodIdx)
            if handlerIdx >= 0 then
                lookup.endpointIdx = handlerIdx
                lookup.isStreamingRequest = streamingReqFlags(handlerIdx)
                lookup.isStreamingResponse = streamingRespFlags(handlerIdx)
                ResultUnit
            else if methodIdx == HeadMethodIdx then
                val getIdx = node.endpointIndices(GetMethodIdx)
                if getIdx >= 0 then
                    lookup.endpointIdx = getIdx
                    lookup.isStreamingRequest = streamingReqFlags(getIdx)
                    lookup.isStreamingResponse = streamingRespFlags(getIdx)
                    ResultUnit
                else methodNotAllowedResult(node).asInstanceOf[Result[FindError, Unit]]
                end if
            else if methodIdx == OptionsMethodIdx && node.allowedMethods.nonEmpty then
                Result.fail(FindError.Options(buildOptionsHeaders(node.allowedMethods)))
            else methodNotAllowedResult(node).asInstanceOf[Result[FindError, Unit]]
            end if
        end if
    end resolveParsedEndpoint

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
                else methodNotAllowedResult(node)
            else if methodIdx == OptionsMethodIdx && node.allowedMethods.nonEmpty then
                Result.fail(FindError.Options(buildOptionsHeaders(node.allowedMethods)))
            else methodNotAllowedResult(node)
            end if
        end if
    end resolveEndpoint

    private def findFirstEndpoint(node: Node): Int =
        @tailrec def loop(i: Int): Int =
            if i >= MethodCount then -1
            else
                val idx = node.endpointIndices(i)
                if idx >= 0 then idx
                else loop(i + 1)
        loop(0)
    end findFirstEndpoint

    private def buildOptionsHeaders(allowedMethods: Set[HttpMethod]): HttpHeaders =
        val allMethods = allowedMethods ++
            (if allowedMethods.contains(HttpMethod.GET) then Set(HttpMethod.HEAD) else Set.empty) +
            HttpMethod.OPTIONS
        val allow = allMethods.iterator.map(_.name).mkString(", ")
        val base  = HttpHeaders.empty.add("Allow", allow).add("Content-Length", "0")
        corsConfig.fold(base) { cors =>
            val h0 = base
                .add("Access-Control-Allow-Origin", cors.allowOrigin)
                .add("Access-Control-Allow-Methods", allow)
            val h1 = if cors.allowHeaders.nonEmpty then h0.add("Access-Control-Allow-Headers", cors.allowHeaders.mkString(", ")) else h0
            val h2 = if cors.exposeHeaders.nonEmpty then h1.add("Access-Control-Expose-Headers", cors.exposeHeaders.mkString(", ")) else h1
            val h3 = if cors.allowCredentials then h2.add("Access-Control-Allow-Credentials", "true") else h2
            h3.add("Access-Control-Max-Age", cors.maxAge.toString)
        }
    end buildOptionsHeaders

    private[kyo] val maxCaptures: Int =
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

private[kyo] object HttpRouter:

    case class RouteMatch(
        endpoint: HttpHandler[?, ?, ?],
        pathCaptures: Dict[String, String],
        isStreamingRequest: Boolean,
        isStreamingResponse: Boolean
    )

    enum FindError derives CanEqual:
        case MethodNotAllowed(allowedMethods: Set[HttpMethod])
        case NotFound
        case Options(headers: HttpHeaders)
    end FindError

    private val NotFoundResult: Result[FindError, Nothing] = Result.fail(FindError.NotFound)
    private val ResultUnit: Result[FindError, Unit]        = Result.succeed(())

    private val MethodCount      = 9
    private val GetMethodIdx     = 0
    private val HeadMethodIdx    = 5
    private val OptionsMethodIdx = 6

    private[kyo] def methodIndex(method: HttpMethod): Int =
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

    private def wrapWithCors(handler: HttpHandler[?, ?, ?], cors: HttpServerConfig.Cors): HttpHandler[?, ?, ?] =
        val headers = Seq("Access-Control-Allow-Origin" -> cors.allowOrigin) ++
            (if cors.allowCredentials then Seq("Access-Control-Allow-Credentials" -> "true") else Seq.empty) ++
            (if cors.exposeHeaders.nonEmpty then Seq("Access-Control-Expose-Headers" -> cors.exposeHeaders.mkString(", ")) else Seq.empty)
        HttpHandler.wrapHeaders(handler, headers)
    end wrapWithCors

    def apply(endpointSeq: Seq[HttpHandler[?, ?, ?]], cors: Maybe[HttpServerConfig.Cors]): HttpRouter =
        val handlers = cors match
            case Present(c) => endpointSeq.map(wrapWithCors(_, c))
            case Absent     => endpointSeq
        if handlers.isEmpty then
            val emptyNode = new Node(Span.empty, Span.empty, -1, -1, Span.fromUnsafe(Array.fill(MethodCount)(-1)), Set.empty)
            new HttpRouter(Span(emptyNode), Span.empty, Span.empty, Span.empty, Span.empty, cors)
        else
            val root = new MutableNode()
            handlers.foreach { ep =>
                val segments = pathToSegments(ep.route.request.path)
                val restIdx  = segments.indexOf(Segment.Rest)
                if restIdx >= 0 && restIdx < segments.size - 1 then
                    throw new IllegalArgumentException(
                        s"Rest capture must be the last segment in a path, but found trailing segments in route: ${ep.route.method} ${ep.route.request.path}"
                    )
                end if
                insert(root, segments, ep.route.method, ep)
            }

            val (nodeCount, epCount) = countNodes(root)

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
                Span.fromUnsafe(flatStreamResp),
                cors
            )
        end if
    end apply

    // ==================== Node ====================

    final private[kyo] class Node(
        val literalSegments: Span[String],
        val literalIndices: Span[Int],
        val captureChild: Int,
        val restChild: Int,
        val endpointIndices: Span[Int],
        val allowedMethods: Set[HttpMethod]
    )
    end Node

    private def methodNotAllowedResult(node: Node): Result[FindError, Nothing] =
        if node.allowedMethods.nonEmpty then Result.fail(FindError.MethodNotAllowed(node.allowedMethods))
        else NotFoundResult

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

    /** Counts nodes and endpoints. Not tailrec — depth equals max route depth (typically < 10). */
    private def countNodes(root: MutableNode): (Int, Int) =
        def visit(node: MutableNode): (Int, Int) =
            val base = (1, node.endpoints.size)
            val afterLiterals = node.literalChildren.values.foldLeft(base) { (acc, child) =>
                val (cn, ce) = visit(child)
                (acc._1 + cn, acc._2 + ce)
            }
            val afterCapture = node.captureChild.fold(afterLiterals) { child =>
                val (cn, ce) = visit(child)
                (afterLiterals._1 + cn, afterLiterals._2 + ce)
            }
            node.restChild.fold(afterCapture) { child =>
                val (cn, ce) = visit(child)
                (afterCapture._1 + cn, afterCapture._2 + ce)
            }
        end visit
        visit(root)
    end countNodes

    // ==================== Serialization ====================

    final private class SerializeState(
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
