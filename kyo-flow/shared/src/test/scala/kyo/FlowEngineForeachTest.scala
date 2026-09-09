package kyo

/** What a `foreach` makes durable, per item, and what a resumed attempt does with it.
  *
  * An aspect file of `FlowEngineTest`, sharing its helpers through [[FlowEngineSupport]]. The split is forced rather than stylistic: a
  * test class carries the body of every leaf it registers in its constructor, the JVM caps how large a class may be, and the engine
  * suite sits at that cap. The fan-out leaves are the natural aspect to lift out, because every one of them is about one question,
  * whether an item's durable record survives an attempt ending.
  */
class FlowEngineForeachTest extends FlowEngineSupport:

    // =========================================================================
    // Foreach durability
    // =========================================================================
    "foreach durability" - {

        /** An interrupted fan-out resumes at the item it stopped on, rather than charging every card a second time.
          *
          * This is what per-item durability is FOR. A fan-out over a runtime collection is the only construct here for N external
          * effects, since `gather` is fixed at definition time. Each item's result is a durable field of its own and an attempt that
          * resumes reads back the ones already recorded, so a fan-out that charges five cards and dies on the third charges the three
          * it owes on recovery rather than all five again.
          *
          * The handoff is real, the same instrument the loop and retry leaves use: the first engine's scope is closed while the
          * fan-out is running, the clock passes the lease, and a second engine on the same store claims the row the ordinary way.
          * Nothing is seeded and no store verb is decorated.
          *
          * **Where the interrupt lands is chosen rather than raced.** The settle waits for the first item's durable FIELD, not for
          * the counter, and the count is what makes that deterministic: the field is written after its item finished, the next item
          * is a virtual second away from its own, and the settle stops advancing time the moment it sees the field. So the item in
          * flight when the engine goes is asleep and has charged nothing, and the total is exact rather than "at most one more".
          */
        "a foreach interrupted part-way resumes without re-charging the items it already charged" in {
            Clock.withTimeControl { tc =>
                FlowStore.initMemory.map { store =>
                    AtomicInt.init(0).map { charges =>
                        val items = 5
                        val wfId  = Flow.Id.Workflow("fanout-handoff")
                        val flow = Flow.init("fanout-handoff")
                            .foreach("charges", concurrency = 1)(_ => (1 to items).toSeq) { item =>
                                // Counted once the card is really charged, so an item interrupted in flight counts for nothing
                                // and the assertion below is about charges that actually happened.
                                val body: Int < Async =
                                    Async.sleep(1.second).andThen(charges.incrementAndGet).map(_ => item * 10)
                                body
                            }
                        val config = FlowEngine.Config(
                            workerCount = 1,
                            lease = 2.seconds,
                            renewEvery = 500.millis,
                            pollTimeout = 100.millis
                        )
                        for
                            eid <- Scope.run {
                                FlowEngine.init(store, config, flow)([v] => (c: v < Async) => c).map { first =>
                                    first.workflows.start(wfId).map { handle =>
                                        settle(tc, step = 100.millis, maxRounds = 60)(
                                            store.getField[Int](handle.executionId, "charges~0").map(_.isDefined)
                                        ).andThen(handle.executionId)
                                    }
                                }
                            }
                            before <- charges.get
                            _      <- tc.advance(10.seconds)
                            _ <- Scope.run {
                                FlowEngine.init(store, config, flow)([v] => (c: v < Async) => c).map { _ =>
                                    settle(tc, step = 250.millis, maxRounds = 200)(
                                        store.getExecution(eid).map(_.exists(_.status.isTerminal))
                                    )
                                }
                            }
                            after   <- charges.get
                            state   <- store.getExecution(eid)
                            results <- store.getField[Chunk[Int]](eid, "charges")
                            fields  <- store.getAllFields(eid)
                        yield
                            assert(
                                before >= 1 && before < items,
                                s"the premise is that the first engine charged some of the $items cards and not all of them, " +
                                    s"and it charged $before"
                            )
                            assert(
                                (0 until items).forall(i => fields.contains(s"charges~$i")),
                                s"an item is durably named by its position under the fan-out's own path, and " +
                                    s"${(0 until items).count(i => fields.contains(s"charges~$i"))} of the $items are stored " +
                                    s"under one, out of ${fields.size} fields in all"
                            )
                            assert(
                                state.exists(_.status == Flow.Status.Completed),
                                s"the premise is that the second engine finished the execution, got ${state.map(_.status)}"
                            )
                            assert(
                                results.map(_.toSeq) == Present(Seq(10, 20, 30, 40, 50)),
                                s"a resumed fan-out must still produce every result in collection order, got $results"
                            )
                            assert(
                                after == items,
                                s"an item already recorded must be read back rather than run again: $items cards were charged " +
                                    s"$after times in total, $before of them before the handoff"
                            )
                        end for
                    }
                }
            }
        }

        /** The same handoff above one worker, where what an attempt recorded is no longer a prefix of the collection.
          *
          * The leaf above runs its items one at a time, so the fan-out it hands over always has a prefix shape: items 0 to k
          * recorded, everything after k untouched. Above one worker that shape is gone. The workers pull the next index off a shared
          * counter, so a fast item lands while a slower one EARLIER in the collection is still running, and what the store carries at
          * the handoff has a hole in it: index 0 absent while 1 and 2 are recorded. A resume that read its own progress as "the first
          * index with no result, and everything after it" would charge those two cards a second time and still pass the leaf above.
          *
          * **The hole is built rather than raced.** Index 0 runs for five virtual seconds, index 1 for one and index 2 for two, so
          * the settle that stops on the second recorded item stops three virtual seconds before anything else could land. The premise
          * asserts the shape it built, because a leaf whose handoff happened to be a prefix would be the leaf above with a larger
          * `concurrency`.
          *
          * **What is deliberately NOT asserted here is the total number of charges, and that is a property of concurrency rather
          * than a gap in the leaf.** An item is charged by its body and recorded once its body returns, so an attempt that ends
          * between the two leaves a card charged with no result to read back, and the resumed attempt charges it again. With N
          * workers there can be N-1 items inside that window at once, which bounds the total at `items + concurrency - 1` rather
          * than fixing it. Index 3 is put inside the window on purpose, charging half a second after it starts and holding its
          * result for four more, so it is charged before the handoff and recorded on neither side of it: the premise says so, and it
          * is why counting charges here would be counting a scheduling outcome. The exact total is the leaf above's to assert, where
          * one worker means the window holds one item and the settle closes it before the engine goes.
          *
          * What is exact above one worker is the property the durable record exists for, and it is asserted per item over exactly
          * the items the store carried at the handoff: each of them is charged once, and the resume charges none of them again.
          */
        "a foreach interrupted above concurrency one does not re-charge the items it recorded out of order" in {
            Clock.withTimeControl { tc =>
                FlowStore.initMemory.map { store =>
                    AtomicRef.init(Chunk.empty[Int]).map { charges =>
                        val items = 4
                        val wfId  = Flow.Id.Workflow("fanout-concurrent-handoff")
                        // How long an item runs before it charges, and how long it holds the charged result before returning it.
                        // The hold is what puts an item inside the window between a side effect and the record of it.
                        def timing(item: Int): (Duration, Duration) = item match
                            case 0 => (5.seconds, Duration.Zero)
                            case 1 => (1.second, Duration.Zero)
                            case 2 => (2.seconds, Duration.Zero)
                            case _ => (500.millis, 4.seconds)
                        val flow = Flow.init("fanout-concurrent-handoff")
                            .foreach("charges", concurrency = 3)(_ => (0 until items).toSeq) { item =>
                                val (until, hold) = timing(item)
                                val body: Int < Async =
                                    Async.sleep(until)
                                        .andThen(charges.updateAndGet(_.append(item)))
                                        .andThen(Async.sleep(hold))
                                        .map(_ => item * 10)
                                body
                            }
                        val config = FlowEngine.Config(
                            workerCount = 1,
                            lease = 2.seconds,
                            renewEvery = 500.millis,
                            pollTimeout = 100.millis
                        )
                        // Which items the store carries a result for, by position, which is the set the read-back property is about.
                        def recorded(eid: Flow.Id.Execution): Set[Int] < (Async & Abort[FlowStoreException]) =
                            store.getAllFields(eid).map { fields =>
                                (0 until items).filter(i => fields.contains(s"charges~$i")).toSet
                            }
                        for
                            eid <- Scope.run {
                                FlowEngine.init(store, config, flow)([v] => (c: v < Async) => c).map { first =>
                                    first.workflows.start(wfId).map { handle =>
                                        settle(tc, step = 100.millis, maxRounds = 120)(
                                            recorded(handle.executionId).map(_.size >= 2)
                                        ).andThen(handle.executionId)
                                    }
                                }
                            }
                            held   <- recorded(eid)
                            before <- charges.get
                            _      <- tc.advance(10.seconds)
                            _ <- Scope.run {
                                FlowEngine.init(store, config, flow)([v] => (c: v < Async) => c).map { _ =>
                                    settle(tc, step = 250.millis, maxRounds = 200)(
                                        store.getExecution(eid).map(_.exists(_.status.isTerminal))
                                    )
                                }
                            }
                            state   <- store.getExecution(eid)
                            results <- store.getField[Chunk[Int]](eid, "charges")
                            after   <- charges.get
                        yield
                            assert(
                                held.size == 2 && !held.contains(0),
                                s"the premise is a handoff with a hole in it, two items recorded and the first item not among " +
                                    s"them, and the store carried $held"
                            )
                            assert(
                                before.toSeq.contains(3) && !held.contains(3),
                                s"the premise is that one item sat inside the window the bound is about, charged and not yet " +
                                    s"recorded when the attempt ended, and the first engine charged $before with $held recorded"
                            )
                            assert(
                                state.exists(_.status == Flow.Status.Completed),
                                s"the premise is that the second engine finished the execution, got ${state.map(_.status)}"
                            )
                            assert(
                                results.map(_.toSeq) == Present(Seq(0, 10, 20, 30)),
                                s"a fan-out resumed from an out-of-order record must still produce every result in collection " +
                                    s"order, got $results"
                            )
                            val charged = held.toSeq.sorted.map(i => (i, after.toSeq.count(_ == i)))
                            assert(
                                charged.forall(_._2 == 1),
                                s"an item whose result the store already carries must never be charged again, and the charge " +
                                    s"counts of the items recorded before the handoff, by item, are $charged; the whole log " +
                                    s"is $after"
                            )
                        end for
                    }
                }
            }
        }

        /** A fan-out that failed part-way unwinds over the items that ran, and over no others.
          *
          * The reason the handler belongs to the ITEM. A node-level handler receives the node's stored value, and a fan-out's value
          * exists only once every item has landed, so a fan-out that failed on its third card would hand its handler nothing at the
          * one moment unwinding matters. Per-item identity makes the pairing well defined: each item's handler is registered as that
          * item completes, carrying the item and what the item produced.
          *
          * `concurrency = 1` so the set of items that ran is a fact rather than a scheduling outcome: the first two cards are charged
          * and recorded, the third throws, and nothing else started. The refunds are asserted as a sorted set, because the unwind
          * runs handlers in reverse order of registration and the order among a fan-out's items is not a property this pins.
          */
        "a partially completed foreach unwinds exactly the items that ran" in {
            withEngine { (engine, store, tc) =>
                AtomicRef.init(Chunk.empty[String]).map { refunded =>
                    val flow = Flow.init("charge-cards")
                        .foreachCompensated("charges", concurrency = 1)(_ => Seq("a", "b", "c")) { card =>
                            if card == "c" then throw new RuntimeException("declined") else s"receipt-$card"
                        } { (card, receipt) =>
                            refunded.updateAndGet(_.append(s"$card:$receipt")).unit
                        }
                    for
                        _      <- engine.register(wf1, flow)
                        handle <- engine.workflows.start(wf1)
                        eid = handle.executionId
                        status <- pump(tc, store, eid, _.isTerminal, 400)
                        back   <- refunded.get
                    yield
                        assert(
                            status != Flow.Status.Completed,
                            s"the premise is that the third card failed the fan-out, got $status"
                        )
                        assert(
                            back.toSeq.sorted == Seq("a:receipt-a", "b:receipt-b"),
                            s"a fan-out that failed part-way must undo the items that ran and no others, it undid $back"
                        )
                    end for
                }
            }
        }

        /** A fan-out whose results were replayed rather than computed is still undone item by item.
          *
          * The half that is easy to get wrong, and the same one the dispatch arm has a leaf for. An execution that suspends after the
          * fan-out and fails after resuming never runs the item bodies a second time, because every item's field is already stored
          * and the node's own field with them, so a handler pushed only where an item is COMPUTED would silently not exist on the
          * attempt that actually unwinds. The cards are charged just as much either way.
          *
          * The premise that the bodies did not run again is asserted first, because a leaf that undid three cards after charging six
          * of them would be reporting a different fact than the one it is named for.
          */
        "a foreach's per-item compensation runs after the execution resumed past the fan-out" in {
            withEngine { (engine, store, tc) =>
                AtomicRef.init(Chunk.empty[String]).map { refunded =>
                    AtomicInt.init(0).map { charged =>
                        val flow = Flow.init("charge-then-wait")
                            .foreachCompensated("charges", concurrency = 1)(_ => Seq("a", "b", "c")) { card =>
                                charged.incrementAndGet.map(_ => s"receipt-$card")
                            } { (card, receipt) =>
                                refunded.updateAndGet(_.append(card)).unit
                            }
                            .input[String]("gate")
                            .step("ship")(_ => throw new RuntimeException("carrier refused"))
                        for
                            _      <- engine.register(wf1, flow)
                            handle <- engine.workflows.start(wf1)
                            eid = handle.executionId
                            _      <- pumpState(tc, store, eid, waitingFor("gate"))
                            _      <- engine.executions.signal[String](eid, "gate", "go")
                            status <- pump(tc, store, eid, _.isTerminal, 400)
                            runs   <- charged.get
                            back   <- refunded.get
                        yield
                            assert(
                                runs == 3,
                                s"the premise is that the three cards were charged before the suspension and not again after it, " +
                                    s"and the body ran $runs times"
                            )
                            assert(
                                status != Flow.Status.Completed,
                                s"the premise is that the step after the resume failed, got $status"
                            )
                            assert(
                                back.toSeq.sorted == Seq("a", "b", "c"),
                                s"a fan-out whose results were replayed rather than computed must still be undone item by item, " +
                                    s"it undid $back"
                            )
                        end for
                    }
                }
            }
        }

        /** An attempt that finished a fan-out somebody else started undoes all of it, not just its own share.
          *
          * The leaf above replays a fan-out that was already whole. This one replays a fan-out that was not: the first engine records
          * some of the cards and goes, the second reads those back and charges the rest, and a later step then fails. Every card that
          * was charged has to be refunded, whichever attempt charged it, so the handler is registered where an item is READ
          * BACK exactly as it is where an item is computed. Registering it in only one of the two places leaves a partially recovered
          * fan-out half undone, and how big that half is depends on where the first engine happened to die.
          *
          * The refunds are asserted as the whole set of cards, which is the assertion that cannot pass by accident: it fails one way
          * if a read-back item registers nothing, and the other way if an item is undone twice.
          */
        "a resumed fan-out undoes the items it read back as well as the ones it ran" in {
            Clock.withTimeControl { tc =>
                FlowStore.initMemory.map { store =>
                    AtomicRef.init(Chunk.empty[Int]).map { refunded =>
                        val items = 5
                        val wfId  = Flow.Id.Workflow("fanout-unwind")
                        val flow = Flow.init("fanout-unwind")
                            .foreachCompensated("charges", concurrency = 1)(_ => (1 to items).toSeq) { item =>
                                val body: Int < Async = Async.sleep(1.second).andThen(item * 10)
                                body
                            } { (item, charged) =>
                                refunded.updateAndGet(_.append(item)).unit
                            }
                            .step("ship")(_ => throw new RuntimeException("carrier refused"))
                        val config = FlowEngine.Config(
                            workerCount = 1,
                            lease = 2.seconds,
                            renewEvery = 500.millis,
                            pollTimeout = 100.millis
                        )
                        for
                            eid <- Scope.run {
                                FlowEngine.init(store, config, flow).map { first =>
                                    first.workflows.start(wfId).map { handle =>
                                        settle(tc, step = 100.millis, maxRounds = 60)(
                                            store.getField[Int](handle.executionId, "charges~0").map(_.isDefined)
                                        ).andThen(handle.executionId)
                                    }
                                }
                            }
                            partial <- refunded.get
                            _       <- tc.advance(10.seconds)
                            _ <- Scope.run {
                                FlowEngine.init(store, config, flow).map { _ =>
                                    settle(tc, step = 250.millis, maxRounds = 200)(
                                        store.getExecution(eid).map(_.exists(_.status.isTerminal))
                                    )
                                }
                            }
                            state <- store.getExecution(eid)
                            back  <- refunded.get
                        yield
                            assert(
                                partial.isEmpty,
                                s"the premise is that an interrupted attempt unwinds nothing, and the first engine refunded $partial"
                            )
                            assert(
                                state.exists(_.status != Flow.Status.Completed),
                                s"the premise is that the step after the fan-out failed the execution, got ${state.map(_.status)}"
                            )
                            assert(
                                back.toSeq.sorted == (1 to items).toSeq,
                                s"every card charged has to be refunded, whichever attempt charged it, and the unwind undid $back"
                            )
                        end for
                    }
                }
            }
        }

        /** A collection that answers a different size on replay fails the node instead of guessing.
          *
          * Per-item identity is positional, so it holds only while the collection is a deterministic function of the record. The
          * count the fan-out started with is recorded beside its items for exactly this check, and a size that disagrees with it
          * leaves every pairing unknowable: the results recorded under the old positions belong to items the new collection may not
          * contain.
          *
          * **No per-item handler fires on this path, and that is deliberate rather than an omission.** Running the fan-out again
          * instead is unimplementable under a write-once store, and pairing the recorded results with the recomputed items would hand
          * each handler a value from a run it did not belong to, which is the stale pairing the design removes by construction. So
          * the items that ran are reported by the failure and not undone by a guess.
          *
          * **What that costs is stated rather than implied: the items that already ran are STRANDED.** Their side effects happened
          * and nothing here undoes them, because the only thing that could pair them with a handler is the collection the mismatch
          * just proved untrustworthy. Remediating them is an operator's job, so the evidence they need has to survive the failure,
          * and the last assertion is that it does: every item's recorded result is still readable under its own durable name after
          * the execution has failed. A fail path that swept the per-item fields away would pass every other assertion here.
          *
          * The compensated step BEFORE the fan-out is what keeps the leaf honest: it proves the unwind really ran, so the absence of
          * refunds is a decision rather than a flow that failed before compensating anything.
          */
        "a foreach whose recomputed collection changed size fails the node naming the mismatch" in {
            withEngine { (engine, store, tc) =>
                AtomicInt.init(3).map { size =>
                    AtomicRef.init(Chunk.empty[String]).map { undone =>
                        val flow = Flow.init("nondeterministic-fanout")
                            .stepCompensated("open")(_ => ())(_ => undone.updateAndGet(_.append("open")).unit)
                            .foreachCompensated("charges", concurrency = 1)(_ => size.get.map(n => (1 to n).toSeq)) { item =>
                                item * 10
                            } { (item, charged) =>
                                undone.updateAndGet(_.append(s"item-$item")).unit
                            }
                            .input[String]("gate")
                            .output("done")(_ => "ok")
                        for
                            _      <- engine.register(wf1, flow)
                            handle <- engine.workflows.start(wf1)
                            eid = handle.executionId
                            _        <- pumpState(tc, store, eid, waitingFor("gate"))
                            _        <- size.set(2)
                            _        <- engine.executions.signal[String](eid, "gate", "go")
                            status   <- pump(tc, store, eid, _.isTerminal, 400)
                            back     <- undone.get
                            stranded <- Kyo.foreach(0 until 3)(i => store.getField[Int](eid, s"charges~$i"))
                        yield
                            val failure = status match
                                case Flow.Status.Failed(error, kind) => Maybe((error, kind))
                                case _                               => Maybe.empty
                            assert(
                                failure.isDefined,
                                s"a fan-out whose collection changed size must fail the execution, got $status"
                            )
                            val (error, kind) = failure.get
                            assert(
                                kind == Present("FlowNondeterministicCollectionException"),
                                s"the failure must carry a kind an operator can group by, got $kind"
                            )
                            assert(
                                error.contains("'charges'") && error.contains("3 item(s)") && error.contains("to 2"),
                                s"the failure must name the node, the count it recorded and the size it recomputed, got '$error'"
                            )
                            assert(
                                back.toSeq == Seq("open"),
                                s"the unwind must run, and no item handler may be fed a pairing the mismatch made unknowable, " +
                                    s"it undid $back"
                            )
                            assert(
                                stranded.toSeq == Seq(Present(10), Present(20), Present(30)),
                                s"the items that ran are stranded rather than undone, so their recorded results have to survive " +
                                    s"the failure for whoever remediates them, and the store answered $stranded"
                            )
                        end for
                    }
                }
            }
        }

        /** The fan-out whose collection changed size is reported as the failed node.
          *
          * The mismatch is a verdict the ENGINE reaches, like a loop running out of schedule, and it reaches the store through the
          * interpreter's failure verb. That verb has to write the node's record: a failure verb that writes nothing leaves an
          * execution failed for the one reason a fan-out fails on its own showing a progress view with every node pending, unable to
          * name the node whose message the lifecycle is already carrying. The record lands under the fan-out's durable name before
          * the failure is raised.
          */
        "a foreach whose collection changed size is the node reported failed" in {
            withEngine { (engine, store, tc) =>
                AtomicInt.init(3).map { size =>
                    // Compensated, because that is the shape this detection site lives in: a completed fan-out recomputes its
                    // collection only to re-pair item handlers with their items, so a fan-out with no handler has nothing to
                    // recompute for and returns without looking. The leaf is about which node the failure is reported on.
                    val flow = Flow.init("mismatch-node")
                        .foreachCompensated("charges", concurrency = 1)(_ => size.get.map(n => (1 to n).toSeq)) { item =>
                            item * 10
                        } { (item, charged) =>
                            ()
                        }
                        .input[String]("gate")
                        .output("done")(_ => "ok")
                    for
                        _      <- engine.register(wf1, flow)
                        handle <- engine.workflows.start(wf1)
                        eid = handle.executionId
                        _      <- pumpState(tc, store, eid, waitingFor("gate"))
                        _      <- size.set(2)
                        _      <- engine.executions.signal[String](eid, "gate", "go")
                        status <- pump(tc, store, eid, _.isTerminal, 400)
                        detail <- engine.executions.describe(eid)
                    yield
                        val error = status match
                            case Flow.Status.Failed(message, _) => Maybe(message)
                            case _                              => Maybe.empty
                        assert(error.isDefined, s"the premise is that the changed collection failed the execution, got $status")
                        assert(
                            detail.progress.nodeByName("charges").map(_.status) ==
                                Maybe(FlowEngine.Progress.NodeStatus.Failed(error.get)),
                            s"the fan-out whose collection moved must be the node reported failed, carrying the execution's own " +
                                s"message, got ${detail.progress.nodeByName("charges").map(_.status)}"
                        )
                    end for
                }
            }
        }

        /** A PARTIALLY completed fan-out resumed under a changed collection fails at the site the stale-pairing argument is about.
          *
          * A fan-out detects a size mismatch at two places, and the leaf above drives only one of them: its fan-out completes whole
          * before the gate, so the replay finds the node's own field and checks the recomputed collection against the RESULTS. The
          * other site is the one the argument is actually about: a fan-out that never finished, whose node field is absent and whose
          * COUNT is the only record of how many items it started with. A regression confined to that branch, a count never written,
          * a comparison inverted, a mismatch quietly ignored, passes every platform green without it.
          *
          * The handoff is the deterministic one the resume leaf above uses: the settle waits for the first item's durable field, so
          * the engine goes away with the fan-out part-way rather than at a moment the scheduler picked. The collection then answers
          * a different size before the second engine starts, and the four facts asserted are the ones the completed-fan-out leaf
          * asserts, item for item.
          */
        "a partially completed foreach resumed under a changed collection fails the node naming the mismatch" in {
            Clock.withTimeControl { tc =>
                FlowStore.initMemory.map { store =>
                    AtomicInt.init(5).map { size =>
                        AtomicRef.init(Chunk.empty[String]).map { undone =>
                            val wfId = Flow.Id.Workflow("fanout-resumed-mismatch")
                            val flow = Flow.init("fanout-resumed-mismatch")
                                .stepCompensated("open")(_ => ())(_ => undone.updateAndGet(_.append("open")).unit)
                                .foreachCompensated("charges", concurrency = 1)(_ => size.get.map(n => (1 to n).toSeq)) { item =>
                                    val body: Int < Async = Async.sleep(1.second).map(_ => item * 10)
                                    body
                                } { (item, charged) =>
                                    undone.updateAndGet(_.append(s"item-$item")).unit
                                }
                                .output("done")(_ => "ok")
                            val config = FlowEngine.Config(
                                workerCount = 1,
                                lease = 2.seconds,
                                renewEvery = 500.millis,
                                pollTimeout = 100.millis
                            )
                            for
                                eid <- Scope.run {
                                    FlowEngine.init(store, config, flow).map { first =>
                                        first.workflows.start(wfId).map { handle =>
                                            settle(tc, step = 100.millis, maxRounds = 60)(
                                                store.getField[Int](handle.executionId, "charges~0").map(_.isDefined)
                                            ).andThen(handle.executionId)
                                        }
                                    }
                                }
                                node <- store.getField[Chunk[Int]](eid, "charges")
                                _ = assert(
                                    node.isEmpty,
                                    s"the premise is that the fan-out is part-way, so its own field is not there yet, got $node"
                                )
                                _ <- size.set(4)
                                _ <- tc.advance(10.seconds)
                                _ <- Scope.run {
                                    FlowEngine.init(store, config, flow).map { _ =>
                                        settle(tc, step = 250.millis, maxRounds = 200)(
                                            store.getExecution(eid).map(_.exists(_.status.isTerminal))
                                        )
                                    }
                                }
                                state    <- store.getExecution(eid)
                                back     <- undone.get
                                stranded <- store.getField[Int](eid, "charges~0")
                            yield
                                val failure = state.map(_.status) match
                                    case Present(Flow.Status.Failed(error, kind)) => Maybe((error, kind))
                                    case _                                        => Maybe.empty
                                assert(
                                    failure.isDefined,
                                    s"a fan-out whose collection changed size must fail the execution, got ${state.map(_.status)}"
                                )
                                val (error, kind) = failure.get
                                assert(
                                    kind == Present("FlowNondeterministicCollectionException"),
                                    s"the failure must carry a kind an operator can group by, got $kind"
                                )
                                assert(
                                    error.contains("'charges'") && error.contains("5 item(s)") && error.contains("to 4"),
                                    s"the failure must name the node, the count it recorded and the size it recomputed, got '$error'"
                                )
                                assert(
                                    back.toSeq == Seq("open"),
                                    s"the unwind must run, and no item handler may be fed a pairing the mismatch made unknowable, " +
                                        s"it undid $back"
                                )
                                assert(
                                    stranded == Present(10),
                                    s"the items that ran are stranded rather than undone, so their recorded results have to survive " +
                                        s"the failure for whoever remediates them, and the store answered $stranded"
                                )
                            end for
                        }
                    }
                }
            }
        }

        /** A fan-out whose ITEM failed reports the fan-out, not the item key nobody can see.
          *
          * An item records under the fan-out's name with its index appended, so item 1 of `charges` lands under `charges~1`, and no
          * node on the progress view is called that: the items are the fan-out's own bookkeeping. The record is attributed to the
          * parent for the same reason a loop's iteration is, and it is why the attribution resolves against the nodes the flow
          * actually has rather than by stripping a suffix: `charges~1` and a subflow child `review~step` are the same shape, and only
          * one of them is a node in its own right.
          */
        "a foreach whose item failed reports the fan-out as the failed node" in {
            withEngine { (engine, store, tc) =>
                val flow = Flow.init("item-fails")
                    .foreach("charges", concurrency = 1)(_ => Seq(1, 2, 3)) { item =>
                        if item == 2 then throw new RuntimeException("card two declined")
                        item * 10
                    }
                    .output("done")(_ => "ok")
                for
                    _      <- engine.register(wf1, flow)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    status <- pump(tc, store, eid, _.isTerminal, 400)
                    detail <- engine.executions.describe(eid)
                yield
                    val error = status match
                        case Flow.Status.Failed(message, _) => Maybe(message)
                        case _                              => Maybe.empty
                    assert(error.isDefined, s"the premise is that the failing item ended the execution, got $status")
                    assert(
                        detail.progress.nodeByName("charges").map(_.status) ==
                            Maybe(FlowEngine.Progress.NodeStatus.Failed(error.get)),
                        s"an item's failure belongs to the fan-out, which is the node a reader has, got " +
                            s"${detail.progress.nodeByName("charges").map(_.status)}"
                    )
                end for
            }
        }

        /** The retry schedule re-asks the ITEM that failed, not the fan-out that contains it.
          *
          * `foreach` declares `timeout` and `retry` like the five constructs beside it, and the item is the unit both govern: a
          * schedule that re-asked the whole fan-out would re-run the cards that already succeeded, which is the same shape ruled out
          * on the unscheduled loop, and per-item retry is exactly what a flow charging N cards needs.
          *
          * The asserted value is the SEQUENCE of items the body ran for, which is what tells the two shapes apart. Under a per-item
          * schedule it is 1, 2, 2, 3: the second card is asked twice and its neighbours once. Under a whole-fan-out schedule the
          * first card appears twice as well.
          */
        "a foreach item that throws is retried by the foreach's schedule without re-running completed items" in {
            withEngine { (engine, store, tc) =>
                AtomicRef.init(Chunk.empty[Int]).map { ran =>
                    AtomicInt.init(0).map { failures =>
                        val flow = Flow.init("flaky-fanout")
                            .foreach("charges", retry = Maybe(Schedule.fixed(10.millis).repeat(3)), concurrency = 1)(_ => Seq(1, 2, 3)) {
                                item =>
                                    val outcome: Int < Sync =
                                        if item != 2 then item * 10
                                        else
                                            failures.incrementAndGet.map { n =>
                                                if n == 1 then throw new RuntimeException("flaky card") else item * 10
                                            }
                                    ran.updateAndGet(_.append(item)).andThen(outcome)
                            }
                        for
                            _      <- engine.register(wf1, flow)
                            handle <- engine.workflows.start(wf1)
                            eid = handle.executionId
                            status  <- pump(tc, store, eid, _.isTerminal, 400)
                            results <- store.getField[Chunk[Int]](eid, "charges")
                            order   <- ran.get
                        yield
                            assert(status == Flow.Status.Completed, s"expected Completed, got $status")
                            assert(
                                results.map(_.toSeq) == Present(Seq(10, 20, 30)),
                                s"a retried item must land its result beside the others, got $results"
                            )
                            assert(
                                order.toSeq == Seq(1, 2, 2, 3),
                                s"the schedule must re-ask the item that failed and leave the items already recorded alone, " +
                                    s"the bodies ran for $order"
                            )
                        end for
                    }
                }
            }
        }

        /** The timeout bounds ONE item, not the sum of every item.
          *
          * The other half of putting the knobs on the item, and the same argument the unscheduled loop's timeout carries: a bound
          * over the whole fan-out cannot be sized for the single slow item, which is the one thing a caller bounds. Four items of
          * 400 virtual milliseconds each run for 1.6 seconds in total under a one second bound, so a fan-out-wide timeout fails the
          * execution while no item is anywhere near slow.
          */
        "a foreach's timeout bounds one item, not the whole fan-out" in {
            withEngine { (engine, store, tc) =>
                val flow = Flow.init("bounded-items")
                    .foreach("items", timeout = 1.second, concurrency = 1)(_ => Seq(1, 2, 3, 4)) { item =>
                        val body: Int < Async = Async.sleep(400.millis).andThen(item * 10)
                        body
                    }
                for
                    _      <- engine.register(wf1, flow)([v] => (c: v < Async) => c)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    status  <- pump(tc, store, eid, _.isTerminal, 2000)
                    results <- store.getField[Chunk[Int]](eid, "items")
                yield
                    assert(
                        status == Flow.Status.Completed,
                        s"four items of 400ms each must each pass a one second bound, got $status"
                    )
                    assert(
                        results.map(_.toSeq) == Present(Seq(10, 20, 30, 40)),
                        s"every item must produce its result under a per-item bound, got $results"
                    )
                end for
            }
        }
    }

end FlowEngineForeachTest
