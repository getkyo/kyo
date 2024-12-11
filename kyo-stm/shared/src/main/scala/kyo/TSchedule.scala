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

    given Flat[TSchedule] = Flat.derive[TRef[Maybe[(Duration, Schedule)]]]

    /** Creates a new transactional schedule within an STM transaction.
      *
      * @param schedule
      *   The initial schedule to wrap
      * @return
      *   A new transactional schedule containing the schedule state, within the STM effect
      */
    def init(schedule: Schedule)(using Frame): TSchedule < STM = Clock.now.map(now => TRef.init(schedule.next(now)))

    /** Creates a new transactional schedule outside of a transaction.
      *
      * WARNING: This operation:
      *   - Cannot be rolled back
      *   - Is not part of any transaction
      *   - Will cause any containing transaction to retry if used within one
      *
      * Use this only for static initialization or when you specifically need non-transactional creation. For most cases, prefer `init`.
      *
      * @param schedule
      *   The initial schedule to wrap
      * @return
      *   A new transactional schedule containing the schedule state, within IO
      */
    def initNow(schedule: Schedule)(using Frame): TSchedule < IO = Clock.now.map(now => TRef.initNow(schedule.next(now)))

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
