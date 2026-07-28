package kyo.net

/** A network address: either a TCP `(host, port)` pair or a Unix domain socket path.
  *
  * Used by both client and server:
  *   - Client: identifies the connection pool key and transport target for each unique remote host.
  *   - Server: returned by listeners to report the address the server actually bound to.
  *
  * Unix socket addresses are selected by setting `unixSocket` on config or using `http+unix://` scheme URLs on the client.
  */
enum NetAddress derives CanEqual:
    case Tcp(host: String, port: Int)
    case Unix(path: String)
end NetAddress
