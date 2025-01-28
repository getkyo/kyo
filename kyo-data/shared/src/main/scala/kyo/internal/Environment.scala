package kyo.internal

/** Environment detection utility.
  *
  * Provides functionality to detect whether the JVM is running in a development environment. This information is used to control the
  * verbosity of error messages and debugging information in Kyo's implementations.
  *
  * The development mode can be controlled in two ways:
  *   1. Explicitly via the system property "-Dkyo.development=true"
  *   2. Automatically by detecting SBT in the classpath
  * }}}
  */
private[kyo] object Environment:

    /** Determines if the execution is a development environment.
      *
      * This method checks the following conditions in order:
      *
      *   1. If system property "kyo.development" exists:
      *      - Returns true if the property value is "true" (case insensitive)
      *      - Returns false if the property value is anything else
      *   2. If the property doesn't exist:
      *      - Returns true if SBT is detected in the classpath (contains "org.scala-sbt")
      *      - Returns false otherwise
      *
      * @return
      *   true if running in a development environment, false otherwise
      */
    val isDevelopment: Boolean = inferIsDevelopment()

    private[internal] def inferIsDevelopment(): Boolean =
        sys.props.get("kyo.development").map(_.toLowerCase) match
            case Some("true") => true
            case Some(_)      => false
            case None =>
                sys.props.get("java.class.path").exists(_.contains("org.scala-sbt"))

end Environment
