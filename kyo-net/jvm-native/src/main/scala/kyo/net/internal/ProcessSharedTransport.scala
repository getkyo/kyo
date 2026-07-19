package kyo.net.internal

/** Marks the construction of a process-lifetime transport (one never closed by design) so each I/O driver's poll/reap carrier spawned during
  * that construction runs through a distinctly named wrapper frame.
  *
  * There are two such transports: the `NetPlatform.transport` singleton (shared across every client and server in the process) and the default
  * HTTP client's own transport (built via `NetPlatform.processLifetimeTransport`; a distinct pool the default client owns but never closes).
  * Each blocks head-of-line in the OS poll call for the JVM's lifetime, so at a test fork's end it shows up as a busy scheduler fiber with an
  * idle kept-alive connection parked. That is expected infrastructure, not a leak. The kyo-test end-of-run fiber-leak and stranded-op checks
  * allowlist the wrapper frame this marker produces (`processSharedTransport`), which appears only on these process-lifetime transports'
  * carriers. An owned transport a caller closes keeps the plain `pollLoop`/`reapLoop` frame, so a genuinely leaked owned transport still trips
  * the check rather than being masked by a broad driver-name allowlist.
  *
  * The flag is a build-scoped thread-local: `whileBuilding` sets it around a process-lifetime transport's construction, and each driver's
  * `start()` reads it on the same (construction) thread when it decides which carrier body to spawn. The carrier body then runs on a scheduler
  * worker, but the decision is made before the spawn, so the worker's stack carries the chosen frame. Only the process-lifetime construction
  * paths set the flag; every owned per-config construction a caller will close reads `false` and uses the plain carrier.
  */
private[net] object ProcessSharedTransport:

    private val building =
        new ThreadLocal[Boolean]:
            override def initialValue(): Boolean = false

    /** True while [[whileBuilding]] is constructing the process-shared default transport on the current thread. Read by each driver's
      * `start()` to decide whether to spawn its carrier through the named, allowlisted wrapper.
      */
    def isBuilding: Boolean = building.get()

    /** Run `f` (the construction of the process-shared default transport) with [[isBuilding]] true on the current thread, restoring the prior
      * value afterward. Driver `start()` calls made synchronously inside `f` observe the flag and mark their carriers.
      */
    def whileBuilding[A](f: => A): A =
        val prior = building.get()
        building.set(true)
        try f
        finally building.set(prior)
    end whileBuilding

end ProcessSharedTransport
