package kyo.internal

import kyo.*

// Backend implementations wired in the Netty backend commit
private[kyo] object HttpPlatformBackend:
    lazy val client: HttpBackend.Client = ???
    lazy val server: HttpBackend.Server = ???
end HttpPlatformBackend
