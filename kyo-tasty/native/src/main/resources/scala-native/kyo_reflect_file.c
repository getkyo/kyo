/**
 * File I/O helpers for kyo-reflect Scala Native.
 *
 * Provides platform-agnostic access to dirent struct fields and
 * open(2) flag constants that vary by platform.
 */
#define _GNU_SOURCE
#include <dirent.h>
#include <fcntl.h>
#include <stddef.h>

/**
 * Extract the d_name pointer from a dirent struct.
 *
 * This avoids hardcoding the offset of d_name in the dirent struct,
 * which differs between Linux and macOS/BSD.
 *
 * @param entry pointer to a dirent struct
 * @return pointer to the d_name field (null-terminated string)
 */
const char* kyo_reflect_dirent_name(struct dirent* entry) {
    return entry->d_name;
}

/** O_WRONLY flag, platform-specific. */
int kyo_reflect_O_WRONLY(void) { return O_WRONLY; }

/** O_CREAT flag, platform-specific. */
int kyo_reflect_O_CREAT(void) { return O_CREAT; }

/** O_TRUNC flag, platform-specific. */
int kyo_reflect_O_TRUNC(void) { return O_TRUNC; }
