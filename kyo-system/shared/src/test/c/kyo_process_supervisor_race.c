#define _GNU_SOURCE
#include <assert.h>
#include <dirent.h>
#include <stdio.h>
#include <string.h>
#include <sys/wait.h>
#include <unistd.h>

/* Build the supervisor with opendir/fopen/main renamed to these hooks, then link this unit without
   those defines. The helper is single-threaded. This forces a real /proc read-after-reap interleaving
   for an unrelated child, with no scheduler timing assumption and no synthetic errno. */
static pid_t fixture = -1;
static int injected = 0;

DIR *kyo_process_test_opendir(const char *path) {
    if (!strcmp(path, "/proc") && fixture < 0) {
        fixture = fork();
        assert(fixture >= 0);
        if (!fixture) _exit(0);
    }
    return opendir(path);
}

FILE *kyo_process_test_fopen(const char *path, const char *mode) {
    FILE *file = fopen(path, mode);
    char expected[64];
    snprintf(expected, sizeof(expected), "/proc/%ld/stat", (long)fixture);
    if (fixture > 0 && file && !strcmp(path, expected)) {
        int status;
        assert(waitpid(fixture, &status, 0) == fixture);
        assert(WIFEXITED(status) && WEXITSTATUS(status) == 0);
        fixture = -1;
        injected++;
    }
    return file;
}

int kyo_process_supervisor_main(int argc, char **argv);
int main(int argc, char **argv) {
    int result = kyo_process_supervisor_main(argc, argv);
    if (fixture > 0) {
        int status;
        assert(waitpid(fixture, &status, 0) == fixture);
    }
    assert(injected > 0);
    return result;
}
