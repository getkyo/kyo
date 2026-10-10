#!/bin/sh
# Prints what decides whether this host's kernel accepts the signal alternate stack Scala Native asks for on musl:
# alignToPageStart(SIGSTKSZ), and musl's SIGSTKSZ is the constant 8192 where glibc's follows AT_MINSIGSTKSZ. The kernel
# refuses a size above MINSIGSTKSZ with ENOMEM only when the frame it would push does not fit, which depends on the
# xstate features the process is permitted (AMX's tile data is the large one) and on the strict_sas_size boot parameter.
#
# Each line starts with "altstack probe:". Three runs of one program: started directly, started by a JVM the way the
# fixtures' binaries are, and a positive control that first asks for AMX tile data permission, which shows whether this
# host refuses an 8 KiB alternate stack to a process that holds it. Never fails: it only reports.
set -u

dir=$(mktemp -d)
trap 'rm -rf "$dir"' EXIT

cat > "$dir/altstack.c" <<'EOF'
#include <errno.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/auxv.h>
#include <sys/syscall.h>
#include <unistd.h>

#define ARCH_GET_XCOMP_PERM 0x1022
#define ARCH_REQ_XCOMP_PERM 0x1023
#define XFEATURE_XTILEDATA 18

int main(int argc, char **argv) {
    const char *mode = argc > 1 ? argv[1] : "direct";
    char request[64] = "not requested";
    char perm[32] = "unavailable";
#ifdef SYS_arch_prctl
    if (strcmp(mode, "amx-permitted") == 0)
        snprintf(request, sizeof request, "%s",
                 syscall(SYS_arch_prctl, ARCH_REQ_XCOMP_PERM, XFEATURE_XTILEDATA) == 0 ? "granted" : strerror(errno));
    unsigned long mask = 0;
    if (syscall(SYS_arch_prctl, ARCH_GET_XCOMP_PERM, &mask) == 0) snprintf(perm, sizeof perm, "0x%lx", mask);
#else
    if (strcmp(mode, "amx-permitted") == 0) snprintf(request, sizeof request, "not an x86_64 host");
#endif
    stack_t s = {0};
    s.ss_size = SIGSTKSZ;
    s.ss_sp = malloc(s.ss_size);
    int r = sigaltstack(&s, NULL);
    printf("%s: SIGSTKSZ=%d AT_MINSIGSTKSZ=%lu amx_request=%s xcomp_perm=%s sigaltstack=%s\n", mode, (int) SIGSTKSZ,
           getauxval(51), request, perm, r ? strerror(errno) : "ok");
    return 0;
}
EOF

cat > "$dir/Spawn.java" <<'EOF'
public class Spawn {
    public static void main(String[] a) throws Exception {
        System.exit(new ProcessBuilder(a).inheritIO().start().waitFor());
    }
}
EOF

{
    grep -m1 -o -w amx_tile /proc/cpuinfo || echo "cpu: no amx_tile"
    echo "kernel: $(uname -r)"
    echo "cmdline: $(cat /proc/cmdline 2>&1)"
    command -v apk > /dev/null && echo "musl: $(apk info -v musl 2>/dev/null | head -n1)"
    if cc -o "$dir/altstack" "$dir/altstack.c"; then
        "$dir/altstack" direct
        java "$dir/Spawn.java" "$dir/altstack" jvm-spawned
        "$dir/altstack" amx-permitted
    fi
} 2>&1 | sed 's/^/altstack probe: /'
exit 0
