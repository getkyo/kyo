package kyo.internal

/** Environment-variable resolution for [[kyo.Flag]], [[kyo.Rollout]] and [[kyo.DynamicFlag]] on the JVM and
  * Scala Native.
  *
  * Both platforms populate the real process environment through `java.lang.System.getenv`, so the resolver
  * delegates to it directly. A missing variable yields `null`, the value [[kyo.Flag]]'s resolution branch
  * tests with `ne null`.
  */
private[kyo] object FlagPlatform {
    def env(name: String): String = java.lang.System.getenv(name)

    /** Every environment-variable name the process can see, for [[kyo.Flag]]'s near-miss scan. */
    def envNames(): Seq[String] =
        scala.jdk.CollectionConverters.SetHasAsScala(java.lang.System.getenv().keySet()).asScala.toSeq
}
