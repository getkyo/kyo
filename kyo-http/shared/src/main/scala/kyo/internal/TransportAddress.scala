package kyo.internal

/** Represents a transport-level address for connection establishment.
  *
  * Used by Transport.connect to specify whether to connect via TCP or Unix domain socket. Also serves as the connection pool key, replacing
  * the former HostKey.
  */
enum TransportAddress derives CanEqual:
    case Tcp(host: String, port: Int)
    case Unix(path: String)
end TransportAddress
