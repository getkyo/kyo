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

    /** Empties the stack for the next borrower.
      *
      * The entries are dropped rather than merely forgotten, which `pop` does not do: a pooled stack outlives the eval that used it, so a
      * slot the size no longer covers would hold that eval's handler, state, context and continuation alive for as long as the pool does.
      * Bounded by the peak depth one eval reached, which is exactly the retention `pop` is allowed to leave and a pool is not.
      *
      * Called on every release, including one leaving on an exception, where the stack still holds every region the throw unwound past.
      */
    def clear(): Unit =
        var i = 0
        while i < size do
            handlers(i) = null
            states(i) = null
            // `Context` is opaque over a TypeMap and admits no null; the shared empty one drops the
            // reference without allocating, which is all this needs
            contexts(i) = Context.empty
            continuations(i) = null
            i += 1
        end while
        size = 0
    end clear

    /** Moves every entry into a packed array, outermost first, leaving the stack empty.
      *
      * Three slots per region: handler, state, continuation. Contexts stay behind: a re-installed region derives its context from where it
      * stands, so captured install-time contexts would be wrong to keep. A move rather than a copy for the reason `clear` nulls its slots:
      * the pooled arrays outlive the eval, and the packed array is the entries' one owner from here on.
      */
    def snapshot(): Array[AnyRef] =
        val out = new Array[AnyRef](size * 3)
        var i   = 0
        while i < size do
            out(i * 3) = handlers(i)
            out(i * 3 + 1) = states(i).asInstanceOf[AnyRef]
            out(i * 3 + 2) = continuations(i)
            handlers(i) = null
            states(i) = null
            contexts(i) = Context.empty
            continuations(i) = null
            i += 1
        end while
        size = 0
        out
    end snapshot

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

    /** The stacks one thread is not currently using.
      *
      * Every eval needs a stack and most give it back untouched, so allocating one per eval put 32 B/op on evals that install no region at
      * all. Pooling is what the reference kernel does at the same place, and it is sound here for the reason the stack is safe to be mutable
      * at all: it is scoped to a single `Eval.apply`, nothing it holds leaves the eval, and a nested eval borrows its own.
      *
      * Plain fields behind a thread local, so a pool is only ever touched by its own thread and needs no synchronization.
      */
    final private class Pool:
        private var free = new Array[Stack](4)
        private var size = 0

        def borrow(): Stack =
            if size == 0 then new Stack
            else
                size -= 1
                val stack = free(size)
                free(size) = null
                stack

        def release(stack: Stack): Unit =
            stack.clear()
            if size == free.length then free = Array.copyOf(free, size * 2)
            free(size) = stack
            size += 1
        end release
    end Pool

    private val pool =
        new ThreadLocal[Pool]:
            override def initialValue() = new Pool

    def borrow(): Stack = pool.get().borrow()

    def release(stack: Stack): Unit = pool.get().release(stack)
end Stack
