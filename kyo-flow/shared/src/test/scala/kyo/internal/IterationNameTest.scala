package kyo.internal

import kyo.*

/** `IterationName` exists to be the ONE place the `name#n` checkpoint scheme is written down.
  *
  * The failure a second derivation would permit is silent and total: if the side that WRITES a checkpoint and the side that reads it
  * back on resume ever disagree by one character, replay stops recognising checkpoints it wrote itself, every completed iteration
  * looks unrun, and a resumed scheduled loop repeats all of them with their side effects. Nothing fails loudly; the loop just does its
  * work twice.
  *
  * These leaves pin the writing side against the helper rather than against a literal, so the guard breaks if either side moves
  * without the other. A test asserting `"acc#0"` would keep passing while two derivations drifted apart, which is precisely the bug.
  */
class IterationNameTest extends kyo.test.Test[Any]:

    given CanEqual[Any, Any] = CanEqual.derived

    val wf1 = Flow.Id.Workflow("iteration-name-flow")

    private def withEngine[A](
        f: (FlowEngine, FlowStore, Clock.TimeControl) => A < (Async & Scope & Abort[Any])
    )(using Frame): A < (Async & Scope & Abort[Any]) =
        Clock.withTimeControl { tc =>
            FlowStore.initMemory.map { store =>
                FlowEngine.init(store, workerCount = 1, lease = 30.seconds, pollTimeout = 100.millis).map { engine =>
                    f(engine, store, tc)
                }
            }
        }

    private def settle(tc: Clock.TimeControl, maxRounds: Int)(
        cond: => Boolean < (Async & Abort[FlowStoreException])
    )(using Frame): Boolean < (Async & Abort[FlowStoreException]) =
        def go(remaining: Int): Boolean < (Async & Abort[FlowStoreException]) =
            cond.map {
                case true                => true
                case _ if remaining <= 0 => false
                case _                   => tc.advance(10.millis).andThen(go(remaining - 1))
            }
        go(maxRounds)
    end settle

    private def stepNames(events: Seq[Flow.Event]): Seq[String] =
        events.collect {
            case Flow.Event.StepCompleted(_, _, name, _)  => name
            case Flow.Event.SleepCompleted(_, _, name, _) => name
        }

    "the interpreter checkpoints iterations under the names IterationName derives" in {
        withEngine { (engine, store, tc) =>
            val flow = Flow.init("acc-loop")
                .loopOn("acc", Schedule.fixed(100.millis).repeat(1000), 0) { (state: Int, ctx) =>
                    if state >= 2 then Loop.done(state)
                    else Loop.continue(state + 1)
                }
            for
                _      <- engine.register(wf1, flow)
                handle <- engine.workflows.start(wf1)
                eid = handle.executionId
                _       <- settle(tc, maxRounds = 200)(store.getExecution(eid).map(_.exists(_.status.isTerminal)))
                history <- store.getHistory(eid, Maybe.empty, 0)
            yield
                val recorded = stepNames(history.events)
                val expected = IterationName.step("acc", 0)
                assert(
                    recorded.contains(expected),
                    s"the first iteration must be checkpointed under ${expected}, the name the resume path looks for; " +
                        s"the history records $recorded"
                )
            end for
        }
    }

    "the interpreter checkpoints iteration sleeps under the names IterationName derives" in {
        withEngine { (engine, store, tc) =>
            val flow = Flow.init("acc-loop")
                .loopOn("acc", Schedule.fixed(100.millis).repeat(1000), 0) { (state: Int, ctx) =>
                    if state >= 2 then Loop.done(state)
                    else Loop.continue(state + 1)
                }
            for
                _      <- engine.register(wf1, flow)
                handle <- engine.workflows.start(wf1)
                eid = handle.executionId
                _       <- settle(tc, maxRounds = 200)(store.getExecution(eid).map(_.exists(_.status.isTerminal)))
                history <- store.getHistory(eid, Maybe.empty, 0)
            yield
                val recorded = stepNames(history.events)
                val expected = IterationName.sleep("acc", 0)
                assert(
                    recorded.contains(expected),
                    s"the first iteration's durable sleep must be checkpointed under ${expected}, the name the resume path " +
                        s"looks for; the history records $recorded"
                )
            end for
        }
    }

    /** The reader this has to agree with is the progress surface's path resolver, not a predicate `IterationName` carries itself.
      *
      * The tracker attributes a checkpoint back to its loop by walking a recorded path up through the reserved characters, because a
      * fan-out's item (`charges~3`) and an iteration (`acc#3`) are the same shape and only the node set tells them apart. A
      * predicate on the name alone would have no reader, so it could drift from nothing and would guard nothing; the agreement worth
      * pinning is between the name the interpreter writes and the node the surface hands it to.
      */
    "an iteration's checkpoint is attributed to the loop that wrote it" in {
        val flow = Flow.input[Int]("x").loopOn("acc", Schedule.fixed(1.hour), 0)((state: Int, ctx) => Loop.done(state))
        val progress = FlowEngine.Progress.build(
            flow,
            Set("x"),
            Flow.Status.Failed("boom"),
            Dict.empty,
            failed = Map(IterationName.step("acc", 7) -> "boom")
        )
        assert(
            progress.nodeByName("acc").map(_.status) == Maybe(FlowEngine.Progress.NodeStatus.Failed("boom")),
            s"the name the interpreter derives must resolve to its loop, got ${progress.nodeByName("acc").map(_.status)}"
        )
        assert(
            !progress.nodes.exists(n => n.name != "acc" && n.status.isInstanceOf[FlowEngine.Progress.NodeStatus.Failed]),
            s"and to no other node, got ${progress.nodes.map(n => (n.name, n.status.show))}"
        )
    }

end IterationNameTest
