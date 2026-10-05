package kyo.internal

import kyo.*

/** The browser-safe timestamp window is spelled as epoch seconds so that initializing [[DragProtocol]] parses
  * nothing; this pins the numbers to the dates they stand for.
  */
class DragProtocolBoundsTest extends kyo.test.Test[Any]:

    "the timestamp bounds are the first instant of year 1 and the last of year 9999" in {
        assert(Instant.parse("0001-01-01T00:00:00Z") == Result.succeed(DragProtocol.browserTimestampMin))
        assert(Instant.parse("9999-12-31T23:59:59.999999999Z") == Result.succeed(DragProtocol.browserTimestampMax))
    }

end DragProtocolBoundsTest
