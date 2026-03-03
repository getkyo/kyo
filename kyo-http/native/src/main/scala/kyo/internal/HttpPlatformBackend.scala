package kyo.internal

import kyo.*

// Backend implementations wired in the libcurl/H2O backend commit
private[kyo] object HttpPlatformBackend:
    lazy val client: HttpBackend.Client = ???
    lazy val server: HttpBackend.Server = ???
end HttpPlatformBackend
