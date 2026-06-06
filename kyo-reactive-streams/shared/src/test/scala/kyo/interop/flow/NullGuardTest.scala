package kyo.interop.flow

import java.util.concurrent.Flow.*
import kyo.*
import kyo.interop.flow.StreamSubscriber.EmitStrategy

final class NullGuardTest extends kyo.test.Test[Any]:

    private val noopSubscription = new Subscription:
        override def request(n: Long): Unit = ()
        override def cancel(): Unit         = ()

    "StreamSubscriber" - {
        "onSubscribe(null) should throw NullPointerException" in {
            StreamSubscriber[String](16).map { subscriber =>
                interceptThrown[NullPointerException](subscriber.onSubscribe(null))
            }
        }

        "onNext(null) should throw NullPointerException" in {
            StreamSubscriber[String](16).map { subscriber =>
                subscriber.onSubscribe(noopSubscription)
                interceptThrown[NullPointerException](subscriber.onNext(null))
            }
        }

        "onError(null) should throw NullPointerException" in {
            StreamSubscriber[String](16).map { subscriber =>
                subscriber.onSubscribe(noopSubscription)
                interceptThrown[NullPointerException](subscriber.onError(null))
            }
        }
    }

    "StreamPublisher.subscribe(null) should throw NullPointerException" in {
        Stream.range(0, 10, 1).toPublisher.map { publisher =>
            interceptThrown[NullPointerException](publisher.subscribe(null))
        }
    }

end NullGuardTest
