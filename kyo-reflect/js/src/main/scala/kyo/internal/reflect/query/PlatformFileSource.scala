package kyo.internal.reflect.query

/** JS platform-specific FileSource accessor. */
private[kyo] object PlatformFileSource:
    def get: FileSource = JsFileSource
end PlatformFileSource
