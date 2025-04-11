package kyo.scheduler

import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.Actor
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.Props
import org.apache.pekko.pattern.ask
import org.apache.pekko.testkit.TestKit
import org.apache.pekko.util.Timeout
import org.scalatest.BeforeAndAfterAll
import org.scalatest.NonImplicitAssertions
import org.scalatest.freespec.AnyFreeSpecLike
import scala.concurrent.Await
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
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

    implicit def timeout: Timeout          = Timeout(5.seconds)
    implicit def execCtx: ExecutionContext = Scheduler.get.asExecutionContext

    override def afterAll(): Unit = {
        TestKit.shutdownActorSystem(system)
    }

    "executes tasks on kyo threads" in {
        val actor = system.actorOf(Props(new Actor {
            def receive = {
                case msg => sender() ! Thread.currentThread().getName
            }
        }))

        val threadName = Await.result((actor ? "test").mapTo[String], 5.seconds)
        assert(threadName.contains("kyo"))
    }

    "handles concurrent messages" in {
        val actor = system.actorOf(Props(new Actor {
            def receive = {
                case msg => sender() ! Thread.currentThread().getName
            }
        }))

        val futures     = (1 to 1000).map(_ => (actor ? "test").mapTo[String])
        val threadNames = Await.result(Future.sequence(futures), 5.seconds)

        assert(threadNames.forall(_.contains("kyo")))
        assert(threadNames.toSet.size >= 1)
    }

    "handles multiple actors" in {
        val actors =
            (1 to 10).map { i =>
                system.actorOf(Props(new Actor {
                    def receive = {
                        case msg => sender() ! Thread.currentThread().getName
                    }
                }))
            }

        val futures =
            for {
                actor <- actors
                _     <- 1 to 10
            } yield (actor ? "test").mapTo[String]

        val threadNames = Await.result(Future.sequence(futures), 5.seconds)
        assert(threadNames.forall(_.contains("kyo")))
        assert(threadNames.toSet.size > 1)
    }
}
