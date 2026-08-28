package protodemo

import kyo.Frame
import kyo.Result
import kyo.Tag
import kyo.proto.*
import kyo.proto.kernel.ArrowEffect
import kyo.proto.kernel.ContextEffect
import kyo.proto.kernel.internal.Debugger
import kyo.proto.kernel.internal.Eval
import kyo.proto.kernel.internal.Handler
import kyo.proto.kernel.internal.Kyo

object Main:

    type CInt[X] = Int

    abstract class Add extends ArrowEffect[CInt, CInt]

    abstract class Tick extends ArrowEffect[CInt, CInt]

    abstract class Cfg extends ContextEffect[Int]

    abstract class Pick extends ArrowEffect[CInt, CInt]

    val addTag  = Tag[Add]
    val tickTag = Tag[Tick]
    val cfgTag  = Tag[Cfg]
    val pickTag = Tag[Pick]

    def add(n: Int): Int < Add =
        Kyo.SuspendArrow[CInt, CInt, Add, Int, Int, Add](addTag, n, Arrow.id)

    def tick(n: Int): Int < Tick =
        Kyo.SuspendArrow[CInt, CInt, Tick, Int, Int, Tick](tickTag, n, Arrow.id)

    def lazily[A](f: => A < Any)(using Frame): A < Any =
        Arrow[Any]((_: Any) => f)

    def cfg: Int < Any =
        Kyo.SuspendContextDefault[Int, Cfg, Int, Any](cfgTag, 0, v => v, Arrow.id)

    val addCont = new Handler.HandlerCont[CInt, CInt, Add, Int, Int, Any]:
        def tag = addTag
        def handle[X, C, S2](input: Int, cont: Arrow[Int, Int, Add & Any], k: Arrow[Int, C, S2]) =
            cont(input + 1, k)
        def done(state: Unit, v: Int) = v

    val tickCont = new Handler.HandlerCont[CInt, CInt, Tick, Int, Int, Any]:
        def tag = tickTag
        def handle[X, C, S2](input: Int, cont: Arrow[Int, Int, Tick & Any], k: Arrow[Int, C, S2]) =
            cont(input + 1, k)
        def done(state: Unit, v: Int) = v

    val addLoop = new Handler.HandlerLoop[CInt, CInt, Add, Int, Int, Any, Int]:
        def tag                               = addTag
        def handle[X](state: Int, input: Int) = Loop.continue(state + input, input)
        def done(state: Int, v: Int)          = state + v

    val addEmit = new Handler.HandlerLoop[CInt, CInt, Add, Int, Int, Tick, Int]:
        def tag                               = addTag
        def handle[X](state: Int, input: Int) = tick(input).map(t => Loop.continue(state + t, input))
        def done(state: Int, v: Int)          = state + v

    def pick(n: Int): Int < Pick =
        Kyo.SuspendArrow[CInt, CInt, Pick, Int, Int, Pick](pickTag, n, Arrow.id)

    val pickAll: Handler.HandlerCont[CInt, CInt, Pick, Int, Int, Any] =
        new Handler.HandlerCont[CInt, CInt, Pick, Int, Int, Any]:
            def tag = pickTag
            def handle[X, C, S2](input: Int, cont: Arrow[Int, Int, Pick & Any], k: Arrow[Int, C, S2]) =
                def branches(i: Int, acc: Int): Int < Any =
                    if i == input then acc
                    else runPick(cont(i, Arrow.id)).map(b => branches(i + 1, acc + b))
                branches(0, 0).chain(k)
            end handle
            def done(state: Unit, v: Int) = v

    def runPick(v: Int < Pick): Int < Any =
        Kyo.handle[Pick, Int, Int, Any, Unit](v, pickAll, ())

    val cfgHandler = new Handler.HandlerContext[Int, Cfg, Int, Int, Any]:
        def tag                                                           = cfgTag
        def fork(current: Int)                                            = current
        def join(current: Int, forked: Int, result: Result[Nothing, Int]) = result
        def done(state: Int, v: Int)                                      = v

    // deep pure map chain, the pure iteration shape
    def pure: Int < Any =
        (1: Int < Any).map(_ + 1).map(_ * 2).map(_ + 3)

    // a deferred step per iteration, the pure shape the eval must unfold
    def deferBind: Int < Any =
        def go(i: Int): Int < Any =
            if i > 2 then i else lazily(go(i + 1))
        go(0)
    end deferBind

    // two answered operations under a continuation handler, the suspension shape
    def cont: Int < Any =
        val body: Int < Add = add(1).map(a => add(a).map(b => a + b))
        Kyo.handle[Add, Int, Int, Any, Unit](body, addCont, ())

    // recursive bind through the effect, a resumption per iteration
    def suspendLoop: Int < Any =
        def go(i: Int): Int < Add =
            if i > 2 then i else add(i).map(a => go(i + a))
        Kyo.handle[Add, Int, Int, Any, Unit](go(0), addCont, ())
    end suspendLoop

    // state threaded per operation with a stateful done, the stateful handler shape
    def loop: Int < Any =
        val body: Int < Add = add(10).map(a => add(20).map(b => a + b))
        Kyo.handle[Add, Int, Int, Any, Int](body, addLoop, 0)

    // a read answered by an enclosing region
    def context: Int < Any =
        val body: Int < Any = cfg.map(_ + 1)
        Kyo.handle[Cfg, Int, Int, Any, Int](body, cfgHandler, 41)

    // a read past every region, answered by the boundary default
    def contextDefault: Int < Any =
        cfg.map(_ + 100)

    // several transforms pending after an answered operation, the trailing-maps shape
    def chained: Int < Any =
        val body: Int < Add = add(1).map(_ + 1).map(_ * 2).map(_ + 3).map(_ + 1).map(_ * 2)
        Kyo.handle[Add, Int, Int, Any, Unit](body, addCont, ())

    // a transform pending outside the region, the handled-then-mapped shape
    def handledMapped: Int < Any =
        val body: Int < Add = add(1).map(a => add(a).map(b => a + b))
        Kyo.handle[Add, Int, Int, Any, Unit](body, addCont, ()).map(_ + 1)

    // a pending transform held across deferred steps
    def deferBindMapped: Int < Any =
        def go(i: Int): Int < Any =
            if i > 2 then i else lazily(go(i + 1))
        go(0).map(_ + 1)
    end deferBindMapped

    // a trailing transform per resumption, the linear trailing-maps shape
    def suspendLoopMapped: Int < Any =
        def go(i: Int): Int < Add =
            if i > 2 then i else add(i).map(a => go(i + a)).map(_ + 1)
        Kyo.handle[Add, Int, Int, Any, Unit](go(0), addCont, ())
    end suspendLoopMapped

    // a region whose effect the body never uses, the idle handler shape
    def idle: Int < Any =
        def go(i: Int): Int < Any =
            if i > 2 then i else lazily(go(i + 1))
        Kyo.handle[Add, Int, Int, Any, Unit](go(0), addCont, ())
    end idle

    // a foreign read crossing the inner region, the nested regions shape
    def nested: Int < Any =
        val body: Int < Add  = cfg.map(c => add(c).map(_ + c))
        val inner: Int < Any = Kyo.handle[Add, Int, Int, Any, Unit](body, addCont, ())
        Kyo.handle[Cfg, Int, Int, Any, Int](inner, cfgHandler, 10)
    end nested

    // the inner handler emits an outer effect from its clause, the region rebuild shape
    def emitting: Int < Any =
        val body: Int < Add   = add(1).map(a => add(a).map(b => a + b))
        val inner: Int < Tick = Kyo.handle[Add, Int, Int, Tick, Int](body, addEmit, 0)
        Kyo.handle[Tick, Int, Int, Any, Unit](inner, tickCont, ())
    end emitting

    // a multi-shot clause re-handling each branch, the nondeterminism shape
    def choice: Int < Any =
        val body: Int < Pick = pick(2).map(a => pick(2).map(b => a * 10 + b))
        runPick(body)
    end choice

    // several handlers stacked around one body, the handler stack shape
    def stacked: Int < Any =
        val body: Int < Add = add(1).map(a => add(a).map(b => a + b))
        val r1: Int < Any   = Kyo.handle[Add, Int, Int, Any, Unit](body, addCont, ())
        val r2: Int < Any   = Kyo.handle[Add, Int, Int, Any, Unit](r1, addCont, ())
        val r3: Int < Any   = Kyo.handle[Add, Int, Int, Any, Unit](r2, addCont, ())
        Kyo.handle[Add, Int, Int, Any, Unit](r3, addCont, ())
    end stacked

    // operations handled across intervening regions, the deep crossing shape
    def crossing: Int < Any =
        val body: Int < Add = add(1).map(a => add(a).map(b => a + b))
        val t1: Int < Add   = Kyo.handle[Tick, Int, Int, Add, Unit](body, tickCont, ())
        val t2: Int < Add   = Kyo.handle[Tick, Int, Int, Add, Unit](t1, tickCont, ())
        Kyo.handle[Add, Int, Int, Any, Unit](t2, addCont, ())
    end crossing

    def scenario(name: String)(v: => Int < Any): Unit =
        println(
            s"""|
                |${"=" * 80}
                |🧪 $name
                |${"=" * 80}""".stripMargin
        )
        val debugger = ConsoleDebugger()
        Debugger.install(debugger)
        try
            val result = Eval(v)
            println(
                s"""|${"-" * 80}
                    |📊 ${debugger.stats}""".stripMargin
            )
            println(s"✅ result: $result")
        finally
            Debugger.uninstall()
        end try
    end scenario

    def main(args: Array[String]): Unit =
        scenario("pure")(pure)
        scenario("defer bind")(deferBind)
        scenario("cont handler")(cont)
        scenario("suspend loop")(suspendLoop)
        scenario("loop handler")(loop)
        scenario("context")(context)
        scenario("context default")(contextDefault)
        scenario("trailing maps")(chained)
        scenario("handled then mapped")(handledMapped)
        scenario("defer bind mapped")(deferBindMapped)
        scenario("suspend loop mapped")(suspendLoopMapped)
        scenario("idle handler")(idle)
        scenario("nested regions")(nested)
        scenario("emitting handler")(emitting)
        scenario("choice")(choice)
        scenario("handler stack")(stacked)
        scenario("deep crossing")(crossing)
    end main
end Main
