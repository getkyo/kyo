package kyo

/** A network address for HTTP connections — either TCP (host + port) or a Unix domain socket path.
  *
  * Used by both client (as the connection pool key and transport target) and server (as the bind address exposed by
  * [[kyo.HttpBackend.Binding.address]]).
  */
enum HttpAddress derives CanEqual:
    case Tcp(host: String, port: Int)
    case Unix(path: String)
end HttpAddress
