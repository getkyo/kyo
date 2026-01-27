package kyo

/** Monotonic tick value for STM conflict detection */
private[kyo] opaque type Tick = Long

private[kyo] object Tick:

    given CanEqual[Tick, Tick] = CanEqual.derived

    private val counter = AtomicLong.Unsafe.init(0)(using AllowUnsafe.embrace.danger)
    private val local   = Local.initNoninheritable[java.lang.Long](-1L)

    /** Generate a new tick value */
    def next()(using AllowUnsafe): Tick = counter.incrementAndGet()

    /** Set counter value. For testing only. */
    def setCounter(value: Long)(using AllowUnsafe): Unit = counter.set(value)

    /** Run with a new tick (starts a new transaction scope) */
    inline def withNext[A, S](inline f: Tick => A < S)(using inline frame: Frame): A < (S & Sync) =
        Sync.Unsafe {
            val tick: Tick = counter.incrementAndGet()
            local.let(tick)(f(tick))
        }

    /** Use current tick (fails if not in transaction) */
    inline def withCurrent[A, S](inline f: Tick => A < S)(using inline frame: Frame): A < (S & Sync) =
        Sync.withLocal(local) {
            case -1L  => bug("STM operation attempted outside of STM.run")
            case tick => f(tick)
        }

    /** Run ifAbsent when not in transaction, ifPresent when in transaction */
    inline def withCurrent[A, S](
        inline ifAbsent: => A < S,
        inline ifPresent: => A < S
    )(using inline frame: Frame): A < (S & Sync) =
        Sync.withLocal(local) {
            case -1L => ifAbsent
            case _   => ifPresent
        }

    /** Use current tick if in transaction, otherwise generate a new one */
    inline def withCurrentOrNext[A, S](inline f: AllowUnsafe ?=> Tick => A < S)(using inline frame: Frame): A < (S & Sync) =
        Sync.Unsafe.withLocal(local) { current =>
            val tick: Tick = if current == -1L then counter.incrementAndGet() else current
            f(tick)
        }

    extension (self: Tick)
        inline def value: Long = self
    end extension

end Tick
