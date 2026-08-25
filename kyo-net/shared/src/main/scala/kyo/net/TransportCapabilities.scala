package kyo.net

/** What a built [[Transport]] can do, carried as ONE value rather than as a growing set of abstract methods.
  *
  * Each capability used to arrive as its own abstract member on `Transport` that every implementation had to override, and the second one
  * documented itself as following "the same placement rule as" the first, which is what an accreting pattern looks like from the inside. A
  * new capability is now a field here: the transports keep one member to supply, and the accessors callers already use keep working because
  * they read this value.
  *
  * These are ARCHITECTURAL capabilities of the transport implementation, and they are deliberately a different axis from the host-probe
  * outcomes selection produces. Whether the posix transport CAN drive BoringSSL is a property of the transport; whether BoringSSL is staged
  * on this host is a capability probe on the selection side. Collapsing the two would change fail-closed pin semantics, because a pin naming
  * a provider the transport cannot drive at all and a pin naming one the host cannot load are different failures.
  *
  * @param tlsProviders
  *   The TLS provider ids this transport can drive ("boringssl"/"jdk" for the posix transport, "jdk" for the NIO floor, "node" for JS). A
  *   `Set` because it is an unordered membership test and that is the shape every reader uses. Empty for a transport with no TLS support at
  *   all, which then honors no provider id.
  * @param unixSockets
  *   Whether this transport can bind and connect AF_UNIX filesystem paths.
  */
final private[net] case class TransportCapabilities(tlsProviders: Set[String], unixSockets: Boolean) derives CanEqual
