package kyo.internal.reflect.query

/** JVM platform-specific FileSource accessor. */
private[kyo] object PlatformFileSource:
    def get: FileSource = JvmFileSource
end PlatformFileSource
