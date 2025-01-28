package kyo.internal

object Environment:

    def isDevelopment(): Boolean =
        sys.props.get("kyo.development-mode.enable").map(_.toLowerCase) match
            case Some("true") => true
            case Some(_)      => false
            case _            => sys.props.get("java.class.path").exists(_.contains("org.scala-sbt"))

end Environment
