package kyo

import java.util.concurrent.atomic.AtomicInteger as JAtomicInteger
import org.scalatest.compatible.Assertion

class TimerTest extends Test:

    def intervals(instants: Seq[Instant]): Seq[Duration] =
        instants.sliding(2, 1).filter(_.size == 2).map(seq => seq(1) - seq(0)).toSeq

    "repeatAtInterval" - {
        "executes function at interval" in run {
            for
                channel  <- Channel.init[Instant](10)
                task     <- Timer.repeatAtInterval(1.millis)(Clock.now.map(channel.put))
                instants <- Kyo.fill(10)(channel.take)
                _        <- task.interrupt
            yield
                val avgInterval = intervals(instants).reduce(_ + _) * (1.toDouble / (instants.size - 2))
                assert(avgInterval >= 1.millis && avgInterval < 5.millis)
        }
        "respects interrupt" in run {
            for
                channel  <- Channel.init[Instant](10)
                task     <- Timer.repeatAtInterval(1.millis)(Clock.now.map(channel.put))
                instants <- Kyo.fill(10)(channel.take)
                _        <- task.interrupt
                _        <- Async.sleep(2.millis)
                result   <- channel.poll
            yield assert(result.isEmpty)
        }
        // "with time control" in run {
        //     Clock.withTimeControl { control =>
        //         for
        //             running  <- AtomicBoolean.init(false)
        //             queue    <- Queue.Unbounded.init[Instant]()
        //             task     <- Timer.repeatAtInterval(1.milli)(running.set(true).andThen(Clock.now.map(queue.add)))
        //             _        <- untilTrue(control.advance(1.milli).andThen(running.get))
        //             _        <- queue.drain
        //             _        <- control.advance(1.milli).repeat(10)
        //             _        <- task.interrupt
        //             instants <- queue.drain
        //         yield
        //             intervals(instants).foreach(v => assert(v == 1.millis))
        //             succeed
        //     }
        // }
        "with Schedule parameter" in run {
            for
                channel  <- Channel.init[Instant](10)
                task     <- Timer.repeatAtInterval(Schedule.fixed(1.millis))(Clock.now.map(channel.put))
                instants <- Kyo.fill(10)(channel.take)
                _        <- task.interrupt
            yield
                val avgInterval = intervals(instants).reduce(_ + _) * (1.toDouble / (instants.size - 2))
                assert(avgInterval >= 1.millis && avgInterval < 5.millis)
        }
        "with Schedule and state" in run {
            for
                channel <- Channel.init[Int](10)
                task    <- Timer.repeatAtInterval(Schedule.fixed(1.millis), 0)(st => channel.put(st).andThen(st + 1))
                numbers <- Kyo.fill(10)(channel.take)
                _       <- task.interrupt
            yield assert(numbers.toSeq == (0 until 10))
        }
        "completes when schedule completes" in run {
            for
                channel <- Channel.init[Int](10)
                task    <- Timer.repeatAtInterval(Schedule.fixed(1.millis).maxDuration(10.millis), 0)(st => channel.put(st).andThen(st + 1))
                lastState <- task.get
                numbers   <- channel.drain
            yield assert(lastState == 10 && numbers.toSeq == (0 until 10))
        }
    }

    "repeatWithDelay" - {
        "executes function with delay" in run {
            for
                channel  <- Channel.init[Instant](10)
                task     <- Timer.repeatWithDelay(1.millis)(Clock.now.map(channel.put))
                instants <- Kyo.fill(10)(channel.take)
                _        <- task.interrupt
            yield
                val avgDelay = intervals(instants).reduce(_ + _) * (1.toDouble / (instants.size - 2))
                assert(avgDelay >= 1.millis && avgDelay < 5.millis)
        }

        "respects interrupt" in run {
            for
                channel  <- Channel.init[Instant](10)
                task     <- Timer.repeatWithDelay(1.millis)(Clock.now.map(channel.put))
                instants <- Kyo.fill(10)(channel.take)
                _        <- task.interrupt
                _        <- Async.sleep(2.millis)
                result   <- channel.poll
            yield assert(result.isEmpty)
        }

        // "with time control" in run {
        //     Clock.withTimeControl { control =>
        //         for
        //             running  <- AtomicBoolean.init(false)
        //             queue    <- Queue.Unbounded.init[Instant]()
        //             task     <- Timer.repeatWithDelay(1.milli)(running.set(true).andThen(Clock.now.map(queue.add)))
        //             _        <- untilTrue(control.advance(1.milli).andThen(running.get))
        //             _        <- queue.drain
        //             _        <- control.advance(1.milli).repeat(10)
        //             _        <- task.interrupt
        //             instants <- queue.drain
        //         yield
        //             intervals(instants).foreach(v => assert(v == 1.millis))
        //             succeed
        //     }
        // }

        "works with Schedule parameter" in run {
            for
                channel  <- Channel.init[Instant](10)
                task     <- Timer.repeatWithDelay(Schedule.fixed(1.millis))(Clock.now.map(channel.put))
                instants <- Kyo.fill(10)(channel.take)
                _        <- task.interrupt
            yield
                val avgDelay = intervals(instants).reduce(_ + _) * (1.toDouble / (instants.size - 2))
                assert(avgDelay >= 1.millis && avgDelay < 5.millis)
        }

        "works with Schedule and state" in run {
            val counter = new JAtomicInteger(0)
            for
                channel <- Channel.init[Int](10)
                task <- Timer.repeatWithDelay(Schedule.fixed(1.millis), 0) { state =>
                    channel.put(state).as(state + 1)
                }
                numbers <- Kyo.fill(10)(channel.take)
                _       <- task.interrupt
            yield assert(numbers.toSeq == (0 until 10))
            end for
        }

        "completes when schedule completes" in run {
            for
                channel <- Channel.init[Int](10)
                task    <- Timer.repeatWithDelay(Schedule.fixed(1.millis).maxDuration(10.millis), 0)(st => channel.put(st).andThen(st + 1))
                lastState <- task.get
                numbers   <- channel.drain
            yield assert(lastState == 10 && numbers.toSeq == (0 until 10))
        }
    }

end TimerTest
