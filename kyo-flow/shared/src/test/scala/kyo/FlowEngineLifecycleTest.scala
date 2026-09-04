package kyo

/** How an engine is built, what it holds while it runs, and what closing it does.
  *
  * An aspect file of `FlowEngineTest`, sharing its helpers through [[FlowEngineSupport]], split off for the reason
  * [[FlowEngineForeachTest]] states: a test class carries the body of every leaf it registers in its constructor and the JVM caps how
  * large a class may be, so the engine suite sits at that cap again. The lifecycle leaves are the natural aspect to lift out, because
  * each is about the engine as an object rather than about an execution: what its tuning refuses, what it retains per attempt, and
  * what it stops when its scope closes.
  */
class FlowEngineLifecycleTest extends FlowEngineSupport:

    // =========================================================================
    // Engine construction
    // =========================================================================
    "engine construction" - {

        /** What an engine holds is the attempts it is running, never the attempts it has run.
          *
          * `Fiber.init` is `Scope.acquireRelease(...)(_.interrupt)` and a scope's finalizer store only ever grows: nothing removes an
          * entry once the fiber it would interrupt has finished. An attempt whose fibers all register with the ENGINE's scope leaves
          * three entries there, the supervision fiber plus the execution and renewal fibers under it, each retaining its closure and
          * the finished fiber's result. For a process meant to run for its own lifetime that is unbounded growth in the number of
          * attempts, and an execution that parks and resumes a thousand times costs three thousand of them.
          *
          * So the two inner fibers live in a scope belonging to the attempt, which closes when the attempt ends, and the supervision
          * is spawned detached and tracked in a registry that an ending removes it from. The registry is what this reads, because it
          * is the one thing the engine keeps per attempt and no surface exposes a scope's finalizer count: the number it holds is the
          * number of attempts IN FLIGHT, which the second half pins by watching one appear.
          */
        "the engine holds no record of the attempts it has finished" in {
            Clock.withTimeControl { tc =>
                FlowStore.initMemory.map { store =>
                    val quick  = Flow.init("bounded-engine").output("y")(_ => 1)
                    val slow   = Flow.init("bounded-engine-slow").output("y")(_ => Async.sleep(1.hour).andThen(1))
                    val config = FlowEngine.Config(workerCount = 1, pollTimeout = 100.millis)
                    FlowEngine.init(store, config, quick, slow).map { engine =>
                        for
                            _ <- Kyo.foreachDiscard(1 to 8) { _ =>
                                engine.workflows.start(Flow.Id.Workflow("bounded-engine")).map { handle =>
                                    pump(tc, store, handle.executionId, _.isTerminal, 400)
                                }
                            }
                            drained <- settle(tc, step = 10.millis, maxRounds = 200)(
                                engine.supervisions.get.map(_.exists(_.isEmpty))
                            )
                            afterEight <- engine.supervisions.get
                            _          <- engine.workflows.start(Flow.Id.Workflow("bounded-engine-slow"))
                            tracked <- settle(tc, step = 10.millis, maxRounds = 200)(
                                engine.supervisions.get.map(_.exists(_.nonEmpty))
                            )
                            inFlight <- engine.supervisions.get
                        yield
                            assert(
                                drained && afterEight.exists(_.isEmpty),
                                s"eight finished attempts must leave nothing behind, got ${afterEight.map(_.size)}"
                            )
                            assert(
                                tracked && inFlight.exists(_.size == 1),
                                s"and the one attempt still running must be the only thing held, got ${inFlight.map(_.size)}"
                            )
                        end for
                    }
                }
            }
        }

        /** Closing an engine is the engine going away, and nothing about it is a fact about the store.
          *
          * The distinction has one observable place to go wrong, and it is `Health`: the worker's recovery arm and the supervision's
          * both count what they catch against `pollFailures` and put its message on `lastPollFailure`, which is what an operator reads
          * to decide whether the database is reachable. A shutdown that lands there reports a store outage that never happened.
          *
          * The one way a shutdown lands there is an attempt registering its fibers with the ENGINE's scope: a registration that races
          * the close is refused with a `Closed` panic, and neither arm picks `Closed` out the way both pick `Interrupted` out. So an
          * attempt's supervision is spawned detached and its own fibers live in a scope belonging to the attempt, leaving no
          * registration for the close to race, and a closing engine interrupts rather than refuses. `Closed` stays uncaught on
          * purpose, because a store is free to raise one of its own and that IS a store failure.
          */
        "closing an engine charges nothing to the store's health" in {
            Clock.withTimeControl { tc =>
                FlowStore.initMemory.map { store =>
                    val slow   = Flow.init("closing-engine").output("y")(_ => Async.sleep(1.hour).andThen(1))
                    val config = FlowEngine.Config(workerCount = 2, pollTimeout = 100.millis)
                    for
                        engine <- Scope.run {
                            FlowEngine.init(store, config, slow).map { engine =>
                                Kyo.foreachDiscard(1 to 4)(_ =>
                                    engine.workflows.start(Flow.Id.Workflow("closing-engine"))
                                ).andThen(
                                    settle(tc, step = 10.millis, maxRounds = 200)(
                                        engine.supervisions.get.map(_.exists(_.nonEmpty))
                                    )
                                ).andThen(engine)
                            }
                        }
                        _      <- tc.advance(500.millis)
                        health <- engine.health
                        live   <- engine.supervisions.get
                    yield
                        assert(
                            health.pollFailures == 0L && health.lastPollFailure.isEmpty,
                            s"a shutdown must not be reported as a store failure, got ${health.pollFailures} failures and " +
                                s"${health.lastPollFailure}"
                        )
                        assert(
                            live.isEmpty,
                            s"and the engine must stop tracking the attempts it interrupted, got ${live.map(_.size)}"
                        )
                    end for
                }
            }
        }

        /** Tuning and a runner are available together.
          *
          * Splitting the two across overloads, tuning on one and a runner on the other, strands every flow whose steps touch a resource
          * (which is any flow with a non-trivial effect row, and therefore the reason a runner exists) on the default lease and worker
          * count. `lease` is what decides how long crash recovery waits.
          */
        "a tuned engine runs a flow whose steps need a runner" in {
            Clock.withTimeControl { tc =>
                FlowStore.initMemory.map { store =>
                    val flow   = Flow.init("tuned").output("greeting")(_ => Env.use[String](name => s"hello $name"))
                    val config = FlowEngine.Config(workerCount = 1, lease = 5.seconds, renewEvery = 1.second, pollTimeout = 100.millis)
                    FlowEngine.init(store, config, flow)([v] => (c: v < Env[String]) => Env.run("world")(c)).map { engine =>
                        for
                            handle <- engine.workflows.start(Flow.Id.Workflow("tuned"))
                            eid = handle.executionId
                            status   <- pump(tc, store, eid, _.isTerminal)
                            greeting <- store.getField[String](eid, "greeting")
                        yield
                            assert(status == Flow.Status.Completed, s"expected Completed, got $status")
                            assert(greeting == Present("hello world"), s"the runner must have supplied the effect, got $greeting")
                        end for
                    }
                }
            }
        }

        "the untuned overloads carry the default config" in {
            assert(FlowEngine.Config.default == FlowEngine.Config())
            assert(FlowEngine.Config.default.workerCount == 2)
            assert(FlowEngine.Config.default.lease == 30.seconds)
        }

        /** Every `runHandlers` overload binds the same way in a for-comprehension.
          *
          * An overload answering a bare `Chunk` makes `handlers <- Flow.runHandlers(engine)` bind over `Chunk`'s own `flatMap`, so the
          * rest of the comprehension runs once per handler with `handlers` being a single one. A type error catches that only when the
          * body's types happen to disagree.
          */
        "runHandlers binds as one value in a kyo comprehension" in {
            Clock.withTimeControl { _ =>
                FlowStore.initMemory.map { store =>
                    val flow = Flow.init("handlers").output("y")(_ => 1)
                    FlowEngine.init(store, flow).map { engine =>
                        for
                            handlers <- Flow.runHandlers(engine)
                            again    <- Flow.runHandlers(store, flow)
                        yield
                            assert(handlers.size > 1, s"the engine overload must answer every handler, got ${handlers.size}")
                            assert(
                                handlers.size == again.size,
                                s"every overload answers the same handlers, ${handlers.size} vs ${again.size}"
                            )
                        end for
                    }
                }
            }
        }
    }
end FlowEngineLifecycleTest
