package org.scalamock.scalatest

import org.scalamock.handlers.{Handlers, OrderedHandlers, UnorderedHandlers}
import org.scalatest.AsyncTestSuite

// Workaround for https://github.com/ScalaMock/ScalaMock/issues/627
trait AsyncMockFactory2 extends AsyncMockFactory { this: AsyncTestSuite =>

    private def inContext[T](context: Handlers)(what: => T): T = {
        currentExpectationContext.add(context)
        val prevContext = currentExpectationContext
        currentExpectationContext = context
        val r = what
        currentExpectationContext = prevContext
        r
    }

    protected def inAnyOrder[T](what: => T): T = {
        inContext(new UnorderedHandlers)(what)
    }

    protected def inSequence[T](what: => T): T = {
        inContext(new OrderedHandlers)(what)
    }

    protected def inAnyOrderWithLogging[T](what: => T) =
        inContext(new UnorderedHandlers(logging = true))(what)

    protected def inSequenceWithLogging[T](what: => T) =
        inContext(new OrderedHandlers(logging = true))(what)
}