package kyo

object Timer:

    def repeatWithDelay[E, S](delay: Duration)(f: => Unit < (Async & Abort[E]))(using Frame): Fiber[E, Unit] < IO =
        repeatWithDelay(Duration.Zero, delay)(f)

    def repeatWithDelay[E, S](
        startAfter: Duration,
        delay: Duration
    )(
        f: => Unit < (Async & Abort[E])
    )(using Frame): Fiber[E, Unit] < IO =
        repeatWithDelay(startAfter, delay, ())(_ => f)

    def repeatWithDelay[E, A: Flat, S](
        startAfter: Duration,
        delay: Duration,
        state: A
    )(
        f: A => A < (Async & Abort[E])
    )(using Frame): Fiber[E, A] < IO =
        repeatWithDelay(Schedule.delay(startAfter).andThen(Schedule.fixed(delay)), state)(f)

    def repeatWithDelay[E, S](delaySchedule: Schedule)(f: => Unit < (Async & Abort[E]))(using Frame): Fiber[E, Unit] < IO =
        repeatWithDelay(delaySchedule, ())(_ => f)

    def repeatWithDelay[E, A: Flat, S](
        delaySchedule: Schedule,
        state: A
    )(
        f: A => A < (Async & Abort[E])
    )(using Frame): Fiber[E, A] < IO =
        Async.run {
            Clock.use { clock =>
                Loop(state, delaySchedule) { (state, schedule) =>
                    schedule.next match
                        case Absent => Loop.done(state)
                        case Present((duration, nextSchedule)) =>
                            clock.sleep(duration).map(_.use(_ => f(state).map(Loop.continue(_, nextSchedule))))
                }
            }
        }

    def repeatAtInterval[E, S](interval: Duration)(f: => Unit < (Async & Abort[E]))(using Frame): Fiber[E, Unit] < IO =
        repeatAtInterval(Duration.Zero, interval)(f)

    def repeatAtInterval[E, S](
        startAfter: Duration,
        interval: Duration
    )(
        f: => Unit < (Async & Abort[E])
    )(using Frame): Fiber[E, Unit] < IO =
        repeatAtInterval(startAfter, interval, ())(_ => f)

    def repeatAtInterval[E, A: Flat, S](
        startAfter: Duration,
        interval: Duration,
        state: A
    )(
        f: A => A < (Async & Abort[E])
    )(using Frame): Fiber[E, A] < IO =
        repeatAtInterval(Schedule.delay(startAfter).andThen(Schedule.fixed(interval)), state)(f)

    def repeatAtInterval[E, S](intervalSchedule: Schedule)(f: => Unit < (Async & Abort[E]))(using Frame): Fiber[E, Unit] < IO =
        repeatAtInterval(intervalSchedule, ())(_ => f)

    def repeatAtInterval[E, A: Flat, S](
        intervalSchedule: Schedule,
        state: A
    )(
        f: A => A < (Async & Abort[E])
    )(using Frame): Fiber[E, A] < IO =
        Async.run {
            Clock.use { clock =>
                clock.now.map { now =>
                    Loop(now, state, intervalSchedule) { (lastExecution, state, period) =>
                        period.next match
                            case Absent => Loop.done(state)
                            case Present((duration, nextSchedule)) =>
                                val nextExecution = lastExecution + duration
                                clock.sleep(duration).map(_.use(_ => f(state).map(Loop.continue(nextExecution, _, nextSchedule))))
                    }
                }
            }
        }
end Timer
