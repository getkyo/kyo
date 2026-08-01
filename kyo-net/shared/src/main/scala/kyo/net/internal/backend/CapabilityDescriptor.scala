package kyo.net.internal.backend

import kyo.*

/** The one selection identity every capability-probed candidate carries: the I/O backends and the TLS providers, on every platform.
  *
  * Before this existed the same three members (`name` / `priority` / `isAvailable`) were declared verbatim on four separate shapes, and two
  * of those shapes were pure forwarders onto the other two. The refinement chain replaces that: each step ADDS the production method its
  * source set can compile, and none of them re-declares the identity. `IoBackend` adds `createDriver`, the registry `Entry` adds
  * `build(): Transport`, and `TlsEngineProvider` adds `createEngine`.
  *
  * The identity lives in the SHARED source set and names no FFI type. kyo-net now depends on kyo-ffi on every platform (JS and Wasm drive
  * the koffi posix transport too), so keeping the identity FFI-free is a deliberate abstraction boundary, not a dependency constraint: the
  * shared refinement stays uniform and the FFI-touching production methods live in the platform refinements. That is also why the production
  * methods do NOT live here. JS produces an
  * `IoDriver`, not a `Transport`, and terminates TLS inside Node rather than building an in-process engine, so a descriptor that demanded
  * either method would not compile there.
  *
  * @see
  *   [[CapabilityOutcome]] for what a probe reports, and `CapabilityProbe` (jvm-native) for the FFI failure classification.
  */
private[net] trait CapabilityDescriptor:

    /** Stable id a `-D` flag matches: "io_uring" / "epoll" / "kqueue" / "nio" / "node" on the I/O registry, "boringssl" / "jdk" / "openssl" /
      * "node" on the TLS registry.
      */
    def name: String

    /** Higher wins. Selection orders the AVAILABLE candidates by this and takes the first; what makes a candidate available, and what it
      * reports when it is not, is [[CapabilityOutcome]]. So priority ORDERS and the outcome GATES: io_uring=30, epoll/kqueue=20, nio/node=10
      * on the I/O side; boringssl=30, openssl=20, jdk/node=10 on the TLS side.
      */
    def priority: Int

    /** The native library ids this candidate's probe loads, in load order: the system libraries first and the candidate's OWN bundled native
      * LAST. Empty for the FFI-free candidates (nio, node, jdk). The order is load-bearing on exactly one path: when a binding class has
      * already been poisoned by an earlier failed initialization its cause is gone, and the classification names the last id as the one that
      * did not load.
      */
    def libraryIds: Chunk[String]

    // Memoized: a probe is a real syscall (an `epoll_create1`, an io_uring ring init and teardown, an `SSL_CTX` allocate and free), the
    // answer it produces is host-static, and it is asked repeatedly: once per candidate on every selection, and again for every skipped
    // candidate the demotion warning explains. Computing it once per candidate removes that repetition, including the second probe per
    // skipped backend the demotion warning used to run. The `@volatile` publishes the outcome across carriers on the JVM; on JS and Native
    // the value is host-fixed, so a benign re-probe racing the first publish would compute the same answer anyway.
    @volatile private var memo: Maybe[CapabilityOutcome] = Absent

    /** The candidate's full-dependency capability probe: memoized per candidate, and NEVER throws. Runs [[doProbe]] on the first call and
      * answers from the memo afterwards; anything [[doProbe]] lets escape is caught here and reported as [[CapabilityOutcome.ProbeFailed]],
      * so a caller can read the outcome without a guard.
      */
    final def probe(using AllowUnsafe): CapabilityOutcome =
        memo match
            case Present(outcome) => outcome
            case Absent =>
                val outcome =
                    try doProbe
                    catch case t: Throwable => CapabilityOutcome.ProbeFailed(t)
                memo = Present(outcome)
                outcome
        end match
    end probe

    /** The candidate's own probe body, run at most once per process by [[probe]]. It exercises the WHOLE dependency set the data path needs,
      * not the cheapest gate that would pass: a readiness backend whose libc syscall exists but whose bundled shim is missing must report
      * that here, because the alternative is winning selection and failing per connection later.
      *
      * Implementations should classify what they can rather than letting it throw ([[probe]]'s catch is the safety net, not the plan); on
      * JVM and Native that classification is `CapabilityProbe.run`.
      */
    private[net] def doProbe(using AllowUnsafe): CapabilityOutcome

end CapabilityDescriptor
