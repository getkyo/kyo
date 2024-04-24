package kyoTest.scheduler

import kyo.scheduler.Task
import org.scalatest.NonImplicitAssertions
import org.scalatest.freespec.AnyFreeSpec
import scala.collection.mutable.PriorityQueue

class TaskTest extends AnyFreeSpec with NonImplicitAssertions:

    given CanEqual[Task, Task] = CanEqual.derived

    "ordering" - {
        "no repetition" in {
            val t1 = Task((), 1)
            val t2 = Task((), 2)
            val t3 = Task((), 3)
            val t4 = Task((), 4)
            val q  = PriorityQueue(t2, t4, t1, t3)
            assert(q.dequeueAll == Seq(t1, t2, t3, t4))
        }
        "repetition" in {
            val t1 = Task((), 1)
            val t2 = Task((), 2)
            val t3 = Task((), 3)
            val t4 = Task((), 2)
            val t5 = Task((), 4)
            assert(PriorityQueue(t2, t4, t5, t1, t3).dequeueAll == Seq(t1, t2, t4, t3, t5))
        }
        "with preemption" in {
            val t1 = Task((), 1)
            val t2 = Task((), 2)
            val t3 = Task((), 3)
            val t4 = Task((), 4)
            t2.doPreempt()
            t4.doPreempt()
            val q = PriorityQueue(t2, t4, t1, t3)
            assert(q.dequeueAll == Seq(t1, t2, t3, t4))
        }
    }
end TaskTest
