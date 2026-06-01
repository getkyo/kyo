package kyo.website

/** One rendered documentation version: the git tag it came from, the label shown in the version
  * dropdown, and whether this version is served as `latest`.
  */
final case class WebsiteVersion(tag: String, label: String, latest: Boolean) derives CanEqual
