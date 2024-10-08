package kyo

/** A Meter is an abstract class that represents a mechanism for controlling concurrency and rate limiting.
  */
abstract class Meter:
    self =>

    /** Returns the number of available permits.
      *
      * @return
      *   The number of available permits as an Int effect.
      */
    def available(using Frame): Int < IO

    /** Checks if there are any available permits.
      *
      * @return
      *   A Boolean effect indicating whether permits are available.
      */
    def isAvailable(using Frame): Boolean < IO =
        available.map(_ > 0)

    /** Runs an effect after acquiring a permit.
      *
      * @param v
      *   The effect to run.
      * @tparam A
      *   The return type of the effect.
      * @tparam S
      *   The effect type.
      * @return
      *   The result of running the effect.
      */
    def run[A, S](v: => A < S)(using Frame): A < (S & Async)

    /** Attempts to run an effect if a permit is available.
      *
      * @param v
      *   The effect to run.
      * @tparam A
      *   The return type of the effect.
      * @tparam S
      *   The effect type.
      * @return
      *   A Maybe containing the result of running the effect, or Empty if no permit was available.
      */
    def tryRun[A, S](v: => A < S)(using Frame): Maybe[A] < (IO & S)

    /** Closes the Meter.
      *
      * @return
      *   A Boolean effect indicating whether the Meter was successfully closed.
      */
    def close(using Frame): Boolean < IO
end Meter

object Meter:

    /** A no-op Meter that always allows operations. */
    case object Noop extends Meter:
        def available(using Frame)                 = Int.MaxValue
        def run[A, S](v: => A < S)(using Frame)    = v
        def tryRun[A, S](v: => A < S)(using Frame) = v.map(Maybe(_))
        def close(using Frame)                     = false
    end Noop

    /** Creates a Meter that acts as a mutex (binary semaphore).
      *
      * @return
      *   A Meter effect that represents a mutex.
      */
    def initMutex(using Frame): Meter < IO =
        initSemaphore(1)

    /** Creates a Meter that acts as a semaphore with the specified concurrency.
      *
      * @param concurrency
      *   The number of concurrent operations allowed.
      * @return
      *   A Meter effect that represents a semaphore.
      */
    def initSemaphore(concurrency: Int)(using Frame): Meter < IO =
        Channel.init[Unit](concurrency).map { chan =>
            offer(concurrency, chan, ()).map { _ =>
                new Meter:
                    def available(using Frame) = chan.size
                    def release(using Frame)   = chan.offerUnit(())

                    def run[A, S](v: => A < S)(using Frame) =
                        IO.ensure(release) {
                            chan.take.andThen(v)
                        }

                    def tryRun[A, S](v: => A < S)(using Frame) =
                        IO.Unsafe {
                            chan.unsafePoll match
                                case Maybe.Empty => Maybe.empty
                                case _ =>
                                    IO.ensure(release) {
                                        v.map(Maybe(_))
                                    }
                        }

                    def close(using Frame) =
                        chan.close.map(_.isDefined)
            }
        }

    /** Creates a Meter that acts as a rate limiter.
      *
      * @param rate
      *   The number of operations allowed per period.
      * @param period
      *   The duration of each period.
      * @return
      *   A Meter effect that represents a rate limiter.
      */
    def initRateLimiter(rate: Int, period: Duration)(using Frame): Meter < IO =
        Channel.init[Unit](rate).map { chan =>
            Timer.scheduleAtFixedRate(period)(offer(rate, chan, ())).map { _ =>
                new Meter:

                    def available(using Frame)              = chan.size
                    def run[A, S](v: => A < S)(using Frame) = chan.take.map(_ => v)

                    def tryRun[A, S](v: => A < S)(using Frame) =
                        chan.poll.map {
                            case Maybe.Empty =>
                                Maybe.empty
                            case _ =>
                                v.map(Maybe(_))
                        }

                    def close(using Frame) =
                        chan.close.map(_.isDefined)
            }
        }

    /** Combines two Meters into a pipeline.
      *
      * @param m1
      *   The first Meter.
      * @param m2
      *   The second Meter.
      * @return
      *   A Meter effect that represents the pipeline of m1 and m2.
      */
    def pipeline[S1, S2](m1: Meter < S1, m2: Meter < S2)(using Frame): Meter < (IO & S1 & S2) =
        pipeline[S1 & S2](List(m1, m2))

    /** Combines three Meters into a pipeline.
      *
      * @param m1
      *   The first Meter.
      * @param m2
      *   The second Meter.
      * @param m3
      *   The third Meter.
      * @return
      *   A Meter effect that represents the pipeline of m1, m2, and m3.
      */
    def pipeline[S1, S2, S3](
        m1: Meter < S1,
        m2: Meter < S2,
        m3: Meter < S3
    )(using Frame): Meter < (IO & S1 & S2 & S3) =
        pipeline[S1 & S2 & S3](List(m1, m2, m3))

    /** Combines four Meters into a pipeline.
      *
      * @param m1
      *   The first Meter.
      * @param m2
      *   The second Meter.
      * @param m3
      *   The third Meter.
      * @param m4
      *   The fourth Meter.
      * @return
      *   A Meter effect that represents the pipeline of m1, m2, m3, and m4.
      */
    def pipeline[S1, S2, S3, S4](
        m1: Meter < S1,
        m2: Meter < S2,
        m3: Meter < S3,
        m4: Meter < S4
    )(using Frame): Meter < (IO & S1 & S2 & S3 & S4) =
        pipeline[S1 & S2 & S3 & S4](List(m1, m2, m3, m4))

    /** Combines a sequence of Meters into a pipeline.
      *
      * @param meters
      *   The sequence of Meters to combine.
      * @return
      *   A Meter effect that represents the pipeline of all input Meters.
      */
    def pipeline[S](meters: Seq[Meter < (IO & S)])(using Frame): Meter < (IO & S) =
        Kyo.collect(meters).map { seq =>
            val meters = seq.toIndexedSeq
            new Meter:

                def available(using Frame) =
                    Loop.indexed(0) { (idx, acc) =>
                        if idx == meters.length then Loop.done(acc)
                        else meters(idx).available.map(v => Loop.continue(acc + v))
                    }

                def run[A, S](v: => A < S)(using Frame) =
                    def loop(idx: Int = 0): A < (S & Async) =
                        if idx == meters.length then v
                        else meters(idx).run(loop(idx + 1))
                    loop()
                end run

                def tryRun[A, S](v: => A < S)(using Frame) =
                    def loop(idx: Int = 0): Maybe[A] < (S & IO) =
                        if idx == meters.length then v.map(Maybe(_))
                        else
                            meters(idx).tryRun(loop(idx + 1)).map {
                                case Maybe.Empty => Maybe.empty
                                case r           => r.flatten
                            }
                    loop()
                end tryRun

                def close(using Frame): Boolean < IO =
                    Kyo.foreach(meters)(_.close).map(_.exists(identity))
            end new
        }

    private def offer[A](n: Int, chan: Channel[A], v: A)(using Frame): Unit < IO =
        Loop.indexed { idx =>
            if idx == n then Loop.done
            else
                chan.offer(v).map {
                    case true  => Loop.continue
                    case false => Loop.done
                }
        }
end Meter
