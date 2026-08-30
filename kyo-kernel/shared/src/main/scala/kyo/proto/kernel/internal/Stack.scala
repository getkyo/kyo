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

    // shared and empty until the first region is installed. Every eval builds a stack and most never install
    // anything, so allocating four arrays in the constructor put 224 B/op on evals with no region in them at
    // all, measured on `evalFixedOverhead`. The first `push` finds no room and grows into real arrays.
    private var handlers      = Stack.noHandlers
    private var states        = Stack.noStates
    private var contexts      = Stack.noContexts
    private var continuations = Stack.noContinuations
    private var size          = 0

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
        contexts(size) = ctx
        continuations(size) = cont
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
    def state_=(value: Any): Unit       = states(size - 1) = value
    def ctx: Context                    = contexts(size - 1)
    def cont: Arrow[?, ?, ?]            = continuations(size - 1)

    // out of line: growth is cold, and the push it serves is on the region path. The first call finds the
    // shared empty arrays and lands on the floor rather than doubling nothing
    private def grow(): Unit =
        val capacity           = if size == 0 then 8 else size * 2
        val grownHandlers      = new Array[Handler[?, ?, ?, ?, ?]](capacity)
        val grownStates        = new Array[Any](capacity)
        val grownContexts      = new Array[Context](capacity)
        val grownContinuations = new Array[Arrow[?, ?, ?]](capacity)
        Array.copy(handlers, 0, grownHandlers, 0, size)
        Array.copy(states, 0, grownStates, 0, size)
        Array.copy(contexts, 0, grownContexts, 0, size)
        Array.copy(continuations, 0, grownContinuations, 0, size)
        handlers = grownHandlers
        states = grownStates
        contexts = grownContexts
        continuations = grownContinuations
    end grow
end Stack

private[kyo] object Stack:
    // one set for the whole process. They are never written, because a write needs room and having none is
    // what sends the first push through `grow`
    private val noHandlers      = new Array[Handler[?, ?, ?, ?, ?]](0)
    private val noStates        = new Array[Any](0)
    private val noContexts      = new Array[Context](0)
    private val noContinuations = new Array[Arrow[?, ?, ?]](0)
end Stack
