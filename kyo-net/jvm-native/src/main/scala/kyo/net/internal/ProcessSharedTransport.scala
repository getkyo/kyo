package kyo.net.internal

/** Marks the construction of the process-shared default transport ([[kyo.net.NetPlatform.transport]]) so each I/O driver's poll/reap carrier
  * spawned during that construction runs through a distinctly named wrapper frame.
  *
  * `NetPlatform.transport` is a process-lifetime singleton that is never closed by design (it is shared across every client and server in the
  * process). Its idle carrier blocks head-of-line in the OS poll call for the JVM's lifetime, so at a test fork's end it shows up as a busy
  * scheduler fiber. That is expected infrastructure, not a leak. The kyo-test end-of-run fiber-leak check allowlists the wrapper frame this
  * marker produces (`processSharedTransport`), which appears ONLY on the shared singleton's carriers. An owned transport (built per-config and
  * closed by its owner) keeps the plain `pollLoop`/`reapLoop` frame, so a genuinely leaked owned transport still trips the check rather than
  * being masked by a broad driver-name allowlist.
  *
  * The flag is a build-scoped thread-local: `whileBuilding` sets it around the singleton's construction, and each driver's `start()` reads it
  * on the same (construction) thread when it decides which carrier body to spawn. The carrier body then runs on a scheduler worker, but the
  * decision is made before the spawn, so the worker's stack carries the chosen frame. Only the singleton build sets the flag; every other
  * transport construction reads `false` and uses the plain carrier.
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
