package io.aeron

import io.aeron.logbuffer.Header // To be stubbed
import org.agrona.DirectBuffer   // To be stubbed

// The constructor signature matches the usage in Topic.scala where a lambda is passed.
// Scala's SAM (Single Abstract Method) conversion should handle this for a Java interface.
// For the stub, we define the constructor parameter explicitly as a function type.
class FragmentAssembler(
    handler: (DirectBuffer, Int, Int, Header) => Unit
) {
    // FragmentAssembler itself doesn't have methods called directly on it in Topic.scala
    // after instantiation. It's passed to Subscription.poll.
    // This simple constructor should suffice for stubbing purposes.
}
