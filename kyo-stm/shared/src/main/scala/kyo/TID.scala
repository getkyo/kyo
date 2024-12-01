package kyo

private[kyo] object TID:

    // Unique transaction and reference ID generation
    private val nextTid = AtomicLong.Unsafe.init(0)(using AllowUnsafe.embrace.danger)

    private val tidLocal = Local.initIsolated(-1L)

    def next(using AllowUnsafe): Long = nextTid.incrementAndGet()

    def useNew[A, S](f: Long => A < S)(using Frame): A < (S & IO) =
        IO.Unsafe {
            val tid = nextTid.incrementAndGet()
            tidLocal.let(tid)(f(tid))
        }

    def use[A, S](f: Long => A < S)(using Frame): A < S =
        tidLocal.use(f)

    def useRequired[A, S](f: Long => A < S)(using Frame): A < S =
        tidLocal.use {
            case -1L => bug("STM operation attempted outside of STM.run - this should be impossible due to effect typing")
            case tid => f(tid)
        }
end TID
