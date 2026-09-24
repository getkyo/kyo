package kyo.scheduler

import org.scalatest.NonImplicitAssertions
import org.scalatest.freespec.AsyncFreeSpec
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.scalajs.js

class SchedulerTest extends AsyncFreeSpec with NonImplicitAssertions {

    implicit override def executionContext: ExecutionContext = org.scalajs.macrotaskexecutor.MacrotaskExecutor

    private class ManualClock {
        var now: Long = 1000L
    }

    private def task(body: () => Task.Result): Task = new Task {
        def run(startMillis: Long, clock: InternalClock, deadline: Long): Task.Result = body()
    }

    private def fields(line: String): Map[String, String] =
        line.split(' ').iterator.filter(_.contains('=')).map { kv =>
            val i = kv.indexOf('=')
            kv.substring(0, i) -> kv.substring(i + 1)
        }.toMap

    "status line" - {
        "counts scheduled, run, and preempted slices" in {
            val clock     = new ManualClock
            val scheduler = new Scheduler("unused", 0, () => clock.now)
            val done      = Promise[Unit]()
            var slices    = 0
            scheduler.schedule(task { () =>
                slices += 1
                if (slices < 3) Task.Preempted
                else {
                    done.success(())
                    Task.Done
                }
            })
            done.future.map { _ =>
                val f = fields(scheduler.statusLine())
                assert(f("scheduled") == "3")
                assert(f("ran") == "3")
                assert(f("preempted") == "2")
                assert(f("pending") == "0")
            }
        }

        "reports the longest slice since the previous line" in {
            val clock     = new ManualClock
            val scheduler = new Scheduler("unused", 0, () => clock.now)
            val done      = Promise[Unit]()
            scheduler.schedule(task { () =>
                clock.now += 40
                Task.Done
            })
            scheduler.schedule(task { () =>
                clock.now += 7
                done.success(())
                Task.Done
            })
            done.future.map { _ =>
                val first  = fields(scheduler.statusLine())
                val second = fields(scheduler.statusLine())
                assert(first("maxSliceMs") == "40")
                assert(second("maxSliceMs") == "0")
            }
        }

        "starts with the ts field ci-monitor reads" in {
            val clock     = new ManualClock
            val scheduler = new Scheduler("unused", 0, () => clock.now)
            val line      = scheduler.statusLine()
            assert(line.startsWith("kyo.sched ts=1000 "))
            assert(fields(line)("platform") == "js")
        }
    }

    "status file" - {
        "is written periodically when a path is configured" in {
            val fs                                    = js.Dynamic.global.process.getBuiltinModule("fs")
            val os                                    = js.Dynamic.global.process.getBuiltinModule("os")
            val path                                  = s"${os.tmpdir()}/kyo-sched-js-test-${js.Math.random().toString.drop(2)}.status"
            val scheduler                             = new Scheduler(path, 10, () => 42L)
            def written(attempt: Int): Future[String] =
                if (fs.existsSync(path).asInstanceOf[Boolean]) Future.successful(fs.readFileSync(path, "utf8").asInstanceOf[String])
                else if (attempt >= 500) Future.failed(new AssertionError(s"status file never written: $path"))
                else {
                    val p = Promise[String]()
                    js.timers.setTimeout(10.0)(p.completeWith(written(attempt + 1)))
                    p.future
                }
            written(0).map { content =>
                scheduler.shutdown()
                fs.unlinkSync(path)
                assert(content.startsWith("kyo.sched ts=42 "))
            }
        }

        "no file is written when no path is configured" in {
            val scheduler = new Scheduler("", 10, () => 42L)
            assert(!scheduler.writesStatus)
        }
    }
}
