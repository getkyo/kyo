package kyo

import kyo.scheduler.Scheduler

/** Exception thrown when a task is rejected by the admission control system.
  *
  * Contains the frame where the rejection occurred for debugging and error handling. Handlers can use this information to implement
  * appropriate retry or fallback logic.
  *
  * @param frame
  *   The execution frame where the task was rejected
  */
class Rejected()(using Frame) extends KyoException("Admisssion control has rejected execution to mitigate overloading.")

/** Admission control mechanism that helps prevent system overload by selectively rejecting tasks at the boundary of incoming workload.
  *
  * Designed to be used at system entry points where external requests first arrive (e.g. API endpoints, message consumers, etc.), this
  * mechanism helps maintain system stability under high load. The admission controller maintains a dynamically adjusting admission
  * percentage based on system conditions. When congestion is detected, the admission rate decreases to shed load. As conditions improve,
  * the rate gradually increases to admit more tasks.
  *
  * Tasks can be rejected probabilistically for one-off operations, or deterministically using string/integer keys for consistent admission
  * decisions across related tasks.
  *
  * @see
  *   [[kyo.scheduler.regulator.Admission]] for detailed implementation and configuration
  */
object Admission:

    /** Executes a computation with admission control using a string key for consistent decisions.
      *
      * This method provides consistent admission control by using the string's hash as a sampling key. This ensures that identical strings
      * will receive the same admission decision at any given admission percentage, creating stable and predictable load shedding patterns.
      *
      * This consistency is particularly valuable for:
      *   - User IDs or session identifiers to maintain consistent user experience
      *   - Transaction or operation IDs for related task sets
      *   - Service names or endpoints for targeted load shedding
      *   - Any scenario requiring deterministic admission control
      *
      * @param key
      *   String to use for admission decision
      * @param v
      *   The computation to execute if admitted
      * @return
      *   The computation result if admitted, or Rejected if the task is rejected
      */
    def run[A, S](key: String)(v: A < S)(using frame: Frame): A < (Sync & S & Abort[Rejected]) =
        Sync {
            if Scheduler.get.reject(key) then Abort.fail(Rejected())
            else v
        }

    /** Executes a computation with admission control using an integer key for consistent decisions.
      *
      * This method provides consistent admission decisions by using the integer directly as a sampling key. It guarantees that identical
      * integers will receive the same admission decision at any given admission percentage, implemented through efficient modulo
      * operations.
      *
      * This method is particularly useful for:
      *   - Numeric identifiers like user IDs or request sequence numbers
      *   - Hash values from other sources
      *   - Cases where the caller has already computed a suitable numeric key
      *   - Performance-critical scenarios needing minimal overhead
      *
      * @param key
      *   Integer to use for admission decision
      * @param v
      *   The computation to execute if admitted
      * @return
      *   The computation result if admitted, or Rejected if the task is rejected
      */
    def run[A, S](key: Int)(v: A < S)(using frame: Frame): A < (Sync & S & Abort[Rejected]) =
        Sync {
            if Scheduler.get.reject(key) then Abort.fail(Rejected())
            else v
        }

    /** Executes a computation with probabilistic admission control.
      *
      * The scheduler uses admission control to prevent system overload by selectively rejecting tasks when detecting signs of congestion.
      * This method provides probabilistic load shedding using random sampling, making it suitable for one-off tasks where consistent
      * admission decisions aren't required.
      *
      * This approach works well for:
      *   - One-off tasks with no related operations
      *   - Tasks where consistent rejection isn't critical
      *   - High-volume scenarios where perfect distribution isn't necessary
      *   - Cases where no natural key exists for the task
      *
      * @param v
      *   The computation to execute if admitted
      * @return
      *   The computation result if admitted, or Rejected if the task is rejected
      */
    def run[A, S](v: A < S)(using frame: Frame): A < (Sync & S & Abort[Rejected]) =
        Sync {
            if Scheduler.get.reject() then Abort.fail(Rejected())
            else v
        }

    /** Tests if a new task should be rejected based on current system conditions.
      *
      * The scheduler uses admission control to prevent system overload by selectively rejecting tasks when detecting signs of congestion.
      * This method provides probabilistic load shedding using random sampling, making it suitable for one-off tasks where consistent
      * admission decisions aren't required.
      *
      * This approach works well for:
      *   - One-off tasks with no related operations
      *   - Tasks where consistent rejection isn't critical
      *   - High-volume scenarios where perfect distribution isn't necessary
      *   - Cases where no natural key exists for the task
      *
      * For tasks requiring consistent admission decisions (e.g., related operations that should be handled similarly), prefer using
      * reject(key) or reject(string) instead.
      *
      * @return
      *   true if the task should be rejected, false if it can be accepted
      */
    def reject()(using Frame): Boolean < Sync =
        Sync(Scheduler.get.reject())

    /** Tests if a task with the given string key should be rejected based on current system conditions.
      *
      * This method provides consistent admission decisions by using the string's hash as a sampling key. This ensures that identical
      * strings will receive the same admission decision at any given admission percentage, creating stable and predictable load shedding
      * patterns.
      *
      * This consistency is particularly valuable for:
      *   - User IDs or session identifiers to maintain consistent user experience
      *   - Transaction or operation IDs for related task sets
      *   - Service names or endpoints for targeted load shedding
      *   - Any scenario requiring deterministic admission control
      *
      * The string-based rejection provides several benefits:
      *   - Related requests from the same user/session get uniform treatment
      *   - Retries of rejected tasks won't add load since they'll stay rejected
      *   - System stabilizes with a consistent subset of flowing traffic
      *   - Natural backpressure mechanism for distributed systems
      *
      * @param key
      *   String to use for admission decision
      * @return
      *   true if the task should be rejected, false if it can be accepted
      */
    def reject(key: String)(using Frame): Boolean < Sync =
        Sync(Scheduler.get.reject(key))

    /** Tests if a task with the given integer key should be rejected based on current system conditions.
      *
      * This method provides consistent admission decisions by using the integer directly as a sampling key. It guarantees that identical
      * integers will receive the same admission decision at any given admission percentage, implemented through efficient modulo
      * operations.
      *
      * This method is particularly useful for:
      *   - Numeric identifiers like user IDs or request sequence numbers
      *   - Hash values from other sources
      *   - Cases where the caller has already computed a suitable numeric key
      *   - Performance-critical scenarios needing minimal overhead
      *
      * The integer-based rejection maintains the same consistency benefits as string-based rejection:
      *   - Deterministic decisions for identical keys
      *   - Stable load shedding patterns
      *   - Efficient handling of related operations
      *   - Natural queueing behavior for rejected requests
      *
      * @param key
      *   Integer to use for admission decision
      * @return
      *   true if the task should be rejected, false if it can be accepted
      */
    def reject(key: Int)(using Frame): Boolean < Sync =
        Sync(Scheduler.get.reject(key))

end Admission
