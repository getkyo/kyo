package kyo

/** Connection pool key that uniquely identifies a database endpoint.
  *
  * Two addresses are equal when all five fields match, so the pool can reuse connections within a single backend and keep separate pools
  * for different databases, users, or hosts.
  *
  * @param driver
  *   lowercase driver identifier: "postgres" or "mysql"
  * @param host
  *   hostname or IP address
  * @param port
  *   TCP port number
  * @param db
  *   database name
  * @param user
  *   authentication user name
  */
final case class SqlAddress(
    driver: String,
    host: String,
    port: Int,
    db: String,
    user: String
) derives CanEqual

object SqlAddress:
    given Render[SqlAddress] = Render.from { a =>
        s"${a.driver}://${a.user}@${a.host}:${a.port}/${a.db}"
    }
end SqlAddress
