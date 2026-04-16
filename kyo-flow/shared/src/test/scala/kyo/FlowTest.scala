package kyo

import kyo.internal.*

class FlowTest extends Test:

    given CanEqual[Any, Any] = CanEqual.derived

    case class OrderId(value: String) derives CanEqual, Json
    case class Order(id: String, amount: Int) derives CanEqual, Json
    case class Payment(orderId: String, total: Int) derives CanEqual, Json
    case class Shipment(orderId: String) derives CanEqual, Json
    case class Approval(approved: Boolean) derives CanEqual, Json

    "AST construction" - {

        "linear flow" in run {
            val flow = Flow.input[OrderId]("orderId")
                .output("order")(ctx => Order(ctx.orderId.value, 100))
                .output("payment")(ctx => Payment(ctx.order.id, ctx.order.amount))
            succeed
        }

        "input adds to both In and Out" in run {
            val flow  = Flow.input[OrderId]("orderId")
            val flow2 = flow.output("order")(ctx => Order(ctx.orderId.value, 100))
            succeed
        }

        "step does not add to Out" in run {
            val flow = Flow.input[OrderId]("orderId")
                .output("order")(ctx => Order(ctx.orderId.value, 100))
                .step("log")(ctx => ())
                .output("payment")(ctx => Payment(ctx.order.id, ctx.order.amount))
            succeed
        }

        "sleep does not change types" in run {
            val flow = Flow.input[OrderId]("orderId")
                .output("order")(ctx => Order(ctx.orderId.value, 100))
                .sleep("wait", 1.hour)
                .output("payment")(ctx => Payment(ctx.order.id, ctx.order.amount))
            succeed
        }

        "multiple inputs" in run {
            val flow = Flow.input[OrderId]("orderId").input[Approval]("approval")
            succeed
        }

        "zip merges In and Out" in run {
            val left     = Flow.input[OrderId]("orderId").output("order")(ctx => Order(ctx.orderId.value, 100))
            val right    = Flow.input[Approval]("approval")
            val combined = left.zip(right)
            succeed
        }

        "andThen merges In and Out" in run {
            val first    = Flow.input[OrderId]("orderId").output("order")(ctx => Order(ctx.orderId.value, 100))
            val second   = Flow.input[Approval]("approval").output("decision")(ctx => ctx.approval.approved)
            val combined = first.andThen(second)
            succeed
        }

        "dispatch adds named output" in run {
            val flow = Flow.input[Int]("amount")
                .dispatch[String]("decision")
                .when(ctx => ctx.amount > 50, name = "high")(ctx => "approved")
                .otherwise(ctx => "rejected", name = "default")
            succeed
        }

        "loop adds named output" in run {
            val flow = Flow.input[Int]("count")
                .loop("result", 0) { (state: Int, ctx) =>
                    if ctx.count > state then Loop.continue(state + 1)
                    else Loop.done(state)
                }
            succeed
        }

        "context available across many steps" in run {
            val flow = Flow.input[OrderId]("orderId")
                .output("order")(ctx => Order(ctx.orderId.value, 100))
                .output("payment")(ctx => Payment(ctx.order.id, ctx.order.amount))
                .step("notify")(ctx => ())
                .sleep("wait", 1.hour)
                .output("shipment")(ctx => Shipment(ctx.order.id))
            succeed
        }

        "nested composition" in run {
            val inner    = Flow.input[Int]("a").output("b")(ctx => ctx.a + 1)
            val outer    = Flow.input[String]("c").output("d")(ctx => ctx.c.length)
            val combined = inner.andThen(outer)
            val zipped   = combined.zip(Flow.input[Boolean]("e"))
            succeed
        }

        "foreach constructs" in run {
            val flow = Flow.input[Int]("count")
                .foreach("results")(ctx => (1 to ctx.count).toSeq)(i => i * 2)
            succeed
        }

        "race constructs two sub-flows" in run {
            val left  = Flow.input[Approval]("approval")
            val right = Flow.input[Int]("x").output("fallback")(ctx => Approval(false))
            val flow  = Flow.race(left, right)
            succeed
        }

        "output with compensate" in run {
            val flow = Flow.input[Int]("x")
                .outputCompensated("y")(ctx => ctx.x + 1)(ctx => ())
            succeed
        }

        "invoke with child flow" in run {
            val child = Flow.input[Int]("a").output("b")(ctx => ctx.a * 10)
            val flow = Flow.input[Int]("x")
                .subflow("result", child)(ctx => "a" ~ ctx.x)
            succeed
        }

        "gather multiple flows" in run {
            val f1   = Flow.input[Int]("a").output("b")(ctx => ctx.a + 1)
            val f2   = Flow.input[Int]("c").output("d")(ctx => ctx.c + 2)
            val flow = Flow.gather(f1, f2)
            succeed
        }

        "race two flows" in run {
            val left  = Flow.input[Int]("a").output("b")(ctx => ctx.a + 1)
            val right = Flow.input[Int]("c").output("d")(ctx => ctx.c + 2)
            val flow  = Flow.race(left, right)
            succeed
        }
    }

    "type-level properties" - {

        "output accumulates into Out" in run {
            val f = Flow.input[Int]("x").output("y")(ctx => ctx.x.toString)
            summon[f.type <:< Flow["x" ~ Int, "x" ~ Int & ("y" ~ String), ?]]
            succeed
        }

        "step does not change Out" in run {
            val f = Flow.input[Int]("x").step("log")(ctx => ())
            summon[f.type <:< Flow["x" ~ Int, "x" ~ Int, ?]]
            succeed
        }

        "input adds to both In and Out" in run {
            val f = Flow.input[Int]("x").input[String]("y")
            summon[f.type <:< Flow["x" ~ Int & ("y" ~ String), "x" ~ Int & ("y" ~ String), ?]]
            succeed
        }

        "output with Async effect tracks S" in run {
            val f = Flow.input[Int]("x").output("y")(ctx => Async.sleep(1.millis).map(_ => ctx.x))
            val _: Flow["x" ~ Int, "x" ~ Int & ("y" ~ Int), Async] = f
            succeed
        }

        "andThen merges In and Out" in run {
            val f1 = Flow.input[Int]("x").output("a")(ctx => ctx.x + 1)
            val f2 = Flow.input[String]("y").output("b")(ctx => ctx.y.length)
            val f  = f1.andThen(f2)
            summon[f.type <:< Flow["x" ~ Int & ("y" ~ String), "x" ~ Int & ("a" ~ Int) & ("y" ~ String) & ("b" ~ Int), ?]]
            succeed
        }

        "zip merges In and Out" in run {
            val f1 = Flow.input[Int]("x").output("a")(ctx => ctx.x + 1)
            val f2 = Flow.input[String]("y").output("b")(ctx => ctx.y.length)
            val f  = f1.zip(f2)
            summon[f.type <:< Flow["x" ~ Int & ("y" ~ String), "x" ~ Int & ("a" ~ Int) & ("y" ~ String) & ("b" ~ Int), ?]]
            succeed
        }

        "sleep does not change In/Out" in run {
            val f = Flow.input[Int]("x").sleep("wait", 1.second)
            summon[f.type <:< Flow["x" ~ Int, "x" ~ Int, ?]]
            succeed
        }

        "dispatch adds named output" in run {
            val f = Flow.input[Int]("x")
                .dispatch[String]("d")
                .when(ctx => ctx.x > 0, name = "positive")(ctx => "yes")
                .otherwise(ctx => "no", name = "default")
            summon[f.type <:< Flow["x" ~ Int, "x" ~ Int & ("d" ~ String), ?]]
            succeed
        }

        "foreach produces Chunk output" in run {
            val f = Flow.input[Int]("n")
                .foreach("results")(ctx => (1 to ctx.n).toSeq)(i => i * 2)
            summon[f.type <:< Flow["n" ~ Int, "n" ~ Int & ("results" ~ Chunk[Int]), ?]]
            succeed
        }

        "gather merges all branches" in run {
            val f1 = Flow.input[Int]("a").output("b")(ctx => ctx.a + 1)
            val f2 = Flow.input[Int]("c").output("d")(ctx => ctx.c + 2)
            val f  = Flow.gather(f1, f2)
            summon[f.type <:< Flow["a" ~ Int & ("c" ~ Int), "a" ~ Int & ("b" ~ Int) & ("c" ~ Int) & ("d" ~ Int), ?]]
            succeed
        }

        "race produces union output" in run {
            val f1 = Flow.input[Int]("a").output("b")(ctx => ctx.a + 1)
            val f2 = Flow.input[Int]("c").output("d")(ctx => ctx.c + 2)
            val f  = Flow.race(f1, f2)
            summon[f.type <:< Flow["a" ~ Int & ("c" ~ Int), ("a" ~ Int & ("b" ~ Int)) | ("c" ~ Int & ("d" ~ Int)), ?]]
            succeed
        }
    }

    "isolate" - {

        "zip compiles with pure flows" in run {
            val f1 = Flow.input[Int]("a").output("b")(ctx => ctx.a + 1)
            val f2 = Flow.input[Int]("c").output("d")(ctx => ctx.c + 2)
            val _  = f1.zip(f2)
            succeed
        }

        "zip compiles with Async flows" in run {
            val f1 = Flow.input[Int]("a").output("b")(ctx => Async.sleep(1.millis).map(_ => ctx.a + 1))
            val f2 = Flow.input[Int]("c").output("d")(ctx => Async.sleep(1.millis).map(_ => ctx.c + 2))
            val _  = f1.zip(f2)
            succeed
        }

        "zip compiles with Var flows when Isolate is provided" in run {
            given Isolate[Var[Int], Any, Var[Int]] = Var.isolate.update[Int]
            val f1                                 = Flow.input[Int]("a").output("b")(ctx => Var.get[Int].map(_ + ctx.a))
            val f2                                 = Flow.input[Int]("c").output("d")(ctx => Var.get[Int].map(_ + ctx.c))
            val _                                  = f1.zip(f2)
            succeed
        }

        "zip rejects Var flows without Isolate" in run {
            assertDoesNotCompile("""
                val f1 = Flow.input[Int]("a").output("b")(ctx => Var.get[Int].map(_ + ctx.a))
                val f2 = Flow.input[Int]("c").output("d")(ctx => Var.get[Int].map(_ + ctx.c))
                f1.zip(f2)
            """)
            succeed
        }

        "gather compiles with pure flows" in run {
            val f1 = Flow.input[Int]("a").output("b")(ctx => ctx.a + 1)
            val f2 = Flow.input[Int]("c").output("d")(ctx => ctx.c + 2)
            val _  = Flow.gather(f1, f2)
            succeed
        }

        "gather rejects Var flows without Isolate" in run {
            assertDoesNotCompile("""
                val f1 = Flow.input[Int]("a").output("b")(ctx => Var.get[Int].map(_ + ctx.a))
                val f2 = Flow.input[Int]("c").output("d")(ctx => Var.get[Int].map(_ + ctx.c))
                Flow.gather(f1, f2)
            """)
            succeed
        }

        "race compiles with pure flows" in run {
            val f1 = Flow.input[Int]("a").output("b")(ctx => ctx.a + 1)
            val f2 = Flow.input[Int]("c").output("d")(ctx => ctx.c + 2)
            val _  = Flow.race(f1, f2)
            succeed
        }

        "race rejects Var flows without Isolate" in run {
            assertDoesNotCompile("""
                val f1 = Flow.input[Int]("a").output("b")(ctx => Var.get[Int].map(_ + ctx.a))
                val f2 = Flow.input[Int]("c").output("d")(ctx => Var.get[Int].map(_ + ctx.c))
                Flow.race(f1, f2)
            """)
            succeed
        }
    }

    "fold" - {

        "visits output and input" in run {
            val flow = Flow.input[Int]("x").output("y")(ctx => ctx.x + 1)
            val names = FlowFold(flow)(new FlowVisitorCollect[Chunk[String]](Chunk.empty, _ ++ _):
                override def onInput[V](name: String, frame: Frame, meta: Flow.Meta)(using Tag[V], Json[V])  = Chunk(s"input:$name")
                override def onOutput[V](name: String, frame: Frame, meta: Flow.Meta)(using Tag[V], Json[V]) = Chunk(s"output:$name"))
            assert(names.toSeq.contains("input:x"))
            assert(names.toSeq.contains("output:y"))
            succeed
        }

        "visits step" in run {
            val flow = Flow.input[Int]("x").step("sideEffect")(ctx => ())
            val names = FlowFold(flow)(new FlowVisitorCollect[Chunk[String]](Chunk.empty, _ ++ _):
                override def onStep(name: String, frame: Frame, meta: Flow.Meta) = Chunk(s"step:$name"))
            assert(names.toSeq.contains("step:sideEffect"))
            succeed
        }

        "visits sleep with duration" in run {
            val flow                           = Flow.input[Int]("x").sleep("pause", 5.minutes)
            var sleepDuration: Maybe[Duration] = Maybe.empty
            FlowFold(flow)(new FlowVisitorCollect[Unit]((), (_, _) => ()):
                override def onSleep(name: String, dur: Duration, frame: Frame, meta: Flow.Meta) =
                    sleepDuration = Maybe(dur))
            assert(sleepDuration == Maybe(5.minutes))
            succeed
        }

        "visits dispatch with branch infos" in run {
            val flow = Flow.input[Int]("x")
                .dispatch[String]("d")
                .when(ctx => ctx.x > 0, name = "positive")(ctx => "pos")
                .when(ctx => ctx.x < 0, name = "negative")(ctx => "neg")
                .otherwise(ctx => "zero", name = "default")
            var branchCount = 0
            FlowFold(flow)(new FlowVisitorCollect[Unit]((), (_, _) => ()):
                override def onDispatch(name: String, infos: Seq[Flow.BranchInfo], frame: Frame, meta: Flow.Meta) =
                    branchCount = infos.size)
            assert(branchCount == 3)
            succeed
        }

        "visits loop" in run {
            val flow     = Flow.input[Int]("x").loop("r") { ctx => Loop.done(ctx.x - 1) }
            var loopName = ""
            FlowFold(flow)(new FlowVisitorCollect[Unit]((), (_, _) => ()):
                override def onLoop(name: String, frame: Frame, meta: Flow.Meta) = loopName = name)
            assert(loopName == "r")
            succeed
        }

        "visits zip" in run {
            val left      = Flow.input[Int]("a").output("b")(ctx => ctx.a)
            val right     = Flow.input[Int]("c").output("d")(ctx => ctx.c)
            val flow      = left.zip(right)
            var zipCalled = false
            FlowFold(flow)(new FlowVisitorCollect[Unit]((), (_, _) => ()):
                override def onZip(left: Unit, right: Unit, frame: Frame) = zipCalled = true)
            assert(zipCalled)
            succeed
        }

        "traversal order" in run {
            val flow = Flow.input[Int]("a").output("b")(ctx => ctx.a).step("c")(ctx => ()).sleep("d", 1.second)
            val names = FlowFold(flow)(new FlowVisitorCollect[Chunk[String]](Chunk.empty, _ ++ _):
                override def onInput[V](name: String, frame: Frame, meta: Flow.Meta)(using Tag[V], Json[V])  = Chunk(name)
                override def onOutput[V](name: String, frame: Frame, meta: Flow.Meta)(using Tag[V], Json[V]) = Chunk(name)
                override def onStep(name: String, frame: Frame, meta: Flow.Meta)                             = Chunk(name)
                override def onSleep(name: String, duration: Duration, frame: Frame, meta: Flow.Meta)        = Chunk(name))
            assert(names.toSeq == Seq("a", "b", "c", "d"))
            succeed
        }

        "counts all nodes" in run {
            val flow = Flow.input[Int]("x").output("y")(ctx => ctx.x + 1).step("log")(ctx => ()).sleep("wait", 1.second)
            val count = FlowFold(flow)(new FlowVisitorCollect[Int](0, _ + _):
                override def onInput[V](name: String, frame: Frame, meta: Flow.Meta)(using Tag[V], Json[V])  = 1
                override def onOutput[V](name: String, frame: Frame, meta: Flow.Meta)(using Tag[V], Json[V]) = 1
                override def onStep(name: String, frame: Frame, meta: Flow.Meta)                             = 1
                override def onSleep(name: String, duration: Duration, frame: Frame, meta: Flow.Meta)        = 1)
            assert(count == 4)
            succeed
        }

        "visits foreach with name and concurrency" in run {
            val flow        = Flow.input[Int]("count").foreach("items", concurrency = 5)(ctx => (1 to ctx.count).toSeq)(i => i * 2)
            var foreachName = ""
            var foreachConc = 0
            FlowFold(flow)(new FlowVisitorCollect[Unit]((), (_, _) => ()):
                override def onForEach(name: String, conc: Int, frame: Frame, meta: Flow.Meta) =
                    foreachName = name; foreachConc = conc)
            assert(foreachName == "items")
            assert(foreachConc == 5)
            succeed
        }

        "visits race sub-flows" in run {
            val left        = Flow.input[Approval]("approval")
            val right       = Flow.input[Int]("x").output("fallback")(ctx => Approval(false))
            val flow        = Flow.race(left, right)
            var raceVisited = false
            FlowFold(flow)(new FlowVisitorCollect[Unit]((), (_, _) => ()):
                override def onRace(left: Unit, right: Unit, frame: Frame) = raceVisited = true)
            assert(raceVisited)
            succeed
        }

        "visits subflow with child flow reference" in run {
            val child                   = Flow.input[Int]("a").output("b")(ctx => ctx.a * 10)
            val flow                    = Flow.input[Int]("x").subflow("result", child)(ctx => "a" ~ ctx.x)
            var subflowName             = ""
            var childRef: Flow[?, ?, ?] = null
            FlowFold(flow)(new FlowVisitorCollect[Unit]((), (_, _) => ()):
                override def onSubflow(name: String, childFlow: Flow[?, ?, ?], frame: Frame, meta: Flow.Meta) =
                    subflowName = name; childRef = childFlow)
            assert(subflowName == "result")
            assert(childRef eq child, "visitor should receive the same child flow instance")
            succeed
        }

        "visits gather with multiple flows" in run {
            val f1          = Flow.input[Int]("a").output("b")(ctx => ctx.a + 1)
            val f2          = Flow.input[Int]("c").output("d")(ctx => ctx.c + 2)
            val flow        = Flow.gather(f1, f2)
            var gatherCount = 0
            FlowFold(flow)(new FlowVisitorCollect[Int](0, _ + _):
                override def onInput[V](name: String, frame: Frame, meta: Flow.Meta)(using Tag[V], Json[V])  = 1
                override def onOutput[V](name: String, frame: Frame, meta: Flow.Meta)(using Tag[V], Json[V]) = 1
                override def onGather(results: Seq[Int], frame: Frame) =
                    gatherCount = results.length; results.sum)
            assert(gatherCount == 2)
            succeed
        }

        "fold with dispatch in chain" in run {
            val flow = Flow.input[Int]("x")
                .output("y")(ctx => ctx.x * 2)
                .dispatch[String]("d")
                .when(ctx => ctx.y > 10, name = "big")(ctx => "big")
                .otherwise(ctx => "small", name = "default")
                .output("z")(ctx => ctx.d)
            val names = FlowFold(flow)(new FlowVisitorCollect[Chunk[String]](Chunk.empty, _ ++ _):
                override def onInput[V](name: String, frame: Frame, meta: Flow.Meta)(using Tag[V], Json[V])          = Chunk(name)
                override def onOutput[V](name: String, frame: Frame, meta: Flow.Meta)(using Tag[V], Json[V])         = Chunk(name)
                override def onDispatch(name: String, branches: Seq[Flow.BranchInfo], frame: Frame, meta: Flow.Meta) = Chunk(name)
                override def onSubflow(name: String, childFlow: Flow[?, ?, ?], frame: Frame, meta: Flow.Meta)        = Chunk(name))
            assert(names.toSeq == Seq("x", "y", "d", "z"))
            succeed
        }
    }

    "type-safe foreach" - {

        "typed foreach with Seq[Int] - body receives Int" in run {
            val flow = Flow.input[Int]("count").foreach("doubled")(ctx => (1 to ctx.count).toSeq)(n => n * 2)
            succeed
        }

        "typed foreach with Seq[String] - body receives String" in run {
            val flow = Flow.input[Int]("count").foreach("lengths")(ctx => Seq("a", "bb", "ccc"))(s => s.length)
            succeed
        }

        "typed foreach result is Chunk[V]" in run {
            val flow: Flow["count" ~ Int, ("count" ~ Int) & ("doubled" ~ Chunk[Int]), Any] =
                Flow.input[Int]("count").foreach("doubled")(ctx => (1 to ctx.count).toSeq)(n => n * 2)
            succeed
        }
    }

    "type-safe invoke" - {

        "invoke propagates child output type" in run {
            val child = Flow.input[Int]("a").output("b")(ctx => ctx.a * 10)
            val flow  = Flow.input[Int]("x").subflow("result", child)(ctx => "a" ~ ctx.x)
            succeed
        }

        "invoke child result accessible downstream" in run {
            val child = Flow.input[Int]("a").output("b")(ctx => ctx.a * 10)
            val flow = Flow.input[Int]("x")
                .subflow("result", child)(ctx => "a" ~ ctx.x)
                .output("final")(ctx => ctx.result.b)
            succeed
        }
    }

    "type-safe gather" - {

        "2-way gather preserves types" in run {
            val f1   = Flow.input[Int]("a").output("b")(ctx => ctx.a + 1)
            val f2   = Flow.input[Int]("c").output("d")(ctx => ctx.c + 2)
            val flow = Flow.gather(f1, f2)
            val _    = flow.output("sum")(ctx => ctx.b + ctx.d)
            succeed
        }

        "3-way gather preserves types" in run {
            val f1 = Flow.input[Int]("a").output("b")(ctx => ctx.a + 1)
            val f2 = Flow.input[Int]("c").output("d")(ctx => ctx.c + 2)
            val f3 = Flow.input[Int]("e").output("f")(ctx => ctx.e + 3)
            val _  = Flow.gather(f1, f2, f3)
            succeed
        }

        "4-way gather preserves types" in run {
            val f1 = Flow.input[Int]("a").output("b")(ctx => ctx.a + 1)
            val f2 = Flow.input[Int]("c").output("d")(ctx => ctx.c + 2)
            val f3 = Flow.input[Int]("e").output("f")(ctx => ctx.e + 3)
            val f4 = Flow.input[Int]("g").output("h")(ctx => ctx.g + 4)
            val _  = Flow.gather(f1, f2, f3, f4)
            succeed
        }

        "5-way gather preserves types" in run {
            val f1 = Flow.input[Int]("a").output("b")(ctx => ctx.a + 1)
            val f2 = Flow.input[Int]("c").output("d")(ctx => ctx.c + 2)
            val f3 = Flow.input[Int]("e").output("f")(ctx => ctx.e + 3)
            val f4 = Flow.input[Int]("g").output("h")(ctx => ctx.g + 4)
            val f5 = Flow.input[Int]("i").output("j")(ctx => ctx.i + 5)
            val _  = Flow.gather(f1, f2, f3, f4, f5)
            succeed
        }

        "nested gather for more than 5 flows" in run {
            val f1 = Flow.input[Int]("a").output("b")(ctx => ctx.a + 1)
            val f2 = Flow.input[Int]("c").output("d")(ctx => ctx.c + 2)
            val f3 = Flow.input[Int]("e").output("f")(ctx => ctx.e + 3)
            val f4 = Flow.input[Int]("g").output("h")(ctx => ctx.g + 4)
            val f5 = Flow.input[Int]("i").output("j")(ctx => ctx.i + 5)
            val f6 = Flow.input[Int]("k").output("l")(ctx => ctx.k + 6)
            val _  = Flow.gather(Flow.gather(f1, f2, f3), Flow.gather(f4, f5, f6))
            succeed
        }
    }

    "compile errors" - {

        "dispatch without otherwise — output" in {
            typeCheckFailure("""
                import kyo.*
                val flow = Flow.init("test").input[Int]("x")
                    .dispatch[String]("d")
                    .when(ctx => ctx.x > 0, name = "yes")(ctx => "y")
                    .output("z")(ctx => "")
            """)("dispatch requires .otherwise")
        }

        "dispatch without otherwise — step" in {
            typeCheckFailure("""
                import kyo.*
                val flow = Flow.init("test").input[Int]("x")
                    .dispatch[String]("d")
                    .when(ctx => ctx.x > 0, name = "yes")(ctx => "y")
                    .step("s")(ctx => ())
            """)("dispatch requires .otherwise")
        }

        "dispatch without otherwise — input" in {
            typeCheckFailure("""
                import kyo.*
                val flow = Flow.init("test").input[Int]("x")
                    .dispatch[String]("d")
                    .when(ctx => ctx.x > 0, name = "yes")(ctx => "y")
                    .input[Int]("z")
            """)("dispatch requires .otherwise")
        }

        "dispatch without otherwise — sleep" in {
            typeCheckFailure("""
                import kyo.*
                val flow = Flow.init("test").input[Int]("x")
                    .dispatch[String]("d")
                    .when(ctx => ctx.x > 0, name = "yes")(ctx => "y")
                    .sleep("s", 1.second)
            """)("dispatch requires .otherwise")
        }
    }

    // --- Status ---

    "Status" - {

        "show returns readable strings" - {

            "Running" in run {
                assert(Flow.Status.Running.show == "running")
                succeed
            }

            "WaitingForInput" in run {
                assert(Flow.Status.WaitingForInput("x").show == "waiting:x")
                succeed
            }

            "Sleeping" in run {
                val until = Instant.Epoch + 1.hour
                assert(Flow.Status.Sleeping("pause", until).show == "sleeping:pause")
                succeed
            }

            "Completed" in run {
                assert(Flow.Status.Completed.show == "completed")
                succeed
            }

            "Failed" in run {
                assert(Flow.Status.Failed("boom").show == "failed:boom")
                succeed
            }

            "Cancelled" in run {
                assert(Flow.Status.Cancelled.show == "cancelled")
                succeed
            }
        }

        "isTerminal for terminal statuses" - {
            "Completed is terminal" in run { assert(Flow.Status.Completed.isTerminal); succeed }
            "Failed is terminal" in run { assert(Flow.Status.Failed("error").isTerminal); succeed }
            "Cancelled is terminal" in run { assert(Flow.Status.Cancelled.isTerminal); succeed }
        }

        "isTerminal for non-terminal statuses" - {
            "Running is not terminal" in run { assert(!Flow.Status.Running.isTerminal); succeed }
            "WaitingForInput is not terminal" in run { assert(!Flow.Status.WaitingForInput("x").isTerminal); succeed }
            "Sleeping is not terminal" in run {
                assert(!Flow.Status.Sleeping("pause", Instant.Epoch + 1.hour).isTerminal); succeed
            }
        }
    }

    // --- Event ---

    "Event" - {

        val f1  = Flow.Id.Workflow("f1")
        val e1  = Flow.Id.Execution("e1")
        val ex1 = Flow.Id.Executor("ex1")
        val ts  = Instant.Epoch + 1.second

        "kind returns correct EventKind for each variant" - {
            "Created" in run { assert(Flow.Event.Created(f1, e1, ts).kind == Flow.EventKind.Created); succeed }
            "StepStarted" in run { assert(Flow.Event.StepStarted(f1, e1, "step1", ex1, ts).kind == Flow.EventKind.StepStarted); succeed }
            "StepCompleted" in run { assert(Flow.Event.StepCompleted(f1, e1, "step1", ts).kind == Flow.EventKind.StepCompleted); succeed }
            "InputWaiting" in run { assert(Flow.Event.InputWaiting(f1, e1, "x", ts).kind == Flow.EventKind.InputWaiting); succeed }
            "SleepStarted" in run {
                assert(Flow.Event.SleepStarted(f1, e1, "pause", ts + 1.hour, ts).kind == Flow.EventKind.SleepStarted); succeed
            }
            "SleepCompleted" in run {
                assert(Flow.Event.SleepCompleted(f1, e1, "pause", ts).kind == Flow.EventKind.SleepCompleted); succeed
            }
            "Completed" in run { assert(Flow.Event.Completed(f1, e1, ts).kind == Flow.EventKind.Completed); succeed }
            "Failed" in run { assert(Flow.Event.Failed(f1, e1, "error msg", ts).kind == Flow.EventKind.Failed); succeed }
            "Cancelled" in run { assert(Flow.Event.Cancelled(f1, e1, ts).kind == Flow.EventKind.Cancelled); succeed }
        }

        "detail returns step/input name or error" - {
            "StepStarted" in run { assert(Flow.Event.StepStarted(f1, e1, "myStep", ex1, ts).detail == "myStep"); succeed }
            "StepCompleted" in run { assert(Flow.Event.StepCompleted(f1, e1, "myStep", ts).detail == "myStep"); succeed }
            "InputWaiting" in run { assert(Flow.Event.InputWaiting(f1, e1, "myInput", ts).detail == "myInput"); succeed }
            "SleepStarted" in run { assert(Flow.Event.SleepStarted(f1, e1, "mySleep", ts + 1.hour, ts).detail == "mySleep"); succeed }
            "SleepCompleted" in run { assert(Flow.Event.SleepCompleted(f1, e1, "mySleep", ts).detail == "mySleep"); succeed }
            "Failed" in run { assert(Flow.Event.Failed(f1, e1, "something broke", ts).detail == "something broke"); succeed }
        }

        "detail returns empty string for Created, Completed, Cancelled" - {
            "Created" in run { assert(Flow.Event.Created(f1, e1, ts).detail == ""); succeed }
            "Completed" in run { assert(Flow.Event.Completed(f1, e1, ts).detail == ""); succeed }
            "Cancelled" in run { assert(Flow.Event.Cancelled(f1, e1, ts).detail == ""); succeed }
        }
    }

end FlowTest
