package kyo.net.internal.util

import kyo.*

/** A field readable and writable only on its owning loop carrier.
  *
  * A driver's per-loop tables (interest maps, the residual-read flags, the missed-event sets) are
  * mutated by exactly one carrier: the driver's dedicated daemon loop thread. That single-owner
  * discipline rests today on a per-field comment that no check enforces, so a stray off-loop
  * mutation breaks the readiness path with no alarm. [[LoopConfined]] makes the discipline
  * structural: every read and write requires a [[LoopProof]], the phantom capability only the loop
  * owner summons, so an off-loop call site fails to compile (the violation is CAUGHT, not merely
  * documented). An assertion build additionally checks the running thread is the loop owner, as
  * defense in depth for a mutation that reaches here across the change-queue handoff.
  *
  * Production cost is zero: [[get]]/[[set]] inline to the raw field read/write (the [[LoopProof]]
  * is a JIT-eliminated singleton reference, the AllowUnsafe shape), and the thread-identity check
  * is behind the assertion flag. The phantom CATCHES an accidental off-loop mutator call at compile
  * time; it does not PROVE physical on-loop execution after a handoff (a runtime/spawn fact), which
  * is the honest ceiling of this confinement.
  */
// TODO it seems this file is dead code?
final private[kyo] class LoopConfined[A](initial: A, owner: LoopToken):
    private var value: A = initial

    /** Read the confined value. Requires a [[LoopProof]]; an assertion build checks the running
      * thread is the loop owner.
      */
    def get(using LoopProof): A =
        owner.assertOnLoop()
        value

    /** Write the confined value. Requires a [[LoopProof]]; an assertion build checks the running
      * thread is the loop owner.
      */
    def set(v: A)(using LoopProof): Unit =
        owner.assertOnLoop()
        value = v
    end set
end LoopConfined

/** Identifies a driver's loop thread and runs the assertion-build on-loop check. Constructed by the
  * driver as its dedicated daemon loop thread starts, capturing that thread as the sole sanctioned
  * mutator of every [[LoopConfined]] field it owns.
  */
final private[kyo] class LoopToken(loopThread: Thread):
    /** Returns true when the calling thread is the loop owner. A single reference-equality read;
      * zero allocation, zero locking.
      */
    def onLoop: Boolean = Thread.currentThread() eq loopThread

    /** In an assertion build, fail when the running thread is not the loop owner. A zero-cost no-op
      * when assertions are disabled (the JIT removes the `assert` guard).
      */
    def assertOnLoop(): Unit =
        assert(
            onLoop,
            s"LoopConfined field accessed off the loop thread '${loopThread.getName}' (running on '${Thread.currentThread().getName}')"
        )
end LoopToken

/** A phantom capability proving the holder runs as the loop owner. Threaded as a `using` argument
  * into every [[LoopConfined]] access, so an off-loop call site cannot summon it and fails to
  * compile. Mirrors `AllowUnsafe`: one private singleton, a JIT-eliminated reference at each call
  * site (no allocation, no per-op cost). The loop body imports the singleton; no other scope does.
  */
sealed abstract class LoopProof private ()

object LoopProof:
    // The single phantom witness. The loop body brings it into scope (the documented sanctioned
    // off-token writer, e.g. the JDK SelectedSelectionKeySet add, imports it at that one site).
    private[kyo] given proof: LoopProof = new LoopProof() {}
end LoopProof
