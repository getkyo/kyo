package kyo.http.client

import kyo.*

/** Idle-connection timeout for the process-lifetime default [[kyo.HttpClient]] (backing the ambient `HttpClient.get*` / `post*` helpers):
  * its pool's background reaper closes a keep-alive connection after this much inactivity, and since the default client is never closed, the pool
  * relies entirely on it. Configurable via `-Dkyo.http.client.defaultIdleTimeout` (a Duration, default 60s); only the default client reads it.
  */
private[kyo] object defaultIdleTimeout extends StaticFlag[Duration](60.seconds)
