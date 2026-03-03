package kyo.internal

import kyo.*

// Backend implementations wired in the Fetch/Node backend commit
private[kyo] object HttpPlatformBackend:
    lazy val client: HttpBackend.Client = ???
    lazy val server: HttpBackend.Server = ???
end HttpPlatformBackend
