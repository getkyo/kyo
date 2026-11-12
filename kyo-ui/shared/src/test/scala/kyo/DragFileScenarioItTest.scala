package kyo

import kyo.internal.DragFiles
import kyo.internal.DragProtocol
import kyo.internal.HtmlOp

/** End-to-end scenarios for the lazy dropped file and directory read service.
  *
  * A deterministic in-test responder plays the browser side: it takes each HtmlOp request from a
  * channel and answers through the service's deliver entry, so chunking, pagination, limits,
  * cancellation, failure, and disconnect are all driven without sleeps. The final leaf drives the
  * embedded runtime in real Chrome: a synthetic external file drop on the served page flows through
  * the wire token store back to a server-side read.
  */
class DragFileScenarioItTest extends UITest:

    override def config = super.config.sequential

    private def content(size: Int): Chunk[Byte] =
        Chunk.from((0 until size).map(index => (index % 251).toByte))

    private def withService[A](limits: DragFiles.TransferLimits = DragFiles.TransferLimits())(
        f: (DragFiles.Service, Channel[HtmlOp], AtomicRef[Chunk[HtmlOp]]) => A < (Async & Scope)
    )(using Frame): A < Async =
        Scope.run {
            for
                requests <- Channel.init[HtmlOp](64)
                sent     <- AtomicRef.init(Chunk.empty[HtmlOp])
                service  <- DragFiles.Service.init(
                    op => sent.getAndUpdate(_.append(op)).andThen(Abort.run(requests.put(op)).unit),
                    limits
                )
                result <- f(service, requests, sent)
            yield result
        }

    /** Serves one file's bytes: FileChunk per ReadDropFile slice, FileReadComplete past the end. */
    private def fileResponder(
        service: DragFiles.Service,
        requests: Channel[HtmlOp],
        token: String,
        bytes: Chunk[Byte]
    )(using Frame): Unit < (Async & Abort[Closed]) =
        def loop: Unit < (Async & Abort[Closed]) =
            requests.take.map {
                case HtmlOp.ReadDropFile(requestId, requestedToken, offset, maxSize) =>
                    val reply =
                        if requestedToken != token then
                            service.deliver(DragProtocol.ClientMessage.FileFailure(
                                requestId,
                                DragProtocol.FileFailureData.InvalidToken
                            ))
                        else
                            val start = offset.toBytes.toInt
                            if start >= bytes.size then
                                service.deliver(DragProtocol.ClientMessage.FileReadComplete(requestId))
                            else
                                val slice   = bytes.drop(start).take(maxSize.toBytes.toInt)
                                val encoded = java.util.Base64.getEncoder.encodeToString(slice.toArray)
                                service.deliver(DragProtocol.ClientMessage.FileChunk(requestId, encoded))
                            end if
                    reply.andThen(loop)
                case _: HtmlOp.CancelDropRead => loop
                case _                        => loop
            }
        loop
    end fileResponder

    private val meta = Drag.FileMeta(
        "token-1",
        "notes.txt",
        Drag.MediaType.parse("text/plain").get,
        ByteSize.fromBytes(200_000L),
        Instant.Epoch
    )

    "streams a multi-chunk file read in order" in {
        val bytes = content(200_000)
        withService() { (service, requests, sent) =>
            for
                responder <- Fiber.initUnscoped(fileResponder(service, requests, "token-1", bytes))
                read      <- Abort.run[Drag.FileError](Scope.run(service.readFile(meta, 64.kib).run))
                pending   <- service.pendingCount
                _         <- responder.interrupt
            yield
                assert(read == Result.succeed(bytes))
                assert(pending == 0)
        }
    }

    "the public handle reads bytes and text through the session service" in {
        val text  = "dropped file content"
        val bytes = Chunk.from(text.getBytes("UTF-8").toIndexedSeq)
        withService() { (service, requests, sent) =>
            for
                responder <- Fiber.initUnscoped(fileResponder(service, requests, "token-1", bytes))
                file = Drag.DroppedFile.from(Drag.Item.File(meta.copy(size = ByteSize.fromBytes(bytes.size.toLong))))
                decoded <- DragFiles.local.let(Present(service)) {
                    Abort.run[Drag.FileError](Scope.run(file.text()))
                }
                pending <- service.pendingCount
                _       <- responder.interrupt
            yield
                assert(decoded == Result.succeed(text))
                assert(file.metadata.name == "notes.txt")
                assert(pending == 0)
        }
    }

    "rejects a chunk size above the per-request ceiling" in {
        withService() { (service, _, _) =>
            Abort.run[Drag.FileError](Scope.run(service.readFile(meta, 2.mib).run)).map { result =>
                assert(result == Result.fail(Drag.FileError.LimitExceeded("Chunk size exceeds 1.mib.")))
            }
        }
    }

    "rejects a buffered ceiling below the chunk size" in {
        withService(DragFiles.TransferLimits(maxChunkSize = 1.mib, maxBufferedSize = 32.kib)) { (service, _, _) =>
            Abort.run[Drag.FileError](Scope.run(service.readFile(meta, 64.kib).run)).map { result =>
                assert(result == Result.fail(Drag.FileError.LimitExceeded("Buffered ceiling is below the chunk size.")))
            }
        }
    }

    "early consumer cancellation sends CancelDropRead and clears the pending map" in {
        val bytes = content(200_000)
        withService() { (service, requests, sent) =>
            for
                responder <- Fiber.initUnscoped(fileResponder(service, requests, "token-1", bytes))
                taken     <- Abort.run[Drag.FileError](Scope.run(
                    service.readFile(meta, 64.kib).take(1_000).run
                ))
                _         <- responder.interrupt
                _         <- responder.getResult
                pending   <- service.pendingCount
                cancelled <- sent.get.map(_.exists {
                    case _: HtmlOp.CancelDropRead => true
                    case _                        => false
                })
            yield
                assert(taken.map(_.size) == Result.succeed(1_000))
                assert(pending == 0)
                assert(cancelled)
        }
    }

    "peer disconnect fails a pending read with Disconnected and clears the map" in {
        withService() { (service, _, _) =>
            for
                reader <- Fiber.initUnscoped(Abort.run[Drag.FileError](Scope.run(
                    service.readFile(meta, 64.kib).run
                )))
                _       <- assertEventually(service.pendingCount.map(_ == 1))
                _       <- service.close()
                result  <- reader.get
                pending <- service.pendingCount
            yield
                assert(result == Result.fail(Drag.FileError.Disconnected))
                assert(pending == 0)
        }
    }

    "a browser failure surfaces as the typed error" in {
        withService() { (service, requests, sent) =>
            for
                responder <- Fiber.initUnscoped(requests.take.map {
                    case HtmlOp.ReadDropFile(requestId, _, _, _) =>
                        service.deliver(DragProtocol.ClientMessage.FileFailure(
                            requestId,
                            DragProtocol.FileFailureData.PermissionDenied
                        ))
                    case _ => ()
                })
                result  <- Abort.run[Drag.FileError](Scope.run(service.readFile(meta, 64.kib).run))
                pending <- service.pendingCount
                _       <- responder.getResult
            yield
                assert(result == Result.fail(Drag.FileError.PermissionDenied))
                assert(pending == 0)
        }
    }

    "two concurrent reads correlate by request id" in {
        val first   = content(100_000)
        val second  = Chunk.from("second file".getBytes("UTF-8").toIndexedSeq)
        val metaTwo = meta.copy(
            token = "token-2",
            name = "other.txt",
            size = ByteSize.fromBytes(second.size.toLong)
        )
        withService() { (service, requests, sent) =>
            for
                responder <- Fiber.initUnscoped {
                    def loop: Unit < (Async & Abort[Closed]) =
                        requests.take.map {
                            case HtmlOp.ReadDropFile(requestId, token, offset, maxSize) =>
                                val bytes = if token == "token-1" then first else second
                                val start = offset.toBytes.toInt
                                val reply =
                                    if start >= bytes.size then
                                        service.deliver(DragProtocol.ClientMessage.FileReadComplete(requestId))
                                    else
                                        val slice   = bytes.drop(start).take(maxSize.toBytes.toInt)
                                        val encoded = java.util.Base64.getEncoder.encodeToString(slice.toArray)
                                        service.deliver(DragProtocol.ClientMessage.FileChunk(requestId, encoded))
                                reply.andThen(loop)
                            case _ => loop
                        }
                    loop
                }
                results <- Async.zip(
                    Abort.run[Drag.FileError](Scope.run(service.readFile(meta, 64.kib).run)),
                    Abort.run[Drag.FileError](Scope.run(service.readFile(metaTwo, 64.kib).run))
                )
                pending <- service.pendingCount
                _       <- responder.interrupt
            yield
                assert(results._1 == Result.succeed(first))
                assert(results._2 == Result.succeed(second))
                assert(pending == 0)
        }
    }

    "directory entries page through cursors and stop at the entry limit" in {
        def entry(name: String): DragProtocol.EntryData =
            DragProtocol.EntryData.Directory(s"token-$name", name)
        withService() { (service, requests, sent) =>
            for
                responder <- Fiber.initUnscoped {
                    def loop: Unit < (Async & Abort[Closed]) =
                        requests.take.map {
                            case HtmlOp.ReadDropDirectory(requestId, _, cursor, _) =>
                                val reply = cursor match
                                    case Absent =>
                                        service.deliver(DragProtocol.ClientMessage.FileEntries(
                                            requestId,
                                            Chunk(entry("one"), entry("two")),
                                            Present("page-2")
                                        ))
                                    case Present(_) =>
                                        service.deliver(DragProtocol.ClientMessage.FileEntries(
                                            requestId,
                                            Chunk(entry("three")),
                                            Absent
                                        ))
                                reply.andThen(loop)
                            case _ => loop
                        }
                    loop
                }
                limits = Drag.DirectoryLimits(maxDepth = 2, maxEntries = 10, maxSize = 8.mib)
                paged <- Abort.run[Drag.FileError](Scope.run(
                    service.readDirectory("dir-token", limits).run
                ))
                capped <- Abort.run[Drag.FileError](Scope.run(
                    service.readDirectory("dir-token", limits.copy(maxEntries = 2)).run
                ))
                pending <- service.pendingCount
                _       <- responder.interrupt
            yield
                assert(paged.map(_.size) == Result.succeed(3))
                assert(capped == Result.fail(Drag.FileError.LimitExceeded("Directory entry limit reached.")))
                assert(pending == 0)
        }
    }

    "rejects non-positive directory limits" in {
        withService() { (service, _, _) =>
            val limits = Drag.DirectoryLimits(maxDepth = 0, maxEntries = 10, maxSize = 8.mib)
            Abort.run[Drag.FileError](Scope.run(service.readDirectory("dir", limits).run)).map { result =>
                assert(result == Result.fail(Drag.FileError.LimitExceeded("Directory limits must be positive.")))
            }
        }
    }

    "100 read lifecycle iterations leave no pending requests" in {
        val bytes = content(200_000)
        withService() { (service, requests, sent) =>
            for
                responder <- Fiber.initUnscoped(fileResponder(service, requests, "token-1", bytes))
                _         <- Kyo.foreachDiscard(Chunk.from(0 until 100)) { iteration =>
                    for
                        cancelled <- Abort.run[Drag.FileError](Scope.run(
                            service.readFile(meta, 64.kib).take(100).run
                        ))
                        completed <- Abort.run[Drag.FileError](Scope.run(
                            service.readFile(meta.copy(size = ByteSize.fromBytes(1_000L)), 64.kib).run
                        ))
                        pending <- service.pendingCount
                    yield
                        assert(cancelled.map(_.size) == Result.succeed(100))
                        assert(completed.map(_.size) == Result.succeed(200_000))
                        assert(pending == 0)
                }
                _ <- responder.interrupt
            yield succeed
        }
    }

    "100 disconnect iterations fail pending reads and clear the map" in {
        Kyo.foreachDiscard(Chunk.from(0 until 100)) { iteration =>
            withService() { (service, _, _) =>
                for
                    reader <- Fiber.initUnscoped(Abort.run[Drag.FileError](Scope.run(
                        service.readFile(meta, 64.kib).run
                    )))
                    _       <- assertEventually(service.pendingCount.map(_ == 1))
                    _       <- service.close()
                    result  <- reader.get
                    pending <- service.pendingCount
                yield
                    assert(result == Result.fail(Drag.FileError.Disconnected))
                    assert(pending == 0)
            }
        }.andThen(succeed)
    }

    // --- Embedded runtime, real Chrome ---

    "an external file dropped on the served page reads back through the wire" in {
        val dropJs =
            """(function(){
              |var file=new File(["external drop payload"],"note.txt",{type:"text/plain"});
              |var dt=new DataTransfer();
              |dt.items.add(file);
              |var zone=document.getElementById("zone");
              |var event=new DragEvent("drop",{bubbles:true,cancelable:true,dataTransfer:dt});
              |zone.dispatchEvent(event);
              |return true;})()""".stripMargin
        for
            captured <- Signal.initRef(Absent: Maybe[String])
            app = UI.div(
                UI.div("Drop here").id("zone")
                    .dropTarget(Drag.Target(
                        "zone",
                        Drag.Accept(mediaTypes = Set(Drag.MediaTypePattern.parse("text/plain").get))
                    ))
                    .onDrop { (event: Drag.Event) =>
                        captured.set(Present("handler-entered")).andThen {
                            event.items.collectFirst { case file: Drag.Item.File => file } match
                                case Some(file) =>
                                    Abort.run[Drag.FileError](Scope.run(Drag.DroppedFile.from(file).text()))
                                        .map {
                                            case Result.Success(text) =>
                                                captured.set(Present(text)).andThen(Drag.Decision.Accept)
                                            case Result.Failure(error) =>
                                                captured.set(Present(s"read-failed:$error"))
                                                    .andThen(Drag.Decision.Reject(Drag.Rejection.Application("read failed")))
                                            case _ =>
                                                Drag.Decision.Reject(Drag.Rejection.Application("read panic"))
                                        }
                                case None =>
                                    captured.set(Present("no-file"))
                                        .andThen(Drag.Decision.Reject(Drag.Rejection.Application("no file")))
                        }
                    },
                captured.map(value => UI.span(value.getOrElse("pending")).id("result"))
            )
            _ <- withUI(app) {
                for
                    _ <- Browser.evalBoolean(dropJs)
                    _ <- Browser.assertText(Browser.Selector.id("result"), "external drop payload")
                yield ()
            }
            value <- captured.get
        yield assert(value == Present("external drop payload"))
        end for
    }
    private def externalFileEvent(event: String, zone: String, items: String): String =
        s"""(function(){var dt=new DataTransfer();
           |$items
           |var event=new DragEvent("$event",{bubbles:true,cancelable:true,dataTransfer:dt});
           |document.getElementById("$zone").dispatchEvent(event);return event.defaultPrevented;})()""".stripMargin

    private def externalTextFile(name: String): String =
        s"""dt.items.add(new File(["external"],"$name",{type:"text/plain"}));"""

    private val observeFileDecisions =
        """(function(){window.__kyoFileResolutions=0;window.__kyoFileErrors=[];
          |var sock=window.__kyoWs,original=sock.onmessage,report=console.error;
          |console.error=function(error){if(error&&error.name)window.__kyoFileErrors.push(error.name);return report.apply(this,arguments);};
          |sock.onmessage=function(event){var op=JSON.parse(event.data);try{return original.call(this,event);}
          |finally{if(op.ResolveDrag)window.__kyoFileResolutions++;}};})()""".stripMargin

    "external file hover honors accepted media types and item counts" in {
        for
            ready <- Signal.initRef(0)
            app =
                def zone(id: String, accept: Drag.Accept) =
                    UI.div(id).id(id).dropTarget(Drag.Target(id, accept)).onDrop(_ => Drag.Decision.Accept)
                UI.div(
                    zone("any", Drag.Accept()),
                    zone("images", Drag.Accept(mediaTypes = Set(Drag.MediaTypePattern.parse("image/png").get))),
                    zone("single", Drag.Accept(maxItems = Present(1))),
                    zone("small", Drag.Accept(maxFileSize = Present(1.bytes))),
                    zone("move", Drag.Accept(operations = Drag.AllowedOperations.move)),
                    UI.div(zone("nested", Drag.Accept(mediaTypes = Set(Drag.MediaTypePattern.parse("image/png").get))))
                        .id("parent").dropTarget(Drag.Target("parent", Drag.Accept())).onDrop(_ => Drag.Decision.Accept),
                    UI.button("ready").id("ready").onClick(ready.updateAndGet(_ + 1).unit),
                    ready.map(n => UI.span(n.toString).id("ready-count"))
                )
            states <- withUI(app) {
                for
                    _        <- Browser.click(Browser.Selector.id("ready"))
                    _        <- Browser.assertText(Browser.Selector.id("ready-count"), "1")
                    entered  <- Browser.evalBoolean(externalFileEvent("dragenter", "any", externalTextFile("note.txt")))
                    over     <- Browser.evalBoolean(externalFileEvent("dragover", "any", externalTextFile("note.txt")))
                    excluded <- Browser.evalBoolean(externalFileEvent("dragover", "images", externalTextFile("note.txt")))
                    overflow <- Browser.evalBoolean(externalFileEvent(
                        "dragover",
                        "single",
                        externalTextFile("one.txt") + externalTextFile("two.txt")
                    ))
                    within <- Browser.evalBoolean(externalFileEvent("dragover", "single", externalTextFile("one.txt")))
                    text   <- Browser.evalBoolean(externalFileEvent("dragover", "any", """dt.setData("text/plain","text");"""))
                    // Protected-mode hover exposes types/counts, not sizes; the size limit belongs to the drop.
                    sizeUnknown <- Browser.evalBoolean(externalFileEvent("dragover", "small", externalTextFile("note.txt")))
                    moveOnly    <- Browser.evalBoolean(externalFileEvent("dragover", "move", externalTextFile("note.txt")))
                    inherited   <- Browser.evalBoolean(externalFileEvent("dragover", "nested", externalTextFile("note.txt")))
                yield (entered, over, excluded, overflow, within, text, sizeUnknown, moveOnly, inherited)
            }
        yield assert(states == (true, true, false, false, true, false, true, false, true), s"$states")
    }

    "external file size limits are applied when the drop exposes sizes" in {
        for
            ready <- Signal.initRef(0)
            app = UI.div(
                UI.div("drop").id("zone").dropTarget(Drag.Target("zone", Drag.Accept(maxFileSize = Present(1.bytes))))
                    .onDrop(_ => Drag.Decision.Accept),
                UI.button("ready").id("ready").onClick(ready.updateAndGet(_ + 1).unit),
                ready.map(n => UI.span(n.toString).id("ready-count"))
            )
            result <- withUI(app) {
                for
                    _     <- Browser.click(Browser.Selector.id("ready"))
                    _     <- Browser.assertText(Browser.Selector.id("ready-count"), "1")
                    state <- Browser.eval(
                        """(function(){var sock=window.__kyoWs,send=sock.send,drops=0;
                          |sock.send=function(value){var msg=JSON.parse(value);if(msg.Event&&msg.Event.value.Drop)drops++;return send.call(this,value);};
                          |function drop(text){var dt=new DataTransfer();dt.items.add(new File([text],"note.txt",{type:"text/plain"}));
                          |var event=new DragEvent("drop",{bubbles:true,cancelable:true,dataTransfer:dt});document.getElementById("zone").dispatchEvent(event);
                          |return [event.defaultPrevented,drops].join('|');}
                          |try{return drop('too large')+'|'+drop('x');}finally{sock.send=send;}})()""".stripMargin
                    )
                yield state
            }
        yield assert(result == "false|0|true|1", result)
    }

    "external file drop dispatches to the accepting ancestor instead of the refusing child" in {
        for
            ready    <- Signal.initRef(0)
            received <- Signal.initRef(Chunk.empty[String])
            app = UI.div(
                UI.div(
                    UI.div("child").id("nested")
                        .dropTarget(Drag.Target("nested", Drag.Accept(mediaTypes = Set(Drag.MediaTypePattern.parse("image/png").get))))
                        .onDrop { (event: Drag.Event) =>
                            received.updateAndGet(_.append(s"child:${event.targetKey.getOrElse("")}")).andThen(Drag.Decision.Accept)
                        }
                ).id("parent").dropTarget(Drag.Target("parent", Drag.Accept()))
                    .onDrop { (event: Drag.Event) =>
                        received.updateAndGet(_.append(s"parent:${event.targetKey.getOrElse("")}")).andThen(Drag.Decision.Accept)
                    },
                UI.button("ready").id("ready").onClick(ready.updateAndGet(_ + 1).unit),
                ready.map(n => UI.span(n.toString).id("ready-count"))
            )
            _ <- withUI(app) {
                for
                    _        <- Browser.click(Browser.Selector.id("ready"))
                    _        <- Browser.assertText(Browser.Selector.id("ready-count"), "1")
                    _        <- Browser.evalDiscard(observeFileDecisions)
                    accepted <- Browser.evalBoolean(externalFileEvent("drop", "nested", externalTextFile("note.txt")))
                    _        <- Sync.defer { assert(accepted) }
                    _        <- assertEventually(Browser.evalInt("window.__kyoFileResolutions").map(_ == 1))
                yield ()
            }
            actual <- received.get
        yield assert(actual == Chunk("parent:parent"))
    }

    "external file decisions finish before the next drop and pointer drag" in {
        val pointerAfterDrop =
            """(function(){var el=document.getElementById("source"),r=el.getBoundingClientRect();
              |function fire(type,y,buttons){el.dispatchEvent(new PointerEvent(type,{bubbles:true,cancelable:true,clientX:r.left+4,clientY:y,pointerId:1,button:0,buttons:buttons,pointerType:"mouse"}));}
              |fire("pointerdown",r.top+4,1);fire("pointermove",r.top+24,1);
              |var dragging=el.getAttribute("data-kyo-dragging")==="true";fire("pointercancel",r.top+24,0);return dragging;})()""".stripMargin
        for
            ready <- Signal.initRef(0)
            names <- Signal.initRef(Chunk.empty[String])
            app = UI.div(
                UI.div("drop").id("zone").dropTarget(Drag.Target("zone", Drag.Accept())).onDrop { (event: Drag.Event) =>
                    names.updateAndGet(_ ++ event.items.collect { case Drag.Item.File(meta) => meta.name }).andThen(Drag.Decision.Accept)
                },
                UI.span("source").id("source").dragSource(Drag.Source("source", Chunk.empty, activation = Drag.Activation.Sensors)),
                UI.button("ready").id("ready").onClick(ready.updateAndGet(_ + 1).unit),
                ready.map(n => UI.span(n.toString).id("ready-count"))
            )
            _ <- withUI(app) {
                for
                    _     <- Browser.click(Browser.Selector.id("ready"))
                    _     <- Browser.assertText(Browser.Selector.id("ready-count"), "1")
                    _     <- Browser.evalDiscard(observeFileDecisions)
                    first <- Browser.evalBoolean(externalFileEvent("drop", "zone", externalTextFile("one.txt")))
                    // Observe the actual decision frame after its handler runs, regardless of whether resolve succeeds.
                    _      <- assertEventually(Browser.evalInt("window.__kyoFileResolutions").map(_ == 1))
                    errors <- Browser.eval("window.__kyoFileErrors.join(',')")
                    _      <- Sync.defer { assert(first); assert(errors == "", errors) }
                    second <- Browser.evalBoolean(externalFileEvent("drop", "zone", externalTextFile("two.txt")))
                    _      <- assertEventually(Browser.evalInt("window.__kyoFileResolutions").map(_ == 2))
                    active <- Browser.evalBoolean(pointerAfterDrop)
                yield assert(second && active)
            }
            actual <- names.get
        yield assert(actual == Chunk("one.txt", "two.txt"))
        end for
    }
end DragFileScenarioItTest
