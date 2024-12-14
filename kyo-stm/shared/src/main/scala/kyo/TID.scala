package kyo

private[kyo] object TID:

    // Unique transaction ID generation
    private val nextTid = AtomicLong.Unsafe.init(0)(using AllowUnsafe.embrace.danger)

    private val tidLocal = Local.initIsolated[java.lang.Long](-1L)

    def next(using AllowUnsafe): Long = nextTid.incrementAndGet()

    inline def useNew[A, S](inline f: Long => A < S)(using inline frame: Frame): A < (S & IO) =
        IO.Unsafe {
            val tid = nextTid.incrementAndGet()
            tidLocal.let(tid)(f(tid))
        }

    inline def use[A, S](inline f: Long => A < S)(using inline frame: Frame): A < S =
        tidLocal.use(f(_))

    inline def useRequired[A, S](inline f: Long => A < S)(using inline frame: Frame): A < S =
        tidLocal.use {
            case -1L => bug("STM operation attempted outside of STM.run - this should be impossible due to effect typing")
            case tid => f(tid)
        }
end TID
