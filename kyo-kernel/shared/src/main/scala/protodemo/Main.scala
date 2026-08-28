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
        ArrowEffect.suspend[Unit](addTag, n)

    def tick(n: Int): Int < Tick =
        ArrowEffect.suspend[Unit](tickTag, n)

    def lazily[A](f: => A < Any)(using Frame): A < Any =
        Arrow[Any]((_: Any) => f)

    def cfg: Int < Any =
        Kyo.SuspendContextDefault[Int, Cfg, Int, Any](cfgTag, 0, v => v, Arrow.id)

    inline def runAdd[S](v: Int < (Add & S)): Int < S =
        ArrowEffect.handleCont[CInt, CInt, Add, Int, S, Any](addTag, v)(
            [C] => (input, cont) => cont(input + 1, Arrow.id)
        )

    inline def runTick[S](v: Int < (Tick & S)): Int < S =
        ArrowEffect.handleCont[CInt, CInt, Tick, Int, S, Any](tickTag, v)(
            [C] => (input, cont) => cont(input + 1, Arrow.id)
        )

    inline def runAddLoop[S](v: Int < (Add & S)): Int < S =
        ArrowEffect.handleLoopState[CInt, CInt, Add, Int, Int, S, Any, Int](addTag, 0, v)(
            [C] => (state, input) => Loop.continue(state + input, input),
            (state, v0) => state + v0
        )

    inline def runAddEmit(v: Int < (Add & Tick)): Int < Tick =
        ArrowEffect.handleLoopState[CInt, CInt, Add, Int, Int, Tick, Any, Int](addTag, 0, v)(
            [C] => (state, input) => tick(input).map(t => Loop.continue(state + t, input)),
            (state, v0) => state + v0
        )

    def pick(n: Int): Int < Pick =
        ArrowEffect.suspend[Unit](pickTag, n)

    def runPickWith[C](v: Int < Pick)(f: Int => C < Any): C < Any =
        ArrowEffect.handleContWith[CInt, CInt, Pick, Int, Int, Any, Any](pickTag, v)(
            [X] =>
                (input, cont) =>
                    def branches(i: Int, acc: Int): Int < Any =
                        if i == input then acc
                        else runPickWith(cont(i, Arrow.id))(b => branches(i + 1, acc + b))
                    branches(0, 0)
            ,
            a => a
        )(f)

    def runPick(v: Int < Pick): Int < Any =
        runPickWith(v)(a => a)

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
        runAdd(body)

    // recursive bind through the effect, a resumption per iteration
    def suspendLoop: Int < Any =
        def go(i: Int): Int < Add =
            if i > 2 then i else add(i).map(a => go(i + a))
        runAdd(go(0))
    end suspendLoop

    // a fused suspend-and-transform per iteration, the merged node shape
    def fused: Int < Any =
        def go(i: Int): Int < Add =
            if i > 2 then i else ArrowEffect.suspendWith[Unit](addTag, i)(a => go(i + a))
        runAdd(go(0))
    end fused

    // state threaded per operation with a stateful done, the stateful handler shape
    def loop: Int < Any =
        val body: Int < Add = add(10).map(a => add(20).map(b => a + b))
        runAddLoop(body)

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
        runAdd(body)

    // a transform pending outside the region, the handled-then-mapped shape
    def handledMapped: Int < Any =
        val body: Int < Add = add(1).map(a => add(a).map(b => a + b))
        runAdd(body).map(_ + 1)

    // the post-handle transform fused into the region node, the merged handle shape
    def handledMappedFused: Int < Any =
        val body: Int < Add = add(1).map(a => add(a).map(b => a + b))
        ArrowEffect.handleContWith(addTag, body)(
            [C] => (input, cont) => cont(input + 1, Arrow.id),
            a => a
        )(_ + 1)
    end handledMappedFused

    // a bind returning an effect under trailing transforms, the mid-chain re-deferral shape
    def rebind: Int < Any =
        val body: Int < Add = add(1).map(a => add(a)).map(b => b + 1).map(c => c * 2)
        runAdd(body)
    end rebind

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
        runAdd(go(0))
    end suspendLoopMapped

    // a region whose effect the body never uses, the idle handler shape
    def idle: Int < Any =
        def go(i: Int): Int < Any =
            if i > 2 then i else lazily(go(i + 1))
        runAdd(go(0))
    end idle

    // a foreign read crossing the inner region, the nested regions shape
    def nested: Int < Any =
        val body: Int < Add  = cfg.map(c => add(c).map(_ + c))
        val inner: Int < Any = runAdd(body)
        Kyo.handle[Cfg, Int, Int, Any, Int](inner, cfgHandler, 10)
    end nested

    // an unbound context read crossing a region to the boundary default, the foreign context shape
    def foreignContext: Int < Any =
        val body: Int < Add = cfg.map(c => add(c).map(_ + c))
        runAdd(body)
    end foreignContext

    // the inner handler emits an outer effect from its clause, the region rebuild shape
    def emitting: Int < Any =
        val body: Int < Add   = add(1).map(a => add(a).map(b => a + b))
        val inner: Int < Tick = runAddEmit(body)
        runTick(inner)
    end emitting

    // a multi-shot clause re-handling each branch, the nondeterminism shape
    def choice: Int < Any =
        val body: Int < Pick = pick(2).map(a => pick(2).map(b => a * 10 + b))
        runPick(body)
    end choice

    // several handlers stacked around one body, the handler stack shape
    def stacked: Int < Any =
        val body: Int < Add = add(1).map(a => add(a).map(b => a + b))
        val r1: Int < Any   = runAdd(body)
        val r2: Int < Any   = runAdd(r1)
        val r3: Int < Any   = runAdd(r2)
        runAdd(r3)
    end stacked

    // operations handled across intervening regions, the deep crossing shape
    def crossing: Int < Any =
        val body: Int < Add = add(1).map(a => add(a).map(b => a + b))
        val t1: Int < Add   = runTick(body)
        val t2: Int < Add   = runTick(t1)
        runAdd(t2)
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
        scenario("suspend with")(fused)
        scenario("loop handler")(loop)
        scenario("context")(context)
        scenario("context default")(contextDefault)
        scenario("trailing maps")(chained)
        scenario("handled then mapped")(handledMapped)
        scenario("handled then mapped fused")(handledMappedFused)
        scenario("pending rebind")(rebind)
        scenario("defer bind mapped")(deferBindMapped)
        scenario("suspend loop mapped")(suspendLoopMapped)
        scenario("idle handler")(idle)
        scenario("nested regions")(nested)
        scenario("foreign context")(foreignContext)
        scenario("emitting handler")(emitting)
        scenario("choice")(choice)
        scenario("handler stack")(stacked)
        scenario("deep crossing")(crossing)
    end main
end Main
