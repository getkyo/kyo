package kyo.proto.kernel.internal

import kyo.proto.Arrow
import kyo.proto.kernel.Effect

/** The regions an eval has open, outermost first.
  *
  * One entry per installed region: the handler that answers for it, the state the loop threads through it, the context to restore when it
  * ends, and the continuation that follows it. Holding a region here rather than in a Java frame is what lets regions nest to any depth: the
  * eval stays one self-recursive loop, and an open region costs an entry instead of a frame.
  *
  * Mutable, and scoped to a single `Eval.apply`, which is the only thing that holds one. A nested eval builds its own, so it sees none of the
  * outer eval's regions, and nothing the eval hands out points here.
  *
  * The arrays hold every region's values, so their element types are the erasure and each read is asserted at the type the matching push
  * established. The entries are strictly heterogeneous, which is why the assertion cannot be carried in a signature.
  */
final private[kyo] class Stack:

    private var handlers = new Array[Handler[?, ?, ?, ?, ?]](8)
    private var states   = new Array[Any](8)
    private var ctxs     = new Array[Context](8)
    private var conts    = new Array[Arrow[?, ?, ?]](8)
    private var size     = 0

    def isEmpty: Boolean = size == 0

    /** Installs a region. Typed, because the pushing site knows all five: the state has to be the one this
      * handler threads, and the continuation has to start where this handler's result ends. Those are the two
      * ways an entry can be built wrong, and both are checked here rather than asserted on the way out.
      */
    def push[E <: Effect, A, B, S, State](
        handler: Handler[E, A, B, S, State],
        state: State,
        ctx: Context,
        cont: Arrow[B, Any, S]
    ): Unit =
        if size == handlers.length then grow()
        handlers(size) = handler
        states(size) = state
        ctxs(size) = ctx
        conts(size) = cont
        size += 1
    end push

    /** Drops the innermost region. The slots it used keep their references until a deeper push overwrites
      * them or the eval ends, so a stack that reached depth n holds up to n entries' worth for the rest
      * of that eval. Clearing them is the alternative and costs four stores on the path every region
      * exit takes; neither has been measured, and the retention is bounded by the eval's peak depth.
      */
    def pop(): Unit = size -= 1

    // the innermost region, which is the only one an eval step can be inside. Every call site reaches these
    // behind its own `isEmpty` test, so an empty stack has no reads rather than a defined answer for them
    def handler: Handler[?, ?, ?, ?, ?] = handlers(size - 1)
    def state: Any                      = states(size - 1)
    def state_=(v: Any): Unit           = states(size - 1) = v
    def ctx: Context                    = ctxs(size - 1)
    def cont: Arrow[?, ?, ?]            = conts(size - 1)

    // out of line: growth is cold, and the push it serves is on the region path
    private def grow(): Unit =
        val n  = size * 2
        val hs = new Array[Handler[?, ?, ?, ?, ?]](n)
        val ss = new Array[Any](n)
        val xs = new Array[Context](n)
        val cs = new Array[Arrow[?, ?, ?]](n)
        Array.copy(handlers, 0, hs, 0, size)
        Array.copy(states, 0, ss, 0, size)
        Array.copy(ctxs, 0, xs, 0, size)
        Array.copy(conts, 0, cs, 0, size)
        handlers = hs
        states = ss
        ctxs = xs
        conts = cs
    end grow
end Stack
