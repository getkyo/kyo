package kyo

import kyo.internal.DragProtocol
import kyo.internal.HtmlOp
import kyo.internal.KeyboardEventData
import kyo.internal.MouseEventData
import kyo.internal.UIEvent
import kyo.internal.UIServer

/** Direct end-to-end proof that the REAL UIServer.serveSession handler round-trips events and tears
  * down the server's per-connection subscription tree on disconnect (disconnect = teardown cascade,
  * no leaked observation).
  *
  * Leaves 1 and 2 use [[HttpWebSocket.connect]] to wire the real [[UIServer.serveSession]] body to
  * a test client without starting a real [[HttpServer]]. Both leaves are `.notNative`: while
  * [[HttpWebSocket.connect]] itself is epoll-free, the plan mandates `.notNative` on all WS-behavior
  * leaves (RI-001 rationale; leaves 3 and 4 are pure codec tests and run on every platform).
  *
  * Teardown witness (public API only): the test wraps the real `serveSession` call in `Sync.ensure`,
  * so `serverEnded` flips when the connection's handler ends (its `Scope.run` completes or is
  * interrupted on disconnect, either way closing the subscription's owning `Scope`). After the client
  * closes, `assertEventually(serverEnded)` confirms that scope closed; then a single set on a
  * test-held leaf `SignalRef` proves the subscription's observation was released (a leaked live
  * subscription would re-park on the swapped promise and keep `waiters >= 1`).
  */
