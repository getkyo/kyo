package protodemo

import kyo.Frame
import kyo.Maybe
import kyo.Result
import kyo.Tag
import kyo.proto.*
import kyo.proto.kernel.ArrowEffect
import kyo.proto.kernel.ContextEffect
import kyo.proto.kernel.Effect

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
        Effect.defer(f)

    def cfg: Int < Any =
        ContextEffect.suspend[Int, Cfg](cfgTag, 0)

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
        ContextEffect.handleInheritable(cfgTag, 41)(body)

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
        ContextEffect.handleInheritable(cfgTag, 10)(inner)
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

    // a generic position accepts a computation by the union's >: A subsumption, the same as a
    // concrete one: the lift stays dormant and nothing boxes
    def constAdd[X](x: X): X < Add =
        add(1).map(_ => x)

    // a computation held as data, the eager join shape: the >: A bound erases data-ness, so the
    // machine evaluates the payload where it surfaces and the done clause receives it settled
    def dataJoin: Int < Any =
        val body: (Int < Any) < Add = constAdd(lazily(42))
        val listed: List[Int < Any] < Any =
            ArrowEffect.handleCont[CInt, CInt, Add, Int < Any, List[Int < Any], Any, Any](addTag, body)(
                [C] => (input, cont) => cont(input + 1, Arrow.id),
                a => List(a)
            )
        listed.map(l => l.head.map(_ + 1))
    end dataJoin

    // a binding deriving from the one enclosing it, the layered binding shape
    def layered: Int < Any =
        val body: Int < Any = cfg.map(_ + 1)
        ContextEffect.handleInheritable(cfgTag, 41)(ContextEffect.handleInheritable(cfgTag)(_.fold(0)(_ * 2))(body))

    // a throw inside the extent answered by the region's recovery clause, the failure recovery shape
    def recovering: Int < Any =
        val body: Int < Add = add(1).map(a => (throw new Exception("boom")): Int)
        ArrowEffect.handleCont[CInt, CInt, Add, Int, Int, Any, Any](addTag, body)(
            [C] => (input, cont) => cont(input + 1, Arrow.id),
            a => a,
            _ => Maybe(-1)
        )
    end recovering

    // a throw after a resumption crossing a foreign region, the resumed recovery shape: the
    // crossed region's recovery clause answers a throw raised while its continuation is being
    // reapplied
    def crossingRecovery: Int < Any =
        val body: Int < Add = add(1).map(v => (throw new Exception("boom")): Int)
        runAdd(ArrowEffect.handleCont[CInt, CInt, Tick, Int, Int, Add, Any](tickTag, body)(
            [C] => (input, cont) => cont(input + 1, Arrow.id),
            a => a,
            _ => Maybe(-1)
        ))
    end crossingRecovery

    type CUnit[X] = Unit

    abstract class Res extends ArrowEffect[CInt, CUnit]

    val resTag = Tag[Res]

    // an operation of the encoded bracket effect: registers a finalizer with the last handler
    def ensure(id: Int): Unit < Res =
        ArrowEffect.suspend[Unit](resTag, id)

    // the encoded bracket effect, the shape Sync will take: the run accepts the bare row, so it is
    // the last handler by type and no inner handler's continuation can ever contain this region.
    // Registrations live in the last handler's loop state, outside every produced continuation;
    // done drains at completion and the recovery clause drains before answering a throw, last
    // registered first. The recovery clause receives the live state, the registrations the loop
    // has threaded, which is what lets the registry ride the loop state at all. Each finalizer
    // drains as a deferred computation, and the result encodes the drain order one digit per
    // release, so the value witnesses last-registered-first
    def runRes(v: Int < Res): Int < Any =
        def drain(fins: List[Int], base: Int < Any): Int < Any =
            fins.foldLeft(base)((acc, id) => acc.flatMap(r => lazily(id).map(_ => r * 10 + id)))
        ArrowEffect.handleLoopState[CInt, CUnit, Res, Int, Int, Any, Any, List[Int]](resTag, List.empty[Int], v)(
            [C] => (fins, id) => Loop.continue(id :: fins, (): Unit < Any),
            (fins, v0) => drain(fins, v0),
            (fins, ex) => Maybe(drain(fins, -1))
        )
    end runRes

    // finalizers drain at completion, last registered first
    def encodedBracket: Int < Any =
        runRes(ensure(1).andThen(ensure(2)).andThen(40).map(_ + 2))

    // a throw inside the extent: the registered finalizer drains before the recovery answers
    def encodedBracketPanic: Int < Any =
        runRes(ensure(1).map(_ => (throw new Exception("boom")): Int))

    // the guarantee the handling order buys: an inner clause discards its continuation, and the
    // registration survives, because it escaped to the last handler before the drop
    def encodedBracketDiscard: Int < Any =
        val body: Int < (Add & Res) = ensure(1).andThen(add(5)).map(_ + 1)
        val dropped: Int < Res = ArrowEffect.handleCont[CInt, CInt, Add, Int, Res, Any](addTag, body)(
            [C] => (input, cont) => 99
        )
        runRes(dropped)
    end encodedBracketDiscard

    def scenario(name: String)(v: => Int < Any): Unit =
        println(
            s"""|
                |${"=" * 80}
                |🧪 $name
                |${"=" * 80}""".stripMargin
        )
        println(s"✅ result: ${kyo.proto.debug.DebugSession.run(quiet = false, guardsLikeProduction = false)(() => v)}")
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
        scenario("data join")(dataJoin)
        scenario("layered binding")(layered)
        scenario("panic recovery")(recovering)
        scenario("crossing recovery")(crossingRecovery)
        scenario("encoded bracket")(encodedBracket)
        scenario("encoded bracket panic")(encodedBracketPanic)
        scenario("encoded bracket discard")(encodedBracketDiscard)
    end main
end Main
