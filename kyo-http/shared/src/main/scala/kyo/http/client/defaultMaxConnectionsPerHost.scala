package kyo.http.client

import kyo.*

/** Maximum idle keep-alive connections per host for the process-lifetime default [[kyo.HttpClient]] (backing the ambient `HttpClient.get*` /
  * `post*` helpers): the cap on connections its pool retains per host. The default client is the one client whose limits cannot be set through
  * `HttpClient.init`, so this flag is its only knob. Configurable via `-Dkyo.http.client.defaultMaxConnectionsPerHost` (an Int, default 100);
  * only the default client reads it.
  */
private[kyo] object defaultMaxConnectionsPerHost extends StaticFlag[Int](100)
