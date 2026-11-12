package kyo

class BrowserTimeoutTest extends BrowserTest:

    override def timeout = 90.seconds

    "operation timeout preserves the current native page and its mutations" in {
        withBrowser {
            onPage("<h1 id='heading'>Original</h1>") {
                for
                    before <- Browser.url
                    inside <- Clock.withTimeControl { _ =>
                        Browser.timeout(1.second) {
                            Browser.eval("window.marker='child'; document.getElementById('heading').textContent='Updated'; 'done'")
                        }
                    }
                    after  <- Browser.url
                    marker <- Browser.eval("String(window.marker)")
                    text   <- Browser.text(Browser.Selector.id("heading"))
                yield
                    assert(inside == "done")
                    assert(after == before)
                    assert(marker == "child")
                    assert(text == "Updated")
            }
        }
    }

    "operation timeout expires under controlled time and runs synchronous releases" in {
        withBrowser {
            for
                released <- AtomicBoolean.init(false)
                result   <- Clock.withTimeControl { control =>
                    for
                        entered <- Latch.init(1)
                        driver  <- Fiber.init(entered.await.andThen(control.advance(1.second)))
                        result  <- Abort.run[Timeout](Browser.timeout(1.second) {
                            Sync.ensure(released.set(true)) {
                                Browser.eval("window.marker='timed'; 'ok'").andThen(entered.release).andThen(Async.never)
                            }
                        })
                        _ <- driver.get
                    yield result
                }
                closed <- released.get
                marker <- Browser.eval("String(window.marker)")
            yield
                result match
                    case Result.Failure(_: Timeout) => succeed
                    case other                      => fail(s"expected Timeout, got $other")
                assert(closed)
                assert(marker == "timed")
        }
    }

    "operation timeout supports an exact custom expiry error" in {
        withBrowser {
            Clock.withTimeControl { control =>
                for
                    entered <- Latch.init(1)
                    driver  <- Fiber.init(entered.await.andThen(control.advance(1.second)))
                    result  <- Abort.run[String](Browser.timeoutWithError(1.second, Result.Failure("expired")) {
                        entered.release.andThen(Async.never)
                    })
                    _ <- driver.get
                yield assert(result == Result.fail("expired"))
            }
        }
    }

    "operation timeout preserves body failures and panics" in {
        withBrowser {
            val problem = new IllegalStateException("body panic")
            for
                failed <- Abort.run[String](Browser.timeout(1.hour)(Abort.fail("body failure").andThen(42)))
                panic  <- Abort.run[Nothing](Browser.timeout(1.hour)(Abort.panic(problem).andThen(42)))
            yield
                assert(failed == Result.fail("body failure"))
                panic match
                    case Result.Panic(ex) => assert(ex eq problem)
                    case other            => fail(s"expected original panic, got $other")
            end for
        }
    }

    "operation timeout handles zero and infinite durations" in {
        withBrowser {
            for
                zero  <- Abort.run[Timeout](Browser.timeout(Duration.Zero)(Async.never))
                value <- Browser.timeout(Duration.Infinity)(Browser.eval("'infinite'"))
            yield
                zero match
                    case Result.Failure(_: Timeout) => succeed
                    case other                      => fail(s"expected zero-duration Timeout, got $other")
                assert(value == "infinite")
        }
    }

    "operation timeout compiles at its public call site without granting browser fork privileges" in {
        typeCheck("""
            import kyo.*
            def title(using Frame): String < (Browser & Abort[BrowserReadException | Timeout]) =
                Browser.timeout(1.second)(Browser.title)
        """)
        typeCheckFailure("""
            import kyo.*
            def parallel(using Frame) = Browser.timeout(1.second)(Async.zip(Browser.title, Browser.url))
        """)("Isolate")
    }

    "operation timeout preserves the caller isolation strategy for additional effects" in {
        withBrowser {
            Env.run("prefix") {
                Var.run(0) {
                    Var.isolate.update[Int].andThen(summon[Isolate[Env[String], Sync, Env[String]]]).use {
                        for
                            value <- Clock.withTimeControl { _ =>
                                Browser.timeout(1.second) {
                                    for
                                        prefix <- Env.get[String]
                                        _      <- Var.set(42)
                                        text   <- Browser.eval(s"'$prefix' + ':child'")
                                    yield text
                                }
                            }
                            state <- Var.get[Int]
                        yield
                            assert(value == "prefix:child")
                            assert(state == 42)
                    }
                }
            }
        }
    }

    "operation timeout keeps the active iframe and restores the caller frame" in {
        withBrowser {
            val parent = """<body><p id='main'>Parent</p><iframe id='frame' srcdoc="{srcdoc}"></iframe></body>"""
            Browser.goto(srcdocPage(parent, "<body><input id='field'></body>")).andThen {
                for
                    frame <- Browser.iframe(Browser.Selector.id("frame"))
                    _     <- Browser.withIFrame(frame) {
                        Clock.withTimeControl { _ =>
                            Browser.timeout(1.second)(Browser.fill(Browser.Selector.id("field"), "child"))
                        }
                    }
                    child <- Browser.withIFrame(frame)(Browser.value(Browser.Selector.id("field")))
                    main  <- Browser.text(Browser.Selector.id("main"))
                yield
                    assert(child == "child")
                    assert(main == "Parent")
            }
        }
    }

    "operation timeout nesting retains the inner expiry error" in {
        withBrowser {
            Clock.withTimeControl { control =>
                for
                    entered <- Latch.init(1)
                    driver  <- Fiber.init(entered.await.andThen(control.advance(1.second)))
                    result  <- Abort.run[String](Browser.timeoutWithError(10.seconds, Result.Failure("outer")) {
                        Browser.timeoutWithError(1.second, Result.Failure("inner")) {
                            entered.release.andThen(Async.never)
                        }
                    })
                    _ <- driver.get
                yield assert(result == Result.fail("inner"))
            }
        }
    }

    "operation timeout propagates outer interruption to its joined body" in {
        for
            entered  <- Latch.init(1)
            released <- AtomicBoolean.init(false)
            task     <- Fiber.init(withBrowser {
                Browser.timeout(1.hour)(Sync.ensure(released.set(true))(entered.release.andThen(Async.never)))
            })
            _      <- entered.await
            _      <- task.interruptAwait
            _      <- assertEventually(released.get)
            result <- task.getResult
        yield result match
            case Result.Panic(_: Interrupted) => succeed
            case other                        => fail(s"expected outer interruption, got $other")
    }

    "operation timeout preserves an already-held caller gate until its continuation ends" in {
        withBrowser {
            for
                gate      <- Meter.initMutex
                entered   <- Latch.init(1)
                competing <- AtomicBoolean.init(false)
                contender <- Fiber.init(entered.await.andThen(gate.run(competing.set(true))))
                inside    <- gate.run {
                    Clock.withTimeControl { control =>
                        for
                            driver <- Fiber.init {
                                entered.await.andThen(assertEventually(gate.pendingWaiters.map(_ == 1)))
                                    .andThen(control.advance(1.second))
                            }
                            result <- Abort.run[Timeout](Browser.timeout(1.second) {
                                Browser.eval("window.marker='nested'; 'ok'").andThen(entered.release).andThen(Async.never)
                            })
                            _      <- driver.get
                            active <- competing.get
                            marker <- Browser.eval("String(window.marker)")
                        yield (result, active, marker)
                    }
                }
                _     <- contender.get
                after <- competing.get
            yield
                inside._1 match
                    case Result.Failure(_: Timeout) => succeed
                    case other                      => fail(s"expected Timeout, got $other")
                assert(!inside._2)
                assert(inside._3 == "nested")
                assert(after)
        }
    }

    "timeout main control keeps the Browser fork guard and permits explicit cloned isolation" in {
        typeCheckFailure("""
            import kyo.*
            def timed(using Frame) = Async.timeout(1.second)(Browser.title)
        """)
        typeCheck("""
            import kyo.*
            def timedClone(using Frame) = Browser.isolate.clone.use(Async.timeout(1.second)(Browser.title))
        """)
    }

    "timeout main control bounds retry configuration without ending arbitrary waits" in {
        withBrowser {
            Browser.withTimeout(1.second) {
                Clock.withTimeControl { control =>
                    for
                        entered <- Latch.init(1)
                        release <- Latch.init(1)
                        task    <- Fiber.init(entered.release.andThen(release.await).andThen("released"))
                        _       <- entered.await
                        _       <- control.advance(3.seconds)
                        done    <- task.done
                        _       <- release.release
                        actual  <- task.get
                    yield
                        assert(!done)
                        assert(actual == "released")
                }
            }
        }
    }

    "timeout main control isolates native page mutations when the caller supplies clone" in {
        withBrowser {
            // Each real page load reports one incomplete sample, so clone restoration must take its retry path.
            val html = """<h1>Original</h1><script>
                const nativeReadyState = Object.getOwnPropertyDescriptor(Document.prototype, 'readyState').get;
                window.reportedLoading = false;
                Object.defineProperty(document, 'readyState', { get() {
                    const state = nativeReadyState.call(this);
                    if (state === 'complete' && !window.reportedLoading) {
                        window.reportedLoading = true;
                        return 'loading';
                    }
                    return state;
                }});
            </script>"""
            onPage(html) {
                for
                    _ <- Browser.eval("window.marker = 'parent'; 'ok'")
                    // The timeout clock is frozen; native load retries must not need that clock to advance.
                    child <- Browser.withConfig(_.loadSchedule(Schedule.forever)) {
                        Clock.withTimeControl { _ =>
                            Browser.isolate.clone.use(Async.timeout(1.second) {
                                for
                                    before  <- Browser.eval("String(window.marker)")
                                    _       <- Browser.eval("window.marker = 'child'; 'ok'")
                                    after   <- Browser.eval("String(window.marker)")
                                    sampled <- Browser.eval("String(window.reportedLoading)")
                                yield (before, after, sampled)
                            })
                        }
                    }
                    parent <- Browser.eval("String(window.marker)")
                yield
                    assert((child._1, child._2) == ("undefined", "child"))
                    assert(child._3 == "true")
                    assert(parent == "parent")
            }
        }
    }

end BrowserTimeoutTest
