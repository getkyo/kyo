package kyo.internal.reflect.query

/** Scala Native platform-specific FileSource accessor. */
private[kyo] object PlatformFileSource:
    def get: FileSource = NativeFileSource
end PlatformFileSource
