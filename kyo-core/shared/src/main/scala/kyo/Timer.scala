package kyo

/** A utility for scheduling recurring tasks with different timing strategies.
  *
  * Timer provides two main scheduling approaches:
  *   - `repeatWithDelay`: Executes tasks with a fixed delay between task completions
  *   - `repeatAtInterval`: Executes tasks at fixed time intervals, regardless of task duration
  */
object Timer:

    /** Repeatedly executes a task with a fixed delay between completions.
      *
      * The delay timer starts after each task completion, making this suitable for tasks that should maintain a minimum gap between
      * executions.
      *
      * @param delay
      *   The duration to wait after each task completion before starting the next execution
      * @param f
      *   The task to execute
      * @return
      *   A Fiber that can be used to control or interrupt the recurring task
      */
    def repeatWithDelay[E, S](delay: Duration)(f: => Unit < (Async & Abort[E]))(using Frame): Fiber[E, Unit] < IO =
        repeatWithDelay(Duration.Zero, delay)(f)

    /** Repeatedly executes a task with a fixed delay between completions, starting after an initial delay.
      *
      * @param startAfter
      *   The duration to wait before the first execution
      * @param delay
      *   The duration to wait after each task completion before starting the next execution
      * @param f
      *   The task to execute
      * @return
      *   A Fiber that can be used to control or interrupt the recurring task
      */
    def repeatWithDelay[E, S](
        startAfter: Duration,
        delay: Duration
    )(
        f: => Unit < (Async & Abort[E])
    )(using Frame): Fiber[E, Unit] < IO =
        repeatWithDelay(startAfter, delay, ())(_ => f)

    /** Repeatedly executes a task with a fixed delay between completions, maintaining state between executions.
      *
      * @param startAfter
      *   The duration to wait before the first execution
      * @param delay
      *   The duration to wait after each task completion before starting the next execution
      * @param state
      *   The initial state value
      * @param f
      *   A function that takes the current state and returns the next state
      * @tparam A
      *   The type of the state value
      * @return
      *   A Fiber that can be used to control or interrupt the recurring task and access the final state
      */
    def repeatWithDelay[E, A: Flat, S](
        startAfter: Duration,
        delay: Duration,
        state: A
    )(
        f: A => A < (Async & Abort[E])
    )(using Frame): Fiber[E, A] < IO =
        repeatWithDelay(Schedule.delay(startAfter).andThen(Schedule.fixed(delay)), state)(f)

    /** Repeatedly executes a task with delays determined by a custom schedule.
      *
      * @param delaySchedule
      *   A schedule that determines the timing between executions
      * @param f
      *   The task to execute
      * @return
      *   A Fiber that can be used to control or interrupt the recurring task
      */
    def repeatWithDelay[E, S](delaySchedule: Schedule)(f: => Unit < (Async & Abort[E]))(using Frame): Fiber[E, Unit] < IO =
        repeatWithDelay(delaySchedule, ())(_ => f)

    /** Repeatedly executes a task with delays determined by a custom schedule, maintaining state between executions.
      *
      * @param delaySchedule
      *   A schedule that determines the timing between executions
      * @param state
      *   The initial state value
      * @param f
      *   A function that takes the current state and returns the next state
      * @tparam A
      *   The type of the state value
      * @return
      *   A Fiber that can be used to control or interrupt the recurring task and access the final state
      */
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

    /** Repeatedly executes a task at fixed time intervals.
      *
      * Unlike repeatWithDelay, this ensures consistent execution intervals regardless of task duration. If a task takes longer than the
      * interval, the next execution will start immediately after completion.
      *
      * @param interval
      *   The fixed time interval between task starts
      * @param f
      *   The task to execute
      * @return
      *   A Fiber that can be used to control or interrupt the recurring task
      */
    def repeatAtInterval[E, S](interval: Duration)(f: => Unit < (Async & Abort[E]))(using Frame): Fiber[E, Unit] < IO =
        repeatAtInterval(Duration.Zero, interval)(f)

    /** Repeatedly executes a task at fixed time intervals, starting after an initial delay.
      *
      * @param startAfter
      *   The duration to wait before the first execution
      * @param interval
      *   The fixed time interval between task starts
      * @param f
      *   The task to execute
      * @return
      *   A Fiber that can be used to control or interrupt the recurring task
      */
    def repeatAtInterval[E, S](
        startAfter: Duration,
        interval: Duration
    )(
        f: => Unit < (Async & Abort[E])
    )(using Frame): Fiber[E, Unit] < IO =
        repeatAtInterval(startAfter, interval, ())(_ => f)

    /** Repeatedly executes a task at fixed time intervals, maintaining state between executions.
      *
      * @param startAfter
      *   The duration to wait before the first execution
      * @param interval
      *   The fixed time interval between task starts
      * @param state
      *   The initial state value
      * @param f
      *   A function that takes the current state and returns the next state
      * @tparam A
      *   The type of the state value
      * @return
      *   A Fiber that can be used to control or interrupt the recurring task and access the final state
      */
    def repeatAtInterval[E, A: Flat, S](
        startAfter: Duration,
        interval: Duration,
        state: A
    )(
        f: A => A < (Async & Abort[E])
    )(using Frame): Fiber[E, A] < IO =
        repeatAtInterval(Schedule.delay(startAfter).andThen(Schedule.fixed(interval)), state)(f)

    /** Repeatedly executes a task with intervals determined by a custom schedule.
      *
      * @param intervalSchedule
      *   A schedule that determines the timing between executions
      * @param f
      *   The task to execute
      * @return
      *   A Fiber that can be used to control or interrupt the recurring task
      */
    def repeatAtInterval[E, S](intervalSchedule: Schedule)(f: => Unit < (Async & Abort[E]))(using Frame): Fiber[E, Unit] < IO =
        repeatAtInterval(intervalSchedule, ())(_ => f)

    /** Repeatedly executes a task with intervals determined by a custom schedule, maintaining state between executions.
      *
      * @param intervalSchedule
      *   A schedule that determines the timing between executions
      * @param state
      *   The initial state value
      * @param f
      *   A function that takes the current state and returns the next state
      * @tparam A
      *   The type of the state value
      * @return
      *   A Fiber that can be used to control or interrupt the recurring task and access the final state
      */
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
