package kyo.internal

import kyo.net.NetPlatform
import kyo.net.Transport

/** Shared platform-transport accessor for kyo-http. Replaces the three per-platform HttpPlatformTransport.scala files. The sole
  * kyo.net.* reference in kyo-http lives here, behind the internal/ boundary; kyo-http's own source has no direct kyo.net imports.
  */
private[kyo] object HttpPlatformTransport:
    def transport: Transport = NetPlatform.transport
end HttpPlatformTransport
