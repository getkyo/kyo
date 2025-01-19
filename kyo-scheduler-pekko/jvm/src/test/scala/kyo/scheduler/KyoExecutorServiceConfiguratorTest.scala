package kyo.scheduler

import com.typesafe.config.ConfigFactory
import java.util.concurrent.CountDownLatch
import org.apache.pekko.actor.Actor
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.Props
import org.apache.pekko.testkit.TestKit
import org.apache.pekko.testkit.TestProbe
import org.scalatest.BeforeAndAfterAll
import org.scalatest.NonImplicitAssertions
import org.scalatest.freespec.AnyFreeSpecLike
import scala.concurrent.Promise
import scala.concurrent.duration.*

class KyoExecutorServiceConfiguratorTest
    extends TestKit(ActorSystem(
        "KyoTest",
        ConfigFactory.parseString("""
            pekko.actor.default-dispatcher {
                type = "Dispatcher"
                executor = "kyo.scheduler.KyoExecutorServiceConfigurator"
            }
        """)
    ))
    with AnyFreeSpecLike
    with NonImplicitAssertions
    with BeforeAndAfterAll {

    override def afterAll(): Unit = {
        TestKit.shutdownActorSystem(system)
    }

    val dispatcher = system.dispatchers.defaultGlobalDispatcher

    case class TestException() extends Exception

    "executes tasks on kyo threads" in {
        val thread                 = Thread.currentThread()
        val latch                  = new CountDownLatch(1)
        var executorThread: Thread = null

        dispatcher.execute { () =>
            executorThread = Thread.currentThread()
            latch.countDown()
        }

        latch.await(5, SECONDS)
        assert(thread ne executorThread)
        assert(executorThread.getName().contains("kyo"))
    }

    "handles messages through actors" in {
        val latch                  = new CountDownLatch(1)
        var executorThread: Thread = null

        val probe = TestProbe()
        val actor = system.actorOf(Props(new Actor {
            def receive = { _ =>
                executorThread = Thread.currentThread()
                latch.countDown()
                probe.ref ! "done"
            }
        }))

        actor ! "test"
        probe.expectMsg("done")

        assert(latch.await(5, SECONDS))
        assert(Thread.currentThread() ne executorThread)
        assert(executorThread.getName().contains("kyo"))
    }

    "handles multiple actors" in {
        val actorCount   = 3
        val messageCount = 10
        val latch        = new CountDownLatch(actorCount * messageCount)
        val threadNames  = scala.collection.mutable.Set[String]()

        val probe = TestProbe()
        val actors = (1 to actorCount).map { i =>
            system.actorOf(Props(new Actor {
                def receive = {
                    case msg =>
                        threadNames.synchronized {
                            threadNames += Thread.currentThread().getName
                        }
                        latch.countDown()
                        probe.ref ! s"done-$i"
                }
            }))
        }

        for {
            actor <- actors
            _     <- 1 to messageCount
        } actor ! "test"

        probe.receiveN(actorCount * messageCount)
        assert(latch.await(5, SECONDS))
        assert(threadNames.size > actorCount)
        assert(threadNames.forall(_.contains("kyo")))
    }
}
