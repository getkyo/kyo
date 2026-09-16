package kyo

/** One configured remote, carrying every column `dolt_remotes` reports.
  *
  * `url` is whatever was configured, which for DoltHub is an `owner/database` name rather than anything resembling a URL. `fetchSpecs` and
  * `params` are the refspec list and the driver parameters, both stored as JSON by the server and handed back as their text.
  */
final case class DoltRemote(
    name: String,
    url: String,
    fetchSpecs: Chunk[String],
    params: Maybe[String]
) derives CanEqual
