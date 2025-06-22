package kyo

/** A transactional wrapper around Schedule that provides atomic access to schedule state.
  *
  * TSchedule makes schedule progression atomic by wrapping the underlying schedule in a TRef and exposing its operations through STM
  * transactions. This ensures that concurrent accesses to the schedule's state are properly synchronized.
  *
  * @param ref
  *   The transactional reference containing the current schedule state
  */
opaque type TSchedule = TRef[Maybe[(Duration, Schedule)]]

object TSchedule:

    /** Creates a new TSchedule with the given schedule.
      *
      * @param schedule
      *   The initial schedule to wrap
      * @return
      *   A new TSchedule containing the schedule, within the Sync effect
      */
    def init(schedule: Schedule)(using Frame): TSchedule < Sync =
        initWith(schedule)(identity)

    /** Creates a new TSchedule and immediately applies a function to it.
      *
      * This is a more efficient way to initialize a TSchedule and perform operations on it, as it combines initialization and the first
      * operation in a single transaction.
      *
      * @param schedule
      *   The initial schedule to wrap
      * @param f
      *   The function to apply to the newly created TSchedule
      * @return
      *   The result of applying the function to the new TSchedule, within combined Sync and S effects
      */
    inline def initWith[A, S](schedule: Schedule)(inline f: TSchedule => A < S)(using Frame): A < (Sync & S) =
        Clock.now.map(now => TRef.initWith(schedule.next(now))(f))

    extension (self: TSchedule)

        /** Applies a function to the next schedule duration within a transaction.
          *
          * This operation:
          *   - Uses the next scheduled duration if available
          *   - Updates the internal schedule state to prepare for the next call
          *   - Returns Maybe.empty if the schedule is complete
          *   - Is atomic - concurrent calls will see consistent schedule progression
          *
          * @param f
          *   Function that transforms the current schedule state
          * @return
          *   The result of applying the function, within combined STM and S effects
          */
        def use[B, S](f: Maybe[Duration] => B < S)(using Frame): B < (STM & S) =
            self.use {
                case Absent => f(Absent)
                case Present((duration, nextSchedule)) =>
                    Clock.now.map { now =>
                        self.set(nextSchedule.next(now)).andThen(f(Maybe(duration)))
                    }
            }

        /** Retrieves the next duration and updates the schedule state atomically.
          *
          * This operation:
          *   - Returns the next scheduled duration if available
          *   - Updates the internal schedule state to prepare for the next call
          *   - Returns Maybe.empty if the schedule is complete
          *   - Is atomic - concurrent calls will see consistent schedule progression
          *
          * @return
          *   The next duration if available, within the STM effect
          */
        def next(using Frame): Maybe[Duration] < STM =
            TSchedule.use(self)(identity)

        /** Retrieves the next duration without updating the schedule state.
          *
          * This allows peeking at the next duration without affecting the schedule's progression.
          *
          * @return
          *   The next duration if available, within the STM effect
          */
        def peek(using Frame): Maybe[Duration] < STM =
            self.use(_.map(_._1))
    end extension

end TSchedule
