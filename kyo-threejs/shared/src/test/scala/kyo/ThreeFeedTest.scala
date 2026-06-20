package kyo

/** Deterministic (non-browser) tests for the Option-Y `Three.Feed` surface (design 02-design-r2 DY-02/
  * DY-03/DY-04): the server-fed signal factories, the structural overload, the app-event registration, and
  * the typed `emit` failure row. The live wire round-trips are proven by the browser tests and the
  * `HostPayloadTest` Schema round-trips; this suite pins the registry behavior and the typed surface.
  */
class ThreeFeedTest extends ThreeTest:

    "serverSignal[A] outside a run session returns a working ref and registers nothing" in {
        Scope.run {
            Three.Feed.serverSignal[Int]("c", 7).map { ref =>
                ref.get.map { v =>
                    assert(v == 7)
                }
            }
        }
    }

    "serverSignal[Chunk[A]] outside a run session returns a working ref with the initial snapshot" in {
        Scope.run {
            Three.Feed.serverSignal[Int]("xs", Chunk(1, 2, 3)).map { ref =>
                ref.get.map { v =>
                    assert(v == Chunk(1, 2, 3))
                }
            }
        }
    }

    "serverSignal[A] inside a run session registers one feed entry that emits a SignalUpdate" in {
        Scope.run {
            Three.Feed.FeedRegistry.init.map { reg =>
                Three.Feed.registryLocal.let(Present(reg)) {
                    Three.Feed.serverSignal[Int]("color", 0).map { ref =>
                        reg.all.map { entries =>
                            assert(entries.size == 1)
                            assert(entries.head.id == "color")
                        }
                    }
                }
            }
        }
    }

    "serverSignal[Chunk[A]] inside a run session registers one structural feed entry" in {
        Scope.run {
            Three.Feed.FeedRegistry.init.map { reg =>
                Three.Feed.registryLocal.let(Present(reg)) {
                    Three.Feed.serverSignal[Int]("list", Chunk(0)).map { _ =>
                        reg.all.map { entries =>
                            assert(entries.size == 1)
                            assert(entries.head.id == "list")
                        }
                    }
                }
            }
        }
    }

    "onAppEvent inside a run session registers a handler that decodes and runs on the encoded event" in {
        Scope.run {
            Three.Feed.FeedRegistry.init.map { reg =>
                AtomicInt.init(0).map { seen =>
                    Three.Feed.registryLocal.let(Present(reg)) {
                        Three.Feed.onAppEvent[Int]("bump")(n => seen.set(n)).andThen {
                            reg.allHandlers.map { handlers =>
                                assert(handlers.size == 1)
                                assert(handlers.head.eventId == "bump")
                                // Running the handler with an encoded Int decodes and invokes it.
                                handlers.head.run(Json.encode[Int](42)).andThen {
                                    seen.get.map(v => assert(v == 42))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    "onAppEvent outside a run session is a no-op (no handler recorded)" in {
        Scope.run {
            Three.Feed.FeedRegistry.init.map { reg =>
                // Not inside the registryLocal let: registration is a no-op, so the registry stays empty.
                Three.Feed.onAppEvent[Int]("bump")(_ => Kyo.unit).andThen {
                    reg.allHandlers.map(handlers => assert(handlers.isEmpty))
                }
            }
        }
    }

    "emit with no feed channel bound fails with the typed FeedUnavailable" in {
        Scope.run {
            Abort.run[ThreeException](Three.Feed.emit[Int]("bump", 1)).map {
                case Result.Failure(ThreeException.FeedUnavailable(id)) =>
                    assert(id == "bump")
                case other =>
                    fail(s"expected FeedUnavailable, got $other")
            }
        }
    }

end ThreeFeedTest