class UIServerWsTest extends kyo.test.Test[Any]:

    /** Consumes the frames every session opens with: the `SessionReady` announcement the server sends before it renders, then the
      * initial render itself. A leaf starts its interaction after this, so the frame it later takes is the one its own event produced.
      */
    private def awaitSessionStart(clientWs: HttpWebSocket)(using Frame): Unit < (Async & Abort[Closed]) =
        clientWs.take().andThen(clientWs.take()).unit

    private def mediaType(value: String): Drag.MediaType = Drag.MediaType.parse(value).get

    private def dragText(representations: (String, String)*): Drag.Item.Text =
        Drag.Item.Text(representations.iterator.map((media, value) => mediaType(media) -> value).toMap)

    private def wireSize(value: ByteSize)(using kyo.test.AssertScope): DragProtocol.WireByteSize =
        DragProtocol.WireByteSize.from(value) match
            case Result.Success(value) => value
            case Result.Failure(error) => fail(error)
            case Result.Panic(error)   => throw error

    "validated drag events cannot be forged" in {
        typeCheckFailure(
            """kyo.internal.DragProtocol.ValidatedEvent.Start(kyo.internal.UIEvent.DragStart(Seq.empty, kyo.internal.DragProtocol.StartData("session", Chunk.empty, Drag.Operation.Copy, Absent, Drag.Point(0, 0), UI.Modifiers.none)), Chunk.empty)"""
        )
    }

    "wire byte sizes cannot be constructed outside browser-safe bounds" in {
        typeCheckFailure("""kyo.internal.DragProtocol.WireByteSize(1.bytes)""")
        typeCheckFailure("""val value: kyo.internal.DragProtocol.WireByteSize = 1.bytes""")
        assert(DragProtocol.WireByteSize.from(ByteSize.Zero).isSuccess)
        assert(DragProtocol.WireByteSize.from(ByteSize.fromBytes(9_007_199_254_740_991L)).isSuccess)
        assert(DragProtocol.WireByteSize.from(ByteSize.fromBytes(9_007_199_254_740_992L)).isFailure)
    }

    "validated events preserve every non-start phase" in {
        val mouse    = MouseEventData(UI.Modifiers.none, Absent)
        val keyboard = KeyboardEventData("Enter", UI.Modifiers.none, Absent)
        val target = DragProtocol.TargetData(
            "session",
            Drag.Operation.Copy,
            Absent,
            Drag.Point(0, 0),
            UI.Modifiers.none,
            Absent
        )
        val move = Drag.Move(
            Chunk("item"),
            Drag.Location("source"),
            Drag.Location("destination"),
            Absent,
            Drag.Position.Inside,
            Drag.Operation.Move
        )
        val events = Chunk[UIEvent](
            UIEvent.Click(Seq.empty, mouse),
            UIEvent.ClickSelf(Seq.empty, mouse),
            UIEvent.Input(Seq.empty, "input"),
            UIEvent.Change(Seq.empty, "change"),
            UIEvent.ChangeChecked(Seq.empty, checked = true),
            UIEvent.ChangeNumeric(Seq.empty, 1.0),
            UIEvent.Submit(Seq.empty, mouse),
            UIEvent.KeyDown(Seq.empty, keyboard),
            UIEvent.KeyUp(Seq.empty, keyboard),
            UIEvent.Focus(Seq.empty, mouse),
            UIEvent.Blur(Seq.empty, mouse),
            UIEvent.Scroll(Seq.empty, 1.0, 2.0, UI.Modifiers.none, Absent),
            UIEvent.Hover(Seq.empty, mouse),
            UIEvent.Unhover(Seq.empty, mouse),
            UIEvent.DragEnd(Seq.empty, DragProtocol.EndData("session", Drag.Operation.Copy, cancelled = false)),
            UIEvent.DragEnter(Seq.empty, target),
            UIEvent.DragLeave(Seq.empty, target),
            UIEvent.DragOver(Seq.empty, target),
            UIEvent.Drop(Seq.empty, target),
            UIEvent.SortMove(Seq.empty, "session", move)
        )
        events.foreach { event =>
            assert(DragProtocol.validateEventAndDomain(event, DragProtocol.Limits.default).map(_.wire) == Result.succeed(event))
        }
    }

    // ==================== Leaf 1: round trip ====================

    // notNative: WS-behavior leaf; see class scaladoc for RI-001 rationale.
    "round trip: event in -> render out over WS".notNative in {
        for
            ref <- Signal.initRef("before")
            // captured holds the second frame received by the client (the reactive-push after the click).
            captured <- AtomicRef.init(Absent: Maybe[String])
            app = UI.div(
                UI.button("Click").id("btn").onClick(ref.set("after")),
                ref.map(v => UI.span(v).id("val"))
            )
            _ <- Scope.run {
                HttpWebSocket.connect(
                    (serverWs: HttpWebSocket) => UIServer.serveSession(serverWs, app),
                    (clientWs: HttpWebSocket) =>
                        for
                            _ <- awaitSessionStart(clientWs)
                            // Dispatch a Click on the button (path Seq("0"): first child of the div).
                            clickEvent = UIEvent.Click(Seq("0"), MouseEventData(UI.Modifiers.none, Absent))
                            _ <- clientWs.put(HttpWebSocket.Payload.Text(Json.encode[UIEvent](clickEvent)))
                            // The next frame must replace the HTML logical range with "after".
                            frame <- clientWs.take()
                            text = frame match
                                case HttpWebSocket.Payload.Text(data) => Present(data)
                                case _                                => Absent
                            _ <- captured.set(text)
                        yield ()
                )
            }
            capturedData <- captured.get
        yield capturedData match
            case Present(data) =>
                Json.decode[HtmlOp](data) match
                    case Result.Success(HtmlOp.ReplaceRange(_, html)) => assert(html.contains("after"))
                    case other                                        => fail(s"expected HtmlOp.ReplaceRange with 'after', got: $other")
            case Absent => fail("client did not receive a second frame after the click")
        end for
    }

    // ==================== Leaf 2: disconnect tears down ====================

    // notNative: WS-behavior leaf; see class scaladoc for RI-001 rationale.
    // Stressed .times(20) to confirm non-flaky teardown cascade.
    "disconnect tears down: subscription released after socket close".notNative.times(20) in {
        for
            // The test retains leafRef to assert its observation (waiters) and to SET it after teardown.
            leafRef <- Signal.initRef(0)
            app = UI.div(leafRef.map(n => UI.span(n.toString)))
            // serverEnded flips when the real serveSession handler ends on disconnect (its Scope.run completes or is
            // interrupted, either way closing the connection's subscription Scope). Public witness, no internal hook.
            serverEnded <- AtomicBoolean.init(false)
            _ <- Scope.run {
                HttpWebSocket.connect(
                    (serverWs: HttpWebSocket) => Sync.ensure(serverEnded.set(true))(UIServer.serveSession(serverWs, app)),
                    (clientWs: HttpWebSocket) =>
                        for
                            // Confirm the server subscription is live: it parks on the test-held leaf, so leafRef has exactly one waiter.
                            _ <- awaitSessionStart(clientWs)
                            _ <- assertEventually(leafRef.waiters.map(_ == 1))
                            // Close the client: fires ws.onPeerClose on the server, ending the race and closing the
                            // connection's subscription Scope (cascade teardown).
                            _ <- clientWs.close()
                        yield ()
                )
            }
            // The connection's owning Scope closed (serveSession ended): the cascade ran.
            _ <- assertEventually(serverEnded.get)
            // Leaf witness: SET the leaf to swap its promise, discarding the parked ghost. A leaked live subscription
            // re-parks on the new promise (waiters >= 1); a torn-down one does not, so waiters settles to 0.
            _       <- leafRef.set(99)
            _       <- assertEventually(leafRef.waiters.map(_ == 0))
            waiters <- leafRef.waiters
        yield assert(waiters == 0)
        end for
    }

    // ==================== Leaf 3: HtmlOp JSON codec (cross-platform) ====================

    "HtmlOp JSON round-trips through the wire codec" in {
        val op      = HtmlOp.Replace(Seq("a", "b"), "<span>x</span>")
        val encoded = Json.encode[HtmlOp](op)
        val decoded = Json.decode[HtmlOp](encoded)
        assert(decoded == Result.Success(op))
    }

    "HtmlOp.ReplaceRange has an exact stable JSON representation and round-trips" in {
        val op      = HtmlOp.ReplaceRange("r000000010030", "<tbody><tr><td>x</td></tr></tbody>")
        val encoded = Json.encode[HtmlOp](op)
        assert(
            encoded ==
                """{"ReplaceRange":{"regionId":"r000000010030","html":"<tbody><tr><td>x</td></tr></tbody>"}}"""
        )
        assert(Json.decode[HtmlOp](encoded) == Result.Success(op))
    }

    // ==================== Leaf 4: UIEvent JSON codec (cross-platform) ====================

    "UIEvent JSON round-trips through the wire codec" in {
        val event   = UIEvent.Click(Seq("btn"), MouseEventData(UI.Modifiers.none, Absent))
        val encoded = Json.encode[UIEvent](event)
        val decoded = Json.decode[UIEvent](encoded)
        assert(decoded == Result.Success(event))
    }

    // ==================== Leaf 5: drag protocol envelopes ====================

    "DragProtocol.ClientMessage cases round-trip through exact JSON wire representations" in {
        val event = UIEvent.DragEnd(
            Seq("source"),
            DragProtocol.EndData("session-2", Drag.Operation.Link, cancelled = false)
        )
        val file = DragProtocol.FileMetaData(
            token = "file-token",
            name = "notes.txt",
            mediaType = "text/plain",
            size = wireSize(12.bytes),
            lastModified = Instant.Epoch
        )
        val cases = Chunk[(DragProtocol.ClientMessage, String)](
            DragProtocol.ClientMessage.Event(event) ->
                """{"Event":{"value":{"DragEnd":{"path":["source"],"event":{"sessionId":"session-2","operation":{"Link":{}},"cancelled":false}}}}}""",
            DragProtocol.ClientMessage.FileChunk("read-1", "AH+A/w==") ->
                """{"FileChunk":{"requestId":"read-1","bytesBase64":"AH+A/w=="}}""",
            DragProtocol.ClientMessage.FileReadComplete("read-1") ->
                """{"FileReadComplete":{"requestId":"read-1"}}""",
            DragProtocol.ClientMessage.FileEntries(
                "read-2",
                Chunk(
                    DragProtocol.EntryData.File(file),
                    DragProtocol.EntryData.Directory("dir-token", "assets")
                ),
                Present("cursor-2")
            ) ->
                """{"FileEntries":{"requestId":"read-2","entries":[{"File":{"meta":{"token":"file-token","name":"notes.txt","mediaType":"text/plain","size":12,"lastModified":"1970-01-01T00:00:00Z"}}},{"Directory":{"token":"dir-token","name":"assets"}}],"nextCursor":"cursor-2"}}""",
            DragProtocol.ClientMessage.FileEntries("read-2", Chunk.empty, Absent) ->
                """{"FileEntries":{"requestId":"read-2","entries":[]}}""",
            DragProtocol.ClientMessage.FileFailure(
                "read-3",
                DragProtocol.FileFailureData.LimitExceeded("entry limit")
            ) ->
                """{"FileFailure":{"requestId":"read-3","failure":{"LimitExceeded":{"reason":"entry limit"}}}}"""
        )

        cases.foreach { case (message, expected) =>
            val encoded = Json.encode[DragProtocol.ClientMessage](message)
            assert(encoded == expected)
            assert(Json.decode[DragProtocol.ClientMessage](encoded) == Result.succeed(message))
        }

        val stream = Chunk(
            DragProtocol.ClientMessage.FileChunk("read-1", "AH+A/w=="),
            DragProtocol.ClientMessage.FileChunk("read-1", "Zm9v"),
            DragProtocol.ClientMessage.FileReadComplete("read-1")
        )
        assert(stream.map(Json.encode[DragProtocol.ClientMessage]) == Chunk(
            """{"FileChunk":{"requestId":"read-1","bytesBase64":"AH+A/w=="}}""",
            """{"FileChunk":{"requestId":"read-1","bytesBase64":"Zm9v"}}""",
            """{"FileReadComplete":{"requestId":"read-1"}}"""
        ))
        Base64.decode("AH+A/w==") match
            case Result.Success(bytes) =>
                assert(bytes.size == 4)
                assert(bytes(0) == 0.toByte)
                assert(bytes(1) == 127.toByte)
                assert(bytes(2) == 128.toByte)
                assert(bytes(3) == 255.toByte)
            case other => fail(s"expected binary base64 payload, got $other")
        end match
    }

    "wire file sizes reject unsafe raw values before ByteSize normalization" in {
        def message(size: Long): String =
            s"""{"FileEntries":{"requestId":"read-1","entries":[{"File":{"meta":{"token":"token","name":"file.bin","mediaType":"application/octet-stream","size":$size,"lastModified":"1970-01-01T00:00:00Z"}}}],"nextCursor":"cursor"}}"""

        assert(Json.decode[DragProtocol.ClientMessage](message(-1)).isFailure)
        assert(Json.decode[DragProtocol.ClientMessage](message(9_007_199_254_740_992L)).isFailure)

        val zero    = Json.decode[DragProtocol.ClientMessage](message(0))
        val maximum = Json.decode[DragProtocol.ClientMessage](message(9_007_199_254_740_991L))
        assert(zero.isSuccess)
        assert(maximum.isSuccess)

        zero match
            case Result.Success(
                    message @ DragProtocol.ClientMessage.FileEntries(
                        _,
                        Chunk(DragProtocol.EntryData.File(meta)),
                        _
                    )
                ) =>
                assert(DragProtocol.validate(message, DragProtocol.Limits.default) == Result.succeed(message))
                assert(meta.size.value == ByteSize.Zero)
                assert(
                    meta.toDomain() == Result.succeed(Drag.FileMeta(
                        "token",
                        "file.bin",
                        mediaType("application/octet-stream"),
                        ByteSize.Zero,
                        Instant.Epoch
                    ))
                )
            case other => fail(s"expected decoded zero-sized file metadata, got $other")
        end match

        maximum match
            case Result.Success(
                    DragProtocol.ClientMessage.FileEntries(
                        _,
                        Chunk(DragProtocol.EntryData.File(meta)),
                        _
                    )
                ) => assert(meta.size.value == ByteSize.fromBytes(9_007_199_254_740_991L))
            case other => fail(s"expected decoded maximum-sized file metadata, got $other")
        end match
    }

    "DragProtocol.ClientMessage distinguishes UI events from file responses" in {
        val eventJson =
            """{"Event":{"value":{"DragEnd":{"path":["source"],"event":{"sessionId":"session-2","operation":{"Copy":{}},"cancelled":false}}}}}"""
        val chunkJson = """{"FileChunk":{"requestId":"read-1","bytesBase64":"Bw=="}}"""

        assert(
            Json.decode[DragProtocol.ClientMessage](eventJson) == Result.succeed(
                DragProtocol.ClientMessage.Event(
                    UIEvent.DragEnd(Seq("source"), DragProtocol.EndData("session-2", Drag.Operation.Copy, cancelled = false))
                )
            )
        )
        assert(
            Json.decode[DragProtocol.ClientMessage](chunkJson) == Result.succeed(
                DragProtocol.ClientMessage.FileChunk("read-1", "Bw==")
            )
        )
    }

    "DragProtocol validation rejects malformed and oversized wire data" in {
        val limits = DragProtocol.Limits(
            maxIdentifierLength = 8,
            maxPathDepth = 256,
            maxItemCount = 2,
            maxTextRepresentationCount = 2,
            maxDirectoryEntryCount = 2,
            maxTextLength = 4,
            maxNameLength = 8,
            maxMediaTypeLength = 16,
            maxReasonLength = 8,
            maxBase64Length = 12,
            maxDecodedChunkSize = 4.bytes
        )
        val item = DragProtocol.ItemData.Text(Map("text/plain" -> "text"))
        val validStart = DragProtocol.ClientMessage.Event(
            UIEvent.DragStart(
                Seq("root"),
                DragProtocol.StartData(
                    "session1",
                    Chunk(item, item),
                    Drag.Operation.Copy,
                    Present("source1"),
                    Drag.Point(0, 0),
                    UI.Modifiers.none
                )
            )
        )
        val validRepresentations = DragProtocol.ClientMessage.Event(
            UIEvent.DragStart(
                Seq("root"),
                DragProtocol.StartData(
                    "session1",
                    Chunk(DragProtocol.ItemData.Text(Map("text/plain" -> "text", "image/png" -> "data"))),
                    Drag.Operation.Copy,
                    Absent,
                    Drag.Point(0, 0),
                    UI.Modifiers.none
                )
            )
        )
        val validEntries = DragProtocol.ClientMessage.FileEntries(
            "request1",
            Chunk(
                DragProtocol.EntryData.Directory("token1", "name1"),
                DragProtocol.EntryData.Directory("token2", "name2")
            ),
            Present("cursor12")
        )

        assert(DragProtocol.validate(validStart, limits) == Result.succeed(validStart))
        validStart match
            case DragProtocol.ClientMessage.Event(UIEvent.DragStart(_, data)) =>
                assert(data.domainItems() == Result.succeed(Chunk(
                    dragText("text/plain" -> "text"),
                    dragText("text/plain" -> "text")
                )))
            case other => fail(s"expected validated drag start, got $other")
        end match
        assert(DragProtocol.validate(validRepresentations, limits) == Result.succeed(validRepresentations))
        assert(DragProtocol.validate(validEntries, limits) == Result.succeed(validEntries))
        assert(
            DragProtocol.validate(DragProtocol.ClientMessage.FileChunk("request1", "AH+A/w=="), limits) ==
                Result.succeed(DragProtocol.ClientMessage.FileChunk("request1", "AH+A/w=="))
        )
        assert(
            DragProtocol.validate(DragProtocol.ClientMessage.FileChunk("", "AH+A/w=="), limits) ==
                Result.fail(DragProtocol.ValidationFailure.Empty("requestId"))
        )
        assert(
            DragProtocol.validate(DragProtocol.ClientMessage.FileChunk("request1", ""), limits) ==
                Result.fail(DragProtocol.ValidationFailure.Empty("bytesBase64"))
        )
        assert(
            DragProtocol.validate(DragProtocol.ClientMessage.FileChunk("request1", "not-base64!"), limits) ==
                Result.fail(DragProtocol.ValidationFailure.InvalidBase64)
        )
        Chunk("A", "AAA", "AA=A", "A===", "====", "AA!A").foreach { invalid =>
            assert(
                DragProtocol.validate(DragProtocol.ClientMessage.FileChunk("request1", invalid), limits) ==
                    Result.fail(DragProtocol.ValidationFailure.InvalidBase64)
            )
        }
        assert(
            DragProtocol.validate(DragProtocol.ClientMessage.FileChunk("request1", "AB=="), limits) ==
                Result.fail(DragProtocol.ValidationFailure.InvalidBase64)
        )
        assert(
            DragProtocol.validate(DragProtocol.ClientMessage.FileChunk("request1", "AAAAAAAAAAAAAAAA"), limits) ==
                Result.fail(DragProtocol.ValidationFailure.TooLong("bytesBase64", 12, 16))
        )
        assert(
            DragProtocol.validate(DragProtocol.ClientMessage.FileChunk("request1", "AAAAAAA="), limits) ==
                Result.fail(DragProtocol.ValidationFailure.ChunkTooLarge(4.bytes, 5.bytes))
        )
        assert(
            DragProtocol.validate(DragProtocol.ClientMessage.FileChunk("request1", "AAAAAA=="), limits) ==
                Result.succeed(DragProtocol.ClientMessage.FileChunk("request1", "AAAAAA=="))
        )
        assert(
            DragProtocol.validate(
                DragProtocol.ClientMessage.Event(
                    UIEvent.DragStart(
                        Seq("root"),
                        DragProtocol.StartData(
                            "session1",
                            Chunk(item, item, item),
                            Drag.Operation.Copy,
                            Absent,
                            Drag.Point(0, 0),
                            UI.Modifiers.none
                        )
                    )
                ),
                limits
            ) == Result.fail(DragProtocol.ValidationFailure.TooMany("items", 2, 3))
        )
        assert(
            DragProtocol.validate(
                DragProtocol.ClientMessage.FileEntries(
                    "request1",
                    Chunk.fill(3)(DragProtocol.EntryData.Directory("token", "name")),
                    Present("cursor1")
                ),
                limits
            ) == Result.fail(DragProtocol.ValidationFailure.TooMany("entries", 2, 3))
        )
    }

    "DragProtocol validates wire media grammar and normalizes only during domain conversion" in {
        val start = DragProtocol.ClientMessage.Event(
            UIEvent.DragStart(
                Seq.empty,
                DragProtocol.StartData(
                    "session",
                    Chunk(DragProtocol.ItemData.Text(Map(" TEXT/PLAIN " -> "value"))),
                    Drag.Operation.Copy,
                    Absent,
                    Drag.Point(0, 0),
                    UI.Modifiers.none
                )
            )
        )
        val target = DragProtocol.TargetConfig(
            "target",
            DragProtocol.AcceptConfig(Set(" IMAGE/* "), Drag.AllowedOperations.all, Absent, Absent, directories = false),
            Absent,
            Drag.Orientation.Vertical,
            Drag.Collision.ClosestEdge
        )
        val collidingTarget = DragProtocol.TargetConfig(
            "target",
            DragProtocol.AcceptConfig(
                Set("IMAGE/*", "image/*"),
                Drag.AllowedOperations.all,
                Absent,
                Absent,
                directories = false
            ),
            Absent,
            Drag.Orientation.Vertical,
            Drag.Collision.ClosestEdge
        )
        val invalidExact = DragProtocol.ClientMessage.Event(
            UIEvent.DragStart(
                Seq.empty,
                DragProtocol.StartData(
                    "session",
                    Chunk(DragProtocol.ItemData.Text(Map("text/*" -> "value"))),
                    Drag.Operation.Copy,
                    Absent,
                    Drag.Point(0, 0),
                    UI.Modifiers.none
                )
            )
        )
        val collidingExact = DragProtocol.ClientMessage.Event(
            UIEvent.DragStart(
                Seq.empty,
                DragProtocol.StartData(
                    "session",
                    Chunk(DragProtocol.ItemData.Text(Map("TEXT/PLAIN" -> "first", "text/plain" -> "second"))),
                    Drag.Operation.Copy,
                    Absent,
                    Drag.Point(0, 0),
                    UI.Modifiers.none
                )
            )
        )

        assert(DragProtocol.validate(start, DragProtocol.Limits.default) == Result.succeed(start))
        assert(DragProtocol.validateTargetConfig(target, DragProtocol.Limits.default) == Result.succeed(target))
        assert(
            DragProtocol.validateTargetConfig(collidingTarget, DragProtocol.Limits.default) ==
                Result.fail(DragProtocol.ValidationFailure.DuplicateMediaType("image/*"))
        )
        DragProtocol.validateTargetConfigAndDomain(target, DragProtocol.Limits.default) match
            case Result.Success(validated) =>
                assert(validated.wire == target)
                assert(validated.accept.mediaTypes.map(_.render) == Set("image/*"))
            case other => fail(s"expected validated target config, got $other")
        end match
        assert(DragProtocol.validate(invalidExact, DragProtocol.Limits.default).isFailure)
        assert(
            DragProtocol.validate(collidingExact, DragProtocol.Limits.default) ==
                Result.fail(DragProtocol.ValidationFailure.DuplicateMediaType("text/plain"))
        )
        invalidExact match
            case DragProtocol.ClientMessage.Event(UIEvent.DragStart(_, data)) =>
                assert(data.domainItems().isFailure)
            case other => fail(s"expected invalid drag start, got $other")
        end match
        start match
            case DragProtocol.ClientMessage.Event(UIEvent.DragStart(_, data)) =>
                data.domainItems() match
                    case Result.Success(items) =>
                        val text = items.head.asInstanceOf[Drag.Item.Text]
                        assert(text.representations.keySet.map(_.render) == Set("text/plain"))
                    case other => fail(s"expected domain items, got $other")
            case other => fail(s"expected drag start, got $other")
        end match
    }

    "DragProtocol validation bounds event path depth and still validates segments" in {
        val limits        = DragProtocol.Limits.default
        val validPath     = Seq.fill(256)("segment")
        val oversizedPath = Seq.fill(257)("segment")
        val valid = DragProtocol.ClientMessage.Event(
            UIEvent.DragEnd(validPath, DragProtocol.EndData("session", Drag.Operation.Copy, cancelled = false))
        )
        val oversized = DragProtocol.ClientMessage.Event(
            UIEvent.DragEnd(oversizedPath, DragProtocol.EndData("session", Drag.Operation.Copy, cancelled = false))
        )
        val invalidSegment = DragProtocol.ClientMessage.Event(
            UIEvent.DragEnd(Seq("valid", ""), DragProtocol.EndData("session", Drag.Operation.Copy, cancelled = false))
        )
        val root = DragProtocol.ClientMessage.Event(
            UIEvent.DragEnd(Seq.empty, DragProtocol.EndData("session", Drag.Operation.Copy, cancelled = false))
        )

        assert(DragProtocol.validate(valid, limits) == Result.succeed(valid))
        assert(
            DragProtocol.validate(oversized, limits) ==
                Result.fail(DragProtocol.ValidationFailure.TooMany("path", 256, 257))
        )
        assert(
            DragProtocol.validate(invalidSegment, limits) ==
                Result.fail(DragProtocol.ValidationFailure.Empty("path"))
        )
        assert(DragProtocol.validate(root, limits) == Result.succeed(root))
    }

    "DragProtocol reports invalid start items before later invalid fields" in {
        val message = DragProtocol.ClientMessage.Event(
            UIEvent.DragStart(
                Seq.empty,
                DragProtocol.StartData(
                    "session",
                    Chunk(DragProtocol.ItemData.Text(Map("text/*" -> "invalid"))),
                    Drag.Operation.Copy,
                    Present(""),
                    Drag.Point(Double.NaN, Double.NaN),
                    UI.Modifiers.none
                )
            )
        )
        assert(
            DragProtocol.validate(message, DragProtocol.Limits.default) ==
                Result.fail(DragProtocol.ValidationFailure.InvalidMediaType("text/*"))
        )
    }

    "DragProtocol validation enforces identifier, metadata, and reason boundaries" in {
        val limits = DragProtocol.Limits.default
        val emptySession = DragProtocol.ClientMessage.Event(
            UIEvent.DragEnd(Seq("source"), DragProtocol.EndData("", Drag.Operation.Copy, cancelled = false))
        )
        val emptyToken = DragProtocol.ClientMessage.FileEntries(
            "read-1",
            Chunk(DragProtocol.EntryData.Directory("", "assets")),
            Absent
        )
        val emptyCursor = DragProtocol.ClientMessage.FileEntries("read-1", Chunk.empty, Present(""))
        val emptyReason = DragProtocol.ClientMessage.FileFailure(
            "read-1",
            DragProtocol.FileFailureData.Io("")
        )
        val invalidMediaType = DragProtocol.ClientMessage.FileEntries(
            "read-1",
            Chunk(DragProtocol.EntryData.File(DragProtocol.FileMetaData(
                "token",
                "file",
                "image/*",
                wireSize(1.bytes),
                Instant.Epoch
            ))),
            Absent
        )
        val invalidTimestamp = DragProtocol.ClientMessage.FileEntries(
            "read-1",
            Chunk(DragProtocol.EntryData.File(DragProtocol.FileMetaData(
                "token",
                "file",
                "image/png",
                wireSize(1.bytes),
                Instant.Max
            ))),
            Absent
        )

        val finitePoint = DragProtocol.ClientMessage.Event(
            UIEvent.DragOver(
                Seq.empty,
                DragProtocol.TargetData(
                    "session",
                    Drag.Operation.Copy,
                    Absent,
                    Drag.Point(Double.MaxValue, -Double.MaxValue),
                    UI.Modifiers.none,
                    Absent
                )
            )
        )
        val nonFinitePoints = Chunk(Double.NaN, Double.PositiveInfinity, Double.NegativeInfinity).map { coordinate =>
            DragProtocol.ClientMessage.Event(
                UIEvent.DragOver(
                    Seq.empty,
                    DragProtocol.TargetData(
                        "session",
                        Drag.Operation.Copy,
                        Absent,
                        Drag.Point(coordinate, 0),
                        UI.Modifiers.none,
                        Absent
                    )
                )
            )
        }

        assert(DragProtocol.validate(emptySession, limits) == Result.fail(DragProtocol.ValidationFailure.Empty("sessionId")))
        assert(DragProtocol.validate(emptyToken, limits) == Result.fail(DragProtocol.ValidationFailure.Empty("token")))
        assert(DragProtocol.validate(emptyCursor, limits) == Result.fail(DragProtocol.ValidationFailure.Empty("nextCursor")))
        assert(DragProtocol.validate(emptyReason, limits) == Result.fail(DragProtocol.ValidationFailure.Empty("reason")))
        assert(
            DragProtocol.validate(invalidMediaType, limits) ==
                Result.fail(DragProtocol.ValidationFailure.InvalidMediaType("image/*"))
        )
        assert(
            DragProtocol.validate(invalidTimestamp, limits) == Result.fail(DragProtocol.ValidationFailure.InvalidTimestamp)
        )
        assert(DragProtocol.validate(finitePoint, limits) == Result.succeed(finitePoint))
        nonFinitePoints.foreach { message =>
            assert(
                DragProtocol.validate(message, limits) == Result.fail(DragProtocol.ValidationFailure.InvalidNumber("point.x"))
            )
        }
    }

    "drag HtmlOp cases round-trip through exact JSON wire representations" in {
        val cases = Chunk[(HtmlOp, String)](
            HtmlOp.ReadDropFile("read-file", "file-token", 4.kib, 64.kib) ->
                """{"ReadDropFile":{"requestId":"read-file","token":"file-token","offset":4096,"maxSize":65536}}""",
            HtmlOp.ReadDropDirectory("read-dir", "dir-token", Present("cursor-1"), 50) ->
                """{"ReadDropDirectory":{"requestId":"read-dir","token":"dir-token","cursor":"cursor-1","maxEntries":50}}""",
            HtmlOp.CancelDropRead("read-file") ->
                """{"CancelDropRead":{"requestId":"read-file"}}""",
            HtmlOp.ResolveDrag("session-2", Drag.Decision.Reject(Drag.Rejection.Application("locked"))) ->
                """{"ResolveDrag":{"sessionId":"session-2","decision":{"Reject":{"rejection":{"Application":{"reason":"locked"}}}}}}"""
        )

        cases.foreach { case (op, expected) =>
            val encoded = Json.encode[HtmlOp](op)
            assert(encoded == expected)
            assert(Json.decode[HtmlOp](encoded) == Result.succeed(op))
        }
    }

    "drop decision reaches the WebSocket as one ResolveDrag frame".notNative in {
        val start = UIEvent.DragStart(
            Seq("0"),
            DragProtocol.StartData(
                "ws-drag",
                Chunk.empty,
                Drag.Operation.Move,
                Present("source"),
                Drag.Point(1, 1),
                UI.Modifiers.none
            )
        )
        val drop = UIEvent.Drop(
            Seq("0"),
            DragProtocol.TargetData(
                "ws-drag",
                Drag.Operation.Move,
                Present("target"),
                Drag.Point(2, 2),
                UI.Modifiers.none,
                Present(Drag.Position.On)
            )
        )
        for
            captured <- AtomicRef.init(Absent: Maybe[String])
            ref      <- Signal.initRef("ready")
            app = UI.div(
                UI.div
                    .dragSource(Drag.Source("source", Chunk.empty))
                    .dropTarget(Drag.Target("target", Drag.Accept()))
                    .onDrop(Drag.Decision.Accept),
                ref.map(UI.span(_))
            )
            _ <- Scope.run {
                HttpWebSocket.connect(
                    serverWs => UIServer.serveSession(serverWs, app),
                    clientWs =>
                        for
                            _     <- awaitSessionStart(clientWs)
                            _     <- clientWs.put(HttpWebSocket.Payload.Text(Json.encode[UIEvent](start)))
                            _     <- clientWs.put(HttpWebSocket.Payload.Text(Json.encode[UIEvent](drop)))
                            frame <- clientWs.take()
                            _ <- captured.set(frame match
                                case HttpWebSocket.Payload.Text(data) => Present(data)
                                case _                                => Absent)
                        yield ()
                )
            }
            actual <- captured.get
        yield assert(actual.exists(data =>
            Json.decode[HtmlOp](data) == Result.succeed(HtmlOp.ResolveDrag("ws-drag", Drag.Decision.Accept))
        ))
        end for
    }

    "malformed drag-start media is rejected without invoking handlers or terminating the session".notNative in {
        val invalidStart = UIEvent.DragStart(
            Seq("0"),
            DragProtocol.StartData(
                "bad-media",
                Chunk(DragProtocol.ItemData.Text(Map("text/*" -> "invalid"))),
                Drag.Operation.Copy,
                Absent,
                Drag.Point(1, 1),
                UI.Modifiers.none
            )
        )
        for
            starts <- AtomicInt.init(0)
            ref    <- Signal.initRef("before")
            app = UI.div(
                UI.div.onDragStart((_: Drag.Event) => starts.incrementAndGet),
                UI.button("Click").onClick(ref.set("after")),
                ref.map(UI.span(_))
            )
            _ <- Scope.run {
                HttpWebSocket.connect(
                    serverWs => UIServer.serveSession(serverWs, app),
                    clientWs =>
                        for
                            _ <- awaitSessionStart(clientWs)
                            _ <- clientWs.put(HttpWebSocket.Payload.Text(Json.encode[UIEvent](invalidStart)))
                            click = UIEvent.Click(Seq("1"), MouseEventData(UI.Modifiers.none, Absent))
                            _     <- clientWs.put(HttpWebSocket.Payload.Text(Json.encode[UIEvent](click)))
                            frame <- clientWs.take()
                        yield frame match
                            case HttpWebSocket.Payload.Text(data) => assert(data.contains("after"))
                            case other                            => fail(s"expected reactive text frame, got $other")
                )
            }
            count <- starts.get
        yield assert(count == 0)
        end for
    }

    // ==================== Leaf 6: scrollIntoView command over WS ====================

    // notNative: WS-behavior leaf; see class scaladoc for RI-001 rationale.
    "scrollIntoView: a click handler's command reaches the client as a ScrollIntoView op".notNative in {
        for
            // captured holds the frame received after the click (the command the handler sent).
            captured <- AtomicRef.init(Absent: Maybe[String])
            ref      <- Signal.initRef("static")
            // The reactive region produces the initial render frame the client awaits before clicking;
            // the handler never touches it, so the click's only response frame is the scroll command.
            app = UI.div(
                UI.button("Jump").id("btn").onClick(UI.scrollIntoView("card-7")),
                ref.map(v => UI.span(v))
            )
            _ <- Scope.run {
                HttpWebSocket.connect(
                    (serverWs: HttpWebSocket) => UIServer.serveSession(serverWs, app),
                    (clientWs: HttpWebSocket) =>
                        for
                            _ <- awaitSessionStart(clientWs)
                            clickEvent = UIEvent.Click(Seq("0"), MouseEventData(UI.Modifiers.none, Absent))
                            _     <- clientWs.put(HttpWebSocket.Payload.Text(Json.encode[UIEvent](clickEvent)))
                            frame <- clientWs.take()
                            text = frame match
                                case HttpWebSocket.Payload.Text(data) => Present(data)
                                case _                                => Absent
                            _ <- captured.set(text)
                        yield ()
                )
            }
            capturedData <- captured.get
        yield capturedData match
            case Present(data) =>
                assert(Json.decode[HtmlOp](data) == Result.Success(HtmlOp.ScrollIntoView("card-7")))
            case Absent => fail("client did not receive a frame after the click")
        end for
    }

    "scrollIntoView outside any session is a no-op" in {
        // No runner installed a sink (plain test context): the command completes without effect.
        UI.scrollIntoView("nowhere").andThen(succeed)
    }

    "ScrollIntoView JSON round-trips through the wire codec" in {
        val op      = HtmlOp.ScrollIntoView("card-7")
        val encoded = Json.encode[HtmlOp](op)
        assert(encoded.contains("ScrollIntoView"), s"the client dispatches on the op name; got $encoded")
        assert(Json.decode[HtmlOp](encoded) == Result.Success(op))
    }

end UIServerWsTest
