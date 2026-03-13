package kyo.interop.flow

import java.util.concurrent.Flow.*
import kyo.*
import kyo.interop.flow.StreamSubscriber.EmitStrategy

final class NullGuardTest extends Test:

    private val noopSubscription = new Subscription:
        override def request(n: Long): Unit = ()
        override def cancel(): Unit         = ()

    "StreamSubscriber" - {
        "onSubscribe(null) should throw NullPointerException" in run {
            StreamSubscriber[String](16).map { subscriber =>
                assertThrows[NullPointerException](subscriber.onSubscribe(null))
            }
        }

        "onNext(null) should throw NullPointerException" in run {
            StreamSubscriber[String](16).map { subscriber =>
                subscriber.onSubscribe(noopSubscription)
                assertThrows[NullPointerException](subscriber.onNext(null))
            }
        }

        "onError(null) should throw NullPointerException" in run {
            StreamSubscriber[String](16).map { subscriber =>
                subscriber.onSubscribe(noopSubscription)
                assertThrows[NullPointerException](subscriber.onError(null))
            }
        }
    }

    "StreamPublisher.subscribe(null) should throw NullPointerException" in run {
        Stream.range(0, 10, 1).toPublisher.map { publisher =>
            assertThrows[NullPointerException](publisher.subscribe(null))
        }
    }

end NullGuardTest
