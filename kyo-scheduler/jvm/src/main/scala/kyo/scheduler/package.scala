package kyo

package object scheduler:
    private[scheduler] def statsScope(path: String*) =
        "kyo" :: "scheduler" :: path.toList
