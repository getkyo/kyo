package kyo

import kyo.internal.Platform
import scala.util.control.NoStackTrace

class LogTest extends kyo.test.Test[Any]:

    // The async tests share the process-global Log daemon, so they use Log.flush as a drain
    // fence. Concurrent flushes from parallel leaf runs complete ALL pending flush waiters on
    // each fence arrival, so a fence from test B can prematurely unblock test A. Sequential
    // execution eliminates the race: each flush is the sole waiter when it fires.
    override def config = super.config.sequential

    case object ex extends NoStackTrace

    "async flags resolve the expected -D system-property keys" in {
        assert(Log.asyncLogging.name == "kyo.Log.asyncLogging")
        assert(Log.asyncLogging.capacity.name == "kyo.Log.asyncLogging.capacity")
        assert(Log.asyncLogging.overflow.name == "kyo.Log.asyncLogging.overflow")
    }

    "log" in {
        for
            _ <- Log.trace("trace")
            _ <- Log.debug("debug")
            _ <- Log.info("info")
            _ <- Log.warn("warn")
            _ <- Log.error("error")
            _ <- Log.trace("trace", ex)
            _ <- Log.debug("debug", ex)
            _ <- Log.info("info", ex)
            _ <- Log.warn("warn", ex)
            _ <- Log.error("error", ex)
        yield succeed("all log methods complete without error")
        end for
    }

    "unsafe" in {
        import AllowUnsafe.embrace.danger
        Log.live.unsafe.trace("trace")
        Log.live.unsafe.debug("debug")
        Log.live.unsafe.info("info")
        Log.live.unsafe.warn("warn")
        Log.live.unsafe.error("error")
        Log.live.unsafe.trace("trace", ex)
        Log.live.unsafe.debug("debug", ex)
        Log.live.unsafe.info("info", ex)
        Log.live.unsafe.warn("warn", ex)
        Log.live.unsafe.error("error", ex)
        succeed("all unsafe log methods complete without error")
    }

    "withConsoleLogger" in {
        // Uses a captured sink rather than Console.withOut because the async daemon dispatches
        // on its own thread, which does not inherit thread-local DynamicVariable Console streams.
        // The ConsoleLogger format/routing is tested directly (synchronously) in "ConsoleLogger splits...".
        // This test verifies that Log.withConsoleLogger binds the named logger in scope and that
        // level gating (trace is below debug) blocks the trace call before dispatch.
        import AllowUnsafe.embrace.danger
        val captured = AtomicRef.Unsafe.init(List.empty[String])
        val captureSink = new Log.Unsafe:
            val level: Log.Level                = Log.Level.debug
            val name: String                    = "test.logger"
            def withName(n: String): Log.Unsafe = this
            private def record(pfx: String, msg: => String)(using AllowUnsafe): Unit =
                discard(captured.getAndUpdate(s"$pfx:$msg" :: _))
            def trace(msg: => String)(using Frame, AllowUnsafe): Unit                  = record("trace", msg)
            def trace(msg: => String, t: => Throwable)(using Frame, AllowUnsafe): Unit = record("trace", msg)
            def debug(msg: => String)(using Frame, AllowUnsafe): Unit                  = record("debug", msg)
            def debug(msg: => String, t: => Throwable)(using Frame, AllowUnsafe): Unit = record("debug", msg)
            def info(msg: => String)(using Frame, AllowUnsafe): Unit                   = record("info", msg)
            def info(msg: => String, t: => Throwable)(using Frame, AllowUnsafe): Unit  = record("info", msg)
            def warn(msg: => String)(using Frame, AllowUnsafe): Unit                   = record("warn", msg)
            def warn(msg: => String, t: => Throwable)(using Frame, AllowUnsafe): Unit  = record("warn", msg)
            def error(msg: => String)(using Frame, AllowUnsafe): Unit                  = record("error", msg)
            def error(msg: => String, t: => Throwable)(using Frame, AllowUnsafe): Unit = record("error", msg)
        end captureSink
        val text: String = "info message - hidden"
        for
            _ <- Log.let(Log(captureSink)) {
                for
                    _ <- Log.trace("won't show up")
                    _ <- Log.debug("test message")
                    _ <- Log.info(text.dropRight(9))
                    _ <- Log.warn("warning", new Exception("test exception"))
                yield ()
            }
            _ <- Log.flush
        yield
            val msgs = captured.get()
            assert(msgs.exists(_.startsWith("debug:test message")), s"debug not found in $msgs")
            assert(msgs.exists(_.startsWith("info:info message")), s"info not found in $msgs")
            assert(msgs.exists(_.startsWith("warn:warning")), s"warn not found in $msgs")
            assert(!msgs.exists(_.startsWith("trace:")), s"trace should be filtered at debug level: $msgs")
        end for
    }

    "ConsoleLogger (msg,t) prints the full stack trace" in {
        val errOut = new StringBuilder
        scala.Console.withErr(new java.io.PrintStream(new java.io.OutputStream:
            override def write(b: Int): Unit = errOut.append(b.toChar))) {
            import AllowUnsafe.embrace.danger
            val ex     = new RuntimeException("kaboom")
            val logger = Log.Unsafe.ConsoleLogger("test", Log.Level.trace)
            logger.error("boom", ex)
        }
        val output = errOut.toString
        assert(output.contains("kaboom"))
        assert(output.trim.contains("\n"))
        if kyo.internal.Platform.isJVM then assert(output.contains("at "))
    }

    "ConsoleLogger splits warn/error to stderr, info-and-below to stdout, line carries timestamp" in {
        val stdout = new StringBuilder
        val stderr = new StringBuilder
        scala.Console.withOut(new java.io.PrintStream(new java.io.OutputStream:
            override def write(b: Int): Unit = stdout.append(b.toChar))) {
            scala.Console.withErr(new java.io.PrintStream(new java.io.OutputStream:
                override def write(b: Int): Unit = stderr.append(b.toChar))) {
                import AllowUnsafe.embrace.danger
                val logger = Log.Unsafe.ConsoleLogger("test", Log.Level.trace)
                logger.error("errormsg")
                logger.warn("warnmsg")
                logger.info("infomsg")
                logger.debug("debugmsg")
                logger.trace("tracemsg")
            }
        }
        val out       = stdout.toString
        val err       = stderr.toString
        val tsPattern = "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}"
        assert(err.contains("errormsg"))
        assert(err.contains("warnmsg"))
        assert(!out.contains("errormsg"))
        assert(!out.contains("warnmsg"))
        assert(out.contains("infomsg"))
        assert(out.contains("debugmsg"))
        assert(out.contains("tracemsg"))
        assert(!err.contains("infomsg") && !err.contains("debugmsg") && !err.contains("tracemsg"))
        val errFirstLine = err.split("\\r?\\n").head
        val outFirstLine = out.split("\\r?\\n").head
        assert(errFirstLine.matches(s"$tsPattern.*"))
        assert(outFirstLine.matches(s"$tsPattern.*"))
    }

    "ConsoleLogger.emit renders the event's emission timestamp, not the write time" in {
        val stdout = new StringBuilder
        scala.Console.withOut(new java.io.PrintStream(new java.io.OutputStream:
            override def write(b: Int): Unit = stdout.append(b.toChar))) {
            import AllowUnsafe.embrace.danger
            val logger = Log.Unsafe.ConsoleLogger("test", Log.Level.trace)
            // Epoch is plainly not "now"; if emit re-read the clock the line would carry the current
            // year, so asserting the 1970 stamp proves the backend renders event.timestamp.
            logger.emit(Log.Event(Log.Level.info, logger, "stamped", Absent, Frame.internal, Instant.Epoch))
        }
        val line = stdout.toString
        assert(line.contains("stamped"))
        assert(line.contains("1970-01-01T00:00:00Z"))
    }

    "instance tier gates below-threshold identically to the object tier" in {
        // Uses a captured sink rather than Console.withOut because the async daemon dispatches
        // on its own thread, which does not inherit thread-local DynamicVariable Console streams.
        // Verifies: warn-level messages reach the sink; debug-level messages are silently dropped.
        import AllowUnsafe.embrace.danger
        val captured = AtomicRef.Unsafe.init(List.empty[String])
        val captureSink = new Log.Unsafe:
            val level: Log.Level                = Log.Level.warn
            val name: String                    = "test"
            def withName(n: String): Log.Unsafe = this
            private def record(pfx: String, msg: => String)(using AllowUnsafe): Unit =
                discard(captured.getAndUpdate(s"$pfx:$msg" :: _))
            def trace(msg: => String)(using Frame, AllowUnsafe): Unit                  = record("trace", msg)
            def trace(msg: => String, t: => Throwable)(using Frame, AllowUnsafe): Unit = record("trace", msg)
            def debug(msg: => String)(using Frame, AllowUnsafe): Unit                  = record("debug", msg)
            def debug(msg: => String, t: => Throwable)(using Frame, AllowUnsafe): Unit = record("debug", msg)
            def info(msg: => String)(using Frame, AllowUnsafe): Unit                   = record("info", msg)
            def info(msg: => String, t: => Throwable)(using Frame, AllowUnsafe): Unit  = record("info", msg)
            def warn(msg: => String)(using Frame, AllowUnsafe): Unit                   = record("warn", msg)
            def warn(msg: => String, t: => Throwable)(using Frame, AllowUnsafe): Unit  = record("warn", msg)
            def error(msg: => String)(using Frame, AllowUnsafe): Unit                  = record("error", msg)
            def error(msg: => String, t: => Throwable)(using Frame, AllowUnsafe): Unit = record("error", msg)
        end captureSink
        val logger = Log(captureSink)
        for
            _ <- Log.let(logger) {
                for
                    _ <- logger.warn("kept-instance")
                    _ <- Log.warn("kept-object")
                    _ <- logger.debug("dropped-instance")
                    _ <- Log.debug("dropped-object")
                yield ()
            }
            _ <- Log.flush
        yield
            val msgs = captured.get()
            assert(msgs.exists(_.contains("kept-instance")), s"kept-instance not found in $msgs")
            assert(msgs.exists(_.contains("kept-object")), s"kept-object not found in $msgs")
            assert(!msgs.exists(_.contains("dropped-instance")), s"dropped-instance should not be in $msgs")
            assert(!msgs.exists(_.contains("dropped-object")), s"dropped-object should not be in $msgs")
        end for
    }

    "below-threshold instance log does not force the msg thunk" in {
        import AllowUnsafe.embrace.danger
        val logger  = Log(Log.Unsafe.ConsoleLogger("test", Log.Level.warn))
        val counter = AtomicInt.Unsafe.init(0)
        Sync.Unsafe.evalOrThrow {
            for
                _ <- logger.debug({ counter.incrementAndGet(); "x" })
                _ <- logger.warn({ counter.incrementAndGet(); "y" })
            yield ()
        }
        assert(counter.get() == 1)
    }

    "Log.Unsafe is an abstract class with exactly name + withName, extends Serializable" in {
        // Serializable is preserved
        summon[Log.Unsafe <:< Serializable]

        // A complete anonymous subclass compiles when both name and withName are provided
        typeCheck("""
            import kyo.Log
            import kyo.AllowUnsafe
            import kyo.Frame
            val _ = new kyo.Log.Unsafe:
                def level = kyo.Log.Level.info
                def name: String = "test"
                def withName(n: String): kyo.Log.Unsafe = this
                def trace(msg: => String)(using frame: Frame, allow: AllowUnsafe): Unit = ()
                def trace(msg: => String, t: => Throwable)(using frame: Frame, allow: AllowUnsafe): Unit = ()
                def debug(msg: => String)(using frame: Frame, allow: AllowUnsafe): Unit = ()
                def debug(msg: => String, t: => Throwable)(using frame: Frame, allow: AllowUnsafe): Unit = ()
                def info(msg: => String)(using frame: Frame, allow: AllowUnsafe): Unit = ()
                def info(msg: => String, t: => Throwable)(using frame: Frame, allow: AllowUnsafe): Unit = ()
                def warn(msg: => String)(using frame: Frame, allow: AllowUnsafe): Unit = ()
                def warn(msg: => String, t: => Throwable)(using frame: Frame, allow: AllowUnsafe): Unit = ()
                def error(msg: => String)(using frame: Frame, allow: AllowUnsafe): Unit = ()
                def error(msg: => String, t: => Throwable)(using frame: Frame, allow: AllowUnsafe): Unit = ()
        """)

        // Omitting `name` is a compile error
        typeCheckFailure("""
            import kyo.Log
            import kyo.AllowUnsafe
            import kyo.Frame
            val _ = new kyo.Log.Unsafe:
                def level = kyo.Log.Level.info
                def withName(n: String): kyo.Log.Unsafe = this
                def trace(msg: => String)(using frame: Frame, allow: AllowUnsafe): Unit = ()
                def trace(msg: => String, t: => Throwable)(using frame: Frame, allow: AllowUnsafe): Unit = ()
                def debug(msg: => String)(using frame: Frame, allow: AllowUnsafe): Unit = ()
                def debug(msg: => String, t: => Throwable)(using frame: Frame, allow: AllowUnsafe): Unit = ()
                def info(msg: => String)(using frame: Frame, allow: AllowUnsafe): Unit = ()
                def info(msg: => String, t: => Throwable)(using frame: Frame, allow: AllowUnsafe): Unit = ()
                def warn(msg: => String)(using frame: Frame, allow: AllowUnsafe): Unit = ()
                def warn(msg: => String, t: => Throwable)(using frame: Frame, allow: AllowUnsafe): Unit = ()
                def error(msg: => String)(using frame: Frame, allow: AllowUnsafe): Unit = ()
                def error(msg: => String, t: => Throwable)(using frame: Frame, allow: AllowUnsafe): Unit = ()
        """)

        // Omitting `withName` is a compile error
        typeCheckFailure("""
            import kyo.Log
            import kyo.AllowUnsafe
            import kyo.Frame
            val _ = new kyo.Log.Unsafe:
                def level = kyo.Log.Level.info
                def name: String = "test"
                def trace(msg: => String)(using frame: Frame, allow: AllowUnsafe): Unit = ()
                def trace(msg: => String, t: => Throwable)(using frame: Frame, allow: AllowUnsafe): Unit = ()
                def debug(msg: => String)(using frame: Frame, allow: AllowUnsafe): Unit = ()
                def debug(msg: => String, t: => Throwable)(using frame: Frame, allow: AllowUnsafe): Unit = ()
                def info(msg: => String)(using frame: Frame, allow: AllowUnsafe): Unit = ()
                def info(msg: => String, t: => Throwable)(using frame: Frame, allow: AllowUnsafe): Unit = ()
                def warn(msg: => String)(using frame: Frame, allow: AllowUnsafe): Unit = ()
                def warn(msg: => String, t: => Throwable)(using frame: Frame, allow: AllowUnsafe): Unit = ()
                def error(msg: => String)(using frame: Frame, allow: AllowUnsafe): Unit = ()
                def error(msg: => String, t: => Throwable)(using frame: Frame, allow: AllowUnsafe): Unit = ()
        """)

        // No Log.Backend symbol resolves
        typeCheckFailure("val _ = kyo.Log.Backend")
        typeCheckFailure("val _ = kyo.Log.live.backend")
    }

    "name accessor delegates to unsafe.name" in {
        val log = Log(Log.Unsafe.ConsoleLogger("my.logger", Log.Level.warn))
        assert(log.name == "my.logger")
        assert(log.unsafe.name == "my.logger")
    }

    "Log.init derives a same-backend sibling named name" in {
        // A minimal test backend that tracks its own class for backend-class checking
        class MarkedBackend(val name: String, val level: Log.Level) extends Log.Unsafe:
            def withName(n: String): Log.Unsafe                                                      = new MarkedBackend(n, level)
            def trace(msg: => String)(using frame: Frame, allow: AllowUnsafe): Unit                  = ()
            def trace(msg: => String, t: => Throwable)(using frame: Frame, allow: AllowUnsafe): Unit = ()
            def debug(msg: => String)(using frame: Frame, allow: AllowUnsafe): Unit                  = ()
            def debug(msg: => String, t: => Throwable)(using frame: Frame, allow: AllowUnsafe): Unit = ()
            def info(msg: => String)(using frame: Frame, allow: AllowUnsafe): Unit                   = ()
            def info(msg: => String, t: => Throwable)(using frame: Frame, allow: AllowUnsafe): Unit  = ()
            def warn(msg: => String)(using frame: Frame, allow: AllowUnsafe): Unit                   = ()
            def warn(msg: => String, t: => Throwable)(using frame: Frame, allow: AllowUnsafe): Unit  = ()
            def error(msg: => String)(using frame: Frame, allow: AllowUnsafe): Unit                  = ()
            def error(msg: => String, t: => Throwable)(using frame: Frame, allow: AllowUnsafe): Unit = ()
        end MarkedBackend

        val rootLog = Log(new MarkedBackend("root", Log.Level.debug))
        Log.let(rootLog) {
            Log.init("com.example.worker").map { derived =>
                assert(derived.name == "com.example.worker")
                assert(derived.unsafe.isInstanceOf[MarkedBackend])
                assert(!derived.unsafe.isInstanceOf[Log.Unsafe.ConsoleLogger])
            }
        }
    }

    "Log.let(name) binds a sibling of the active backend for the scope" in {
        class MarkedBackend(val name: String, val level: Log.Level) extends Log.Unsafe:
            def withName(n: String): Log.Unsafe                                                      = new MarkedBackend(n, level)
            def trace(msg: => String)(using frame: Frame, allow: AllowUnsafe): Unit                  = ()
            def trace(msg: => String, t: => Throwable)(using frame: Frame, allow: AllowUnsafe): Unit = ()
            def debug(msg: => String)(using frame: Frame, allow: AllowUnsafe): Unit                  = ()
            def debug(msg: => String, t: => Throwable)(using frame: Frame, allow: AllowUnsafe): Unit = ()
            def info(msg: => String)(using frame: Frame, allow: AllowUnsafe): Unit                   = ()
            def info(msg: => String, t: => Throwable)(using frame: Frame, allow: AllowUnsafe): Unit  = ()
            def warn(msg: => String)(using frame: Frame, allow: AllowUnsafe): Unit                   = ()
            def warn(msg: => String, t: => Throwable)(using frame: Frame, allow: AllowUnsafe): Unit  = ()
            def error(msg: => String)(using frame: Frame, allow: AllowUnsafe): Unit                  = ()
            def error(msg: => String, t: => Throwable)(using frame: Frame, allow: AllowUnsafe): Unit = ()
        end MarkedBackend

        val outerLog = Log(new MarkedBackend("outer", Log.Level.debug))
        Log.let(outerLog) {
            Log.let("child") {
                Log.get.map { inner =>
                    assert(inner.name == "child")
                    assert(inner.unsafe.isInstanceOf[MarkedBackend])
                }
            }
        }.andThen {
            // After the scope, the outer logger is active
            Log.let(outerLog) {
                Log.get.map { restored =>
                    assert(restored.name == "outer")
                }
            }
        }
    }

    "child fiber inherits the ambient Log under let" in {
        class TestBackend(val name: String, val level: Log.Level) extends Log.Unsafe:
            def withName(n: String): Log.Unsafe                                                      = new TestBackend(n, level)
            def trace(msg: => String)(using frame: Frame, allow: AllowUnsafe): Unit                  = ()
            def trace(msg: => String, t: => Throwable)(using frame: Frame, allow: AllowUnsafe): Unit = ()
            def debug(msg: => String)(using frame: Frame, allow: AllowUnsafe): Unit                  = ()
            def debug(msg: => String, t: => Throwable)(using frame: Frame, allow: AllowUnsafe): Unit = ()
            def info(msg: => String)(using frame: Frame, allow: AllowUnsafe): Unit                   = ()
            def info(msg: => String, t: => Throwable)(using frame: Frame, allow: AllowUnsafe): Unit  = ()
            def warn(msg: => String)(using frame: Frame, allow: AllowUnsafe): Unit                   = ()
            def warn(msg: => String, t: => Throwable)(using frame: Frame, allow: AllowUnsafe): Unit  = ()
            def error(msg: => String)(using frame: Frame, allow: AllowUnsafe): Unit                  = ()
            def error(msg: => String, t: => Throwable)(using frame: Frame, allow: AllowUnsafe): Unit = ()
        end TestBackend

        val testLog = Log(new TestBackend("test-backend", Log.Level.debug))
        for
            ch <- Channel.init[String](2)
            _ <- Log.let(testLog) {
                Fiber.init {
                    Log.get.map(log => ch.put(log.name))
                }.map(_.get)
            }
            outsideFiber <- Fiber.init(Log.get.map(log => ch.put(log.name)))
            _            <- outsideFiber.get
            inside       <- ch.take
            outside      <- ch.take
        yield
            assert(inside == testLog.name)
            assert(outside == Log.live.name)
        end for
    }

    "call-site row is exactly Unit < Sync for all ten methods" in {
        // Positive compile-time check: the for-comprehension annotated Unit < Sync must typecheck.
        typeCheck("""
            import kyo.*
            import kyo.Frame
            def check(using Frame): Unit < Sync =
                for
                    _ <- Log.trace("t")
                    _ <- Log.debug("d")
                    _ <- Log.info("i")
                    _ <- Log.warn("w")
                    _ <- Log.error("e")
                    _ <- Log.trace("t", new Exception)
                    _ <- Log.debug("d", new Exception)
                    _ <- Log.info("i", new Exception)
                    _ <- Log.warn("w", new Exception)
                    _ <- Log.error("e", new Exception)
                yield ()
        """)
        // Paired-negative: Abort[Closed] must not be the call-site effect (the channel Result is absorbed).
        // Unit < Sync is not a subtype of Unit < Abort[Closed] (Abort[Closed] and Sync are unrelated),
        // so ascribing the result as Unit < Abort[Closed] is rejected by the compiler.
        typeCheckFailure("""
            import kyo.*
            val _: Unit < Abort[Closed] = Log.info("x")
        """)
        // Paired-negative: Scope must not be the call-site effect.
        typeCheckFailure("""
            import kyo.*
            val _: Unit < Scope = Log.info("x")
        """)
        succeed("all ten call-site types are exactly Unit < Sync, no Abort/Async/Scope leak")
    }

    "Abort[Closed] is absorbed; call-site row stays Unit < Sync" in {
        import AllowUnsafe.embrace.danger
        // Compile half: the call-site row must not be Abort[Closed] alone (channel Result absorbed).
        // Unit < Sync is not assignable to Unit < Abort[Closed], so this is rejected.
        typeCheckFailure("""
            import kyo.*
            val _: Unit < Abort[Closed] = Log.info("x")
        """)
        // Runtime positive: a normal log call completes without error.
        val result = Sync.Unsafe.evalOrThrow {
            for
                _ <- Log.info("before")
                _ <- Log.warn("after")
            yield true
        }
        assert(result)
    }

    "JS/Wasm path is synchronous and daemon never created; JVM/Native daemon exists" in {
        import AllowUnsafe.embrace.danger
        if Platform.isJVM || Platform.isNative then
            // Force the daemon by emitting an event, then assert it initialized exactly once.
            Sync.Unsafe.evalOrThrow(Log.warn("inv012-force"))
            assert(Log.daemonInitCount >= 1)
        else
            // JS/Wasm: no daemon ever created.
            assert(Log.daemonInitCount == 0)
        end if
        succeed("verified")
    }

    // Helper: builds a Log.Unsafe that prepends every dispatched message to an AtomicRef list.
    // All ten methods converge to the same append; the sink is thread-safe via CAS.
    def makeSink(ref: AtomicRef.Unsafe[List[String]], prefix: String)(using AllowUnsafe): Log.Unsafe =
        new Log.Unsafe:
            val level: Log.Level                = Log.Level.trace
            val name: String                    = s"test-$prefix"
            def withName(n: String): Log.Unsafe = makeSink(ref, n)
            private def append(m: String): Unit =
                val entry = prefix + ":" + m
                discard(ref.getAndUpdate(prev => entry :: prev))
            def trace(msg: => String)(using Frame, AllowUnsafe): Unit                  = append(msg)
            def trace(msg: => String, t: => Throwable)(using Frame, AllowUnsafe): Unit = append(msg)
            def debug(msg: => String)(using Frame, AllowUnsafe): Unit                  = append(msg)
            def debug(msg: => String, t: => Throwable)(using Frame, AllowUnsafe): Unit = append(msg)
            def info(msg: => String)(using Frame, AllowUnsafe): Unit                   = append(msg)
            def info(msg: => String, t: => Throwable)(using Frame, AllowUnsafe): Unit  = append(msg)
            def warn(msg: => String)(using Frame, AllowUnsafe): Unit                   = append(msg)
            def warn(msg: => String, t: => Throwable)(using Frame, AllowUnsafe): Unit  = append(msg)
            def error(msg: => String)(using Frame, AllowUnsafe): Unit                  = append(msg)
            def error(msg: => String, t: => Throwable)(using Frame, AllowUnsafe): Unit = append(msg)
        end new
    end makeSink

    "SyncFallback overflow writes inline on calling fiber (real full channel)" in {
        import AllowUnsafe.embrace.danger
        // Construct a capacity-1 channel with NO drain fiber. The first offer enqueues event-A
        // into the buffer (Success(true)). The second offer finds the channel full (Success(false))
        // and SyncFallback writes event-B INLINE on the calling fiber via applyOverflow.
        // The test asserts event-B appears in the sink immediately, without any drain or flush,
        // proving the SyncFallback arm executes the inline write.
        val ref  = AtomicRef.Unsafe.init(List.empty[String])
        val sink = makeSink(ref, "inv016sf")
        val ch   = Channel.Unsafe.init[Log.Event](1, Access.MultiProducerSingleConsumer)
        val evA  = Log.Event(Log.Level.info, sink, "event-A", Absent, Frame.internal, Instant.Min)
        val evB  = Log.Event(Log.Level.info, sink, "event-B", Absent, Frame.internal, Instant.Min)
        // Fill the channel: first offer succeeds (buffered).
        Log.enqueueWith(evA, ch, Log.Overflow.SyncFallback)
        // Second offer finds channel full: SyncFallback writes event-B inline immediately.
        Log.enqueueWith(evB, ch, Log.Overflow.SyncFallback)
        // event-B must be in the sink now (inline write, no drain needed).
        val msgs = ref.get()
        assert(msgs.exists(_.contains("event-B")), s"SyncFallback must write overflow inline: $msgs")
        // event-A is still in the channel buffer (no drain ran), so it is absent from the sink.
        assert(!msgs.exists(_.contains("event-A")), s"event-A should still be buffered (no drain): $msgs")
        succeed("SyncFallback overflow writes inline on the calling fiber")
    }

    "DropBelow overflow drops strictly below level, writes at-or-above inline" in {
        import AllowUnsafe.embrace.danger
        // Construct a capacity-1 channel with NO drain fiber. After filling the buffer,
        // overflow events are subject to DropBelow(warn): INFO is dropped, WARN is written inline.
        val ref    = AtomicRef.Unsafe.init(List.empty[String])
        val sink   = makeSink(ref, "inv016db")
        val ch     = Channel.Unsafe.init[Log.Event](1, Access.MultiProducerSingleConsumer)
        val evFill = Log.Event(Log.Level.info, sink, "fill", Absent, Frame.internal, Instant.Min)
        val evInfo = Log.Event(Log.Level.info, sink, "dropped-info", Absent, Frame.internal, Instant.Min)
        val evWarn = Log.Event(Log.Level.warn, sink, "kept-warn", Absent, Frame.internal, Instant.Min)
        val policy = Log.Overflow.DropBelow(Log.Level.warn)
        // Fill the channel buffer.
        Log.enqueueWith(evFill, ch, policy)
        // INFO overflow: strictly below warn -> dropped (sink not called).
        Log.enqueueWith(evInfo, ch, policy)
        // WARN overflow: at-or-above warn -> written inline.
        Log.enqueueWith(evWarn, ch, policy)
        val msgs = ref.get()
        assert(!msgs.exists(_.contains("dropped-info")), s"DropBelow must drop INFO overflow: $msgs")
        assert(msgs.exists(_.contains("kept-warn")), s"DropBelow must write WARN overflow inline: $msgs")
        succeed("DropBelow drops strictly below its level and writes at-or-above inline")
    }

    "full-channel runtime: call completes without abort, policy outcome observed in sink" in {
        import AllowUnsafe.embrace.danger
        // Drive the channel to full with no drain and assert the producing call completes normally
        // (no exception, no Abort[Closed] propagated) and the overflow event reaches the sink.
        val ref  = AtomicRef.Unsafe.init(List.empty[String])
        val sink = makeSink(ref, "inv004rt")
        val ch   = Channel.Unsafe.init[Log.Event](1, Access.MultiProducerSingleConsumer)
        val evA  = Log.Event(Log.Level.info, sink, "fill-slot", Absent, Frame.internal, Instant.Min)
        val evB  = Log.Event(Log.Level.info, sink, "overflow-event", Absent, Frame.internal, Instant.Min)
        // Fill: no throw, no abort.
        Log.enqueueWith(evA, ch, Log.Overflow.SyncFallback)
        // Overflow: SyncFallback writes inline, no throw, no abort.
        Log.enqueueWith(evB, ch, Log.Overflow.SyncFallback)
        val msgs = ref.get()
        assert(msgs.exists(_.contains("overflow-event")), s"overflow event must be written inline: $msgs")
        succeed("full-channel enqueueWith completes without abort; overflow event written inline")
    }

    "sync-mode dispatch is strictly FIFO-ordered" in {
        import AllowUnsafe.embrace.danger
        // Call LogShared.dispatch directly in enqueue order (the synchronous write path).
        // The sink prepends each message so the final list reversed == enqueue order exactly.
        val N    = 5
        val ref  = AtomicRef.Unsafe.init(List.empty[String])
        val sink = makeSink(ref, "inv017sync")
        val msgs = (0 until N).map(i => s"L$i").toList
        msgs.foreach { m =>
            val ev = Log.Event(Log.Level.info, sink, m, Absent, Frame.internal, Instant.Min)
            kyo.internal.LogShared.dispatch(ev)
        }
        val got     = ref.get().map(_.stripPrefix("inv017sync:"))
        val ordered = got.reverse
        assert(ordered == msgs, s"sync dispatch must be strictly ordered: got $ordered, expected $msgs")
        succeed("sync-mode dispatch is strictly FIFO-ordered")
    }

    "SyncFallback overflow reorder: inline write lands before buffered event is drained" in {
        import AllowUnsafe.embrace.danger
        // Capacity-1 channel, no drain fiber. Event-A fills the buffer. Event-B overflows via
        // SyncFallback and is written INLINE immediately. The sink observes B before A is ever
        // drained, demonstrating the strict total order breaks under async overflow.
        val ref  = AtomicRef.Unsafe.init(List.empty[String])
        val sink = makeSink(ref, "inv017reorder")
        val ch   = Channel.Unsafe.init[Log.Event](1, Access.MultiProducerSingleConsumer)
        val evA  = Log.Event(Log.Level.info, sink, "event-A", Absent, Frame.internal, Instant.Min)
        val evB  = Log.Event(Log.Level.info, sink, "event-B", Absent, Frame.internal, Instant.Min)
        Log.enqueueWith(evA, ch, Log.Overflow.SyncFallback) // A -> buffered in channel
        Log.enqueueWith(evB, ch, Log.Overflow.SyncFallback) // B -> overflow, written inline NOW
        val msgs = ref.get()
        // B was observed inline; A is still in the channel buffer.
        assert(msgs.exists(_.contains("event-B")), s"event-B must be written inline via SyncFallback: $msgs")
        assert(!msgs.exists(_.contains("event-A")), s"event-A must still be buffered (reorder witness): $msgs")
        succeed("SyncFallback-on-full reorder: inline write precedes still-buffered enqueue")
    }

    if Platform.isJVM || Platform.isNative then

        "Log.flush drains all buffered events, zero loss" in {
            import AllowUnsafe.embrace.danger
            val collected = AtomicRef.Unsafe.init(List.empty[String])
            val testLog   = Log(makeSink(collected, "inv003"))
            for
                _ <- Log.let(testLog) {
                    for
                        _ <- Log.info("msg1")
                        _ <- Log.info("msg2")
                        _ <- Log.info("msg3")
                        _ <- Log.warn("msg4")
                        _ <- Log.error("msg5")
                    yield ()
                }
                _ <- Log.flush
            yield
                val msgs = collected.get()
                assert(msgs.size == 5, s"expected 5 messages, got ${msgs.size}: $msgs")
            end for
        }

        "drain dispatches the event's carried sink, not the ambient Log Local" in {
            import AllowUnsafe.embrace.danger
            val ref1 = AtomicRef.Unsafe.init(List.empty[String])
            val ref2 = AtomicRef.Unsafe.init(List.empty[String])
            val log1 = Log(makeSink(ref1, "s1"))
            val log2 = Log(makeSink(ref2, "s2"))
            for
                _ <- Log.let(log1)(Log.info("from-s1-a"))
                _ <- Log.let(log1)(Log.info("from-s1-b"))
                _ <- Log.let(log2)(Log.info("from-s2-a"))
                _ <- Log.flush
            yield
                val s1 = ref1.get()
                val s2 = ref2.get()
                assert(s1.size == 2, s"expected 2 in s1, got ${s1.size}: $s1")
                assert(s2.size == 1, s"expected 1 in s2, got ${s2.size}: $s2")
                assert(s1.exists(_.contains("from-s1-a")))
                assert(s1.exists(_.contains("from-s1-b")))
                assert(s2.exists(_.contains("from-s2-a")))
            end for
        }

        "producer enqueue never parks (non-blocking offer)" in {
            import AllowUnsafe.embrace.danger
            // Proof by termination: if enqueue parked the producer, this test would time out.
            // We rely on the test framework's timeout, not a sleep.
            val count     = 200
            val collected = AtomicRef.Unsafe.init(0)
            val countSink = new Log.Unsafe:
                val level: Log.Level                                                       = Log.Level.trace
                val name: String                                                           = "inv010-sink"
                def withName(n: String): Log.Unsafe                                        = this
                def trace(msg: => String)(using Frame, AllowUnsafe): Unit                  = discard(collected.getAndUpdate(_ + 1))
                def trace(msg: => String, t: => Throwable)(using Frame, AllowUnsafe): Unit = discard(collected.getAndUpdate(_ + 1))
                def debug(msg: => String)(using Frame, AllowUnsafe): Unit                  = discard(collected.getAndUpdate(_ + 1))
                def debug(msg: => String, t: => Throwable)(using Frame, AllowUnsafe): Unit = discard(collected.getAndUpdate(_ + 1))
                def info(msg: => String)(using Frame, AllowUnsafe): Unit                   = discard(collected.getAndUpdate(_ + 1))
                def info(msg: => String, t: => Throwable)(using Frame, AllowUnsafe): Unit  = discard(collected.getAndUpdate(_ + 1))
                def warn(msg: => String)(using Frame, AllowUnsafe): Unit                   = discard(collected.getAndUpdate(_ + 1))
                def warn(msg: => String, t: => Throwable)(using Frame, AllowUnsafe): Unit  = discard(collected.getAndUpdate(_ + 1))
                def error(msg: => String)(using Frame, AllowUnsafe): Unit                  = discard(collected.getAndUpdate(_ + 1))
                def error(msg: => String, t: => Throwable)(using Frame, AllowUnsafe): Unit = discard(collected.getAndUpdate(_ + 1))
            end countSink
            val testLog = Log(countSink)
            for
                _ <- Log.let(testLog) {
                    Async.foreach(1 to count)(_ => Log.info("event"))
                }
                _ <- Log.flush
            yield
                val total = collected.get()
                assert(total == count, s"expected $count, got $total")
            end for
        }

        "daemon initializes exactly once under concurrent first use" in {
            import AllowUnsafe.embrace.danger
            val K         = 8
            val collected = AtomicRef.Unsafe.init(List.empty[String])
            val testLog   = Log(makeSink(collected, "inv011"))
            for
                _ <- Async.foreach(1 to K) { i =>
                    Log.let(testLog)(Log.info(s"racer-$i"))
                }
                _ <- Log.flush
            yield
                val initCount = Log.daemonInitCount
                assert(initCount == 1, s"expected exactly 1 daemon init, got $initCount")
                val msgs = collected.get()
                assert(msgs.size == K, s"expected $K messages, got ${msgs.size}: $msgs")
            end for
        }

        "normal-path async drain preserves FIFO enqueue order" in {
            import AllowUnsafe.embrace.danger
            // Single producer, channel comfortably above N (no overflow). The single-consumer
            // FIFO drain dequeues in enqueue order, so after flush the sink reflects enqueue order.
            val N        = 10
            val ref      = AtomicRef.Unsafe.init(List.empty[String])
            val testLog  = Log(makeSink(ref, "inv017fifo"))
            val expected = (0 until N).map(i => s"L$i").toList
            for
                _ <- Log.let(testLog) {
                    Kyo.foreach(expected)(m => Log.info(m))
                }
                _ <- Log.flush
            yield
                val got     = ref.get().map(_.stripPrefix("inv017fifo:"))
                val ordered = got.reverse
                assert(ordered == expected, s"async FIFO drain must preserve enqueue order: $ordered vs $expected")
            end for
        }

        // Builds a synthetic Event carrying the given sink, level, and message. The sink is the
        // unit under observation; level/timestamp are irrelevant to the drain dispatch path.
        def event(sink: Log.Unsafe, level: Log.Level, msg: String)(using AllowUnsafe): Log.Event =
            Log.Event(level, sink, msg, Absent, Frame.internal, Instant.Min)

        // Builds a daemon over a fresh channel of the given capacity WITHOUT starting its drain,
        // via the real internal API. Tests fill the channel and register flush waiters before the
        // drain runs, so the channel-full / fence-dropped ordering is deterministic, independent of
        // the process-global daemon and its resolve-once capacity flag.
        def newDaemon(cap: Int)(using AllowUnsafe): kyo.internal.LogDaemon.Daemon =
            val channel = Channel.Unsafe.init[Log.Event](Math.max(1, cap), Access.MultiProducerSingleConsumer)
            val waiters = Queue.Unbounded.Unsafe.init[Promise.Unsafe[Unit, Any]](Access.MultiProducerMultiConsumer)
            new kyo.internal.LogDaemon.Daemon(channel, waiters, Log.Overflow.SyncFallback)
        end newDaemon

        // Starts the real drain fiber over the daemon's channel and waiters.
        def startDrain(daemon: kyo.internal.LogDaemon.Daemon)(using AllowUnsafe, Frame): Unit =
            discard(Fiber.Unsafe.init(kyo.internal.LogDaemon.runDrain(daemon.channel, daemon.flushWaiters)))

        // Registers a flush waiter on a test daemon and awaits it, bounded by a timeout so a
        // stranded waiter surfaces as a Timeout failure (an assertion the test can observe) rather
        // than an infinite hang that stalls the whole suite. The fence is offered exactly as the
        // production flushDaemon does (best-effort wake of an idle drain); the caller decides
        // whether the channel is full when it fires.
        def registerAndAwait(daemon: kyo.internal.LogDaemon.Daemon)(using AllowUnsafe): Result[Timeout, Unit] < Async =
            val p = Promise.Unsafe.init[Unit, Any]()
            daemon.flushWaiters.add(p)
            discard(daemon.channel.offer(kyo.internal.LogShared.fence))
            p.safe.get.handle(Async.timeout(5.seconds), Abort.run[Timeout])
        end registerAndAwait

        "a throwing sink does not kill the drain; later events still dispatch and flush completes" in {
            import AllowUnsafe.embrace.danger
            // A sink that throws while dispatching, including a fatal (NonFatal-false) throwable,
            // must not end the single drain fiber: the drain contains the throw, reports it off the
            // Log path, and keeps draining. Events and the flush waiter are enqueued before the drain
            // starts, so the throwing event is dispatched first and the ordering is deterministic.
            val captured = AtomicRef.Unsafe.init(List.empty[String])
            val throwOn  = "boom"
            val sink = new Log.Unsafe:
                val level: Log.Level                = Log.Level.trace
                val name: String                    = "throwing-sink"
                def withName(n: String): Log.Unsafe = this
                private def record(msg: => String)(using AllowUnsafe): Unit =
                    val m = msg
                    // A fatal throwable (NonFatal returns false), which the drain must still contain.
                    if m == throwOn then throw new LinkageError("sink exploded")
                    else discard(captured.getAndUpdate(m :: _))
                end record
                def trace(msg: => String)(using Frame, AllowUnsafe): Unit                  = record(msg)
                def trace(msg: => String, t: => Throwable)(using Frame, AllowUnsafe): Unit = record(msg)
                def debug(msg: => String)(using Frame, AllowUnsafe): Unit                  = record(msg)
                def debug(msg: => String, t: => Throwable)(using Frame, AllowUnsafe): Unit = record(msg)
                def info(msg: => String)(using Frame, AllowUnsafe): Unit                   = record(msg)
                def info(msg: => String, t: => Throwable)(using Frame, AllowUnsafe): Unit  = record(msg)
                def warn(msg: => String)(using Frame, AllowUnsafe): Unit                   = record(msg)
                def warn(msg: => String, t: => Throwable)(using Frame, AllowUnsafe): Unit  = record(msg)
                def error(msg: => String)(using Frame, AllowUnsafe): Unit                  = record(msg)
                def error(msg: => String, t: => Throwable)(using Frame, AllowUnsafe): Unit = record(msg)
            end sink
            val daemon = newDaemon(8)
            // Interleave TWO throwing events among the good ones: the drain must survive every throw
            // and still dispatch every non-throwing event that follows.
            discard(daemon.channel.offer(event(sink, Log.Level.info, throwOn)))
            discard(daemon.channel.offer(event(sink, Log.Level.info, "after-1")))
            discard(daemon.channel.offer(event(sink, Log.Level.info, throwOn)))
            discard(daemon.channel.offer(event(sink, Log.Level.info, "after-2")))
            startDrain(daemon)
            registerAndAwait(daemon).map { flushed =>
                assert(flushed.isSuccess, s"flush must complete; the drain must survive a throwing sink: $flushed")
                val msgs = captured.get()
                assert(msgs.contains("after-1"), s"event after a throwing sink must still dispatch: $msgs")
                assert(msgs.contains("after-2"), s"event after a second throwing sink must still dispatch: $msgs")
                assert(!msgs.contains(throwOn), s"the throwing event must not be recorded: $msgs")
            }
        }

        "flush awaits dispatch of every enqueued event, not just the first" in {
            import AllowUnsafe.embrace.danger
            // Regression: when the drain pulled the whole channel into one chunk, channel.empty() read
            // true after the first dispatch, so a flush waiter completed while later events of the same
            // batch were still undispatched. Draining one event at a time keeps flush awaiting all of
            // them. The events, the waiter, and the fence are all enqueued before the drain starts, so
            // the whole batch reaches the drain together (the condition that surfaced the defect).
            val captured = AtomicRef.Unsafe.init(List.empty[String])
            val sink     = makeSink(captured, "batch")
            val daemon   = newDaemon(16)
            val n        = 8
            (0 until n).foreach(i => discard(daemon.channel.offer(event(sink, Log.Level.info, s"e$i"))))
            val p = Promise.Unsafe.init[Unit, Any]()
            daemon.flushWaiters.add(p)
            discard(daemon.channel.offer(kyo.internal.LogShared.fence))
            startDrain(daemon)
            p.safe.get.handle(Async.timeout(5.seconds), Abort.run[Timeout]).map { flushed =>
                assert(flushed.isSuccess, s"flush must complete: $flushed")
                assert(
                    captured.get().length == n,
                    s"flush must await all $n events dispatched, got ${captured.get().length}: ${captured.get()}"
                )
            }
        }

        "Log.flush completes when the fence is dropped on a full channel" in {
            import AllowUnsafe.embrace.danger
            // flush registers a waiter then offers a fence sentinel on the bounded channel; on a full
            // channel that offer is dropped. flush must still complete, because the drain completes
            // waiters when it observes the channel empty rather than relying on the fence. The channel
            // is filled and the fence dropped before the drain starts, so the dropped-fence-on-full
            // ordering is deterministic.
            val captured = AtomicRef.Unsafe.init(List.empty[String])
            val sink     = makeSink(captured, "fullbuffer")
            // Capacity-1 daemon, drain not yet started. The first offer fills the single slot.
            val daemon = newDaemon(1)
            assert(daemon.channel.offer(event(sink, Log.Level.info, "filler")).contains(true), "first offer must fill the channel")
            assert(daemon.channel.full().contains(true), "capacity-1 channel must be full after one offer")
            // Register the waiter and offer the fence while the channel is FULL: the fence is dropped.
            val p = Promise.Unsafe.init[Unit, Any]()
            daemon.flushWaiters.add(p)
            assert(daemon.channel.offer(kyo.internal.LogShared.fence).contains(false), "fence must be dropped on the full channel")
            // Now start the drain. It must reach empty and complete the stranded waiter.
            startDrain(daemon)
            p.safe.get.handle(Async.timeout(5.seconds), Abort.run[Timeout]).map { flushed =>
                assert(flushed.isSuccess, s"flush must complete even when the fence is dropped on a full channel: $flushed")
                assert(captured.get().exists(_.contains("filler")), s"the buffered event must still be dispatched: ${captured.get()}")
            }
        }

        "a sink whose toString throws does not kill the drain; later events still dispatch and flush completes" in {
            import AllowUnsafe.embrace.danger
            // The off-Log drain-failure reporter must stay total: when the failing Throwable's toString
            // itself throws, reporting must not propagate that throw and end the drain. A broken stderr
            // is not deterministically testable, but the same containment covers it.
            val captured = AtomicRef.Unsafe.init(List.empty[String])
            // A Throwable subclass whose toString itself throws, so reportDrainFailure is the failure site.
            val throwingToString = new RuntimeException("sink exploded"):
                override def toString: String = throw new RuntimeException("toString itself threw")
            val sink = new Log.Unsafe:
                val level: Log.Level                = Log.Level.trace
                val name: String                    = "throwing-tostring-sink"
                def withName(n: String): Log.Unsafe = this
                private def record(msg: => String)(using AllowUnsafe): Unit =
                    val m = msg
                    if m == "boom-tostring" then throw throwingToString
                    else discard(captured.getAndUpdate(m :: _))
                end record
                def trace(msg: => String)(using Frame, AllowUnsafe): Unit                  = record(msg)
                def trace(msg: => String, t: => Throwable)(using Frame, AllowUnsafe): Unit = record(msg)
                def debug(msg: => String)(using Frame, AllowUnsafe): Unit                  = record(msg)
                def debug(msg: => String, t: => Throwable)(using Frame, AllowUnsafe): Unit = record(msg)
                def info(msg: => String)(using Frame, AllowUnsafe): Unit                   = record(msg)
                def info(msg: => String, t: => Throwable)(using Frame, AllowUnsafe): Unit  = record(msg)
                def warn(msg: => String)(using Frame, AllowUnsafe): Unit                   = record(msg)
                def warn(msg: => String, t: => Throwable)(using Frame, AllowUnsafe): Unit  = record(msg)
                def error(msg: => String)(using Frame, AllowUnsafe): Unit                  = record(msg)
                def error(msg: => String, t: => Throwable)(using Frame, AllowUnsafe): Unit = record(msg)
            end sink
            val daemon = newDaemon(8)
            // Enqueue events before the drain starts for deterministic ordering.
            discard(daemon.channel.offer(event(sink, Log.Level.info, "boom-tostring")))
            discard(daemon.channel.offer(event(sink, Log.Level.info, "after-tostring")))
            startDrain(daemon)
            registerAndAwait(daemon).map { flushed =>
                assert(flushed.isSuccess, s"flush must complete; drain must survive a sink whose toString throws: $flushed")
                val msgs = captured.get()
                assert(msgs.contains("after-tostring"), s"event after the throwing-toString sink must still dispatch: $msgs")
                assert(!msgs.contains("boom-tostring"), s"the throwing event must not be recorded: $msgs")
            }
        }

    end if

end LogTest
