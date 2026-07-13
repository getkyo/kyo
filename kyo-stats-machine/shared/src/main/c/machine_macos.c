// macOS projection shim: flattens nested/array syscall structs into flat primitive out-params the FFI
// struct layer can read. Compiled by the kyo-ffi plugin (library id machine_macos). Host metrics only:
// no file is opened here; proc-style files are read in Scala via kyo.Path.
//
// The whole shim is guarded on __APPLE__: the kyo-ffi plugin compiles every declared C source on the
// BUILD HOST for JVM/JS, and Scala Native compiles it into the binary on every OS, so on a non-macOS
// host (a Linux build host) the mach/sysctl headers do not exist. The #else branch provides same-signature
// stubs returning failure codes, so the file compiles on every host and every symbol resolves; the
// binding methods then return non-zero / 0 count / "" off macOS, which MachineMacos already maps to Absent.
#include <stdint.h>

#if defined(__APPLE__)

#include <string.h>
#include <stdlib.h>
#include <unistd.h>
#include <sys/sysctl.h>
#include <sys/mount.h>
#include <sys/param.h>
#include <mach/mach.h>
#include <mach/mach_host.h>

// The mach host port, acquired once and reused. mach_host_self() returns a port user-reference on every
// call, so calling it per tick leaks urefs for process lifetime (libtop/psutil acquire it once). The host
// port is a process-lifetime singleton, so a one-time cache is correct and leak-free.
static host_t g_host = 0;

static host_t machine_macos_host(void) {
    if (g_host == 0) g_host = mach_host_self();
    return g_host;
}

// host_cpu_load_info projected to [user, system, idle, nice] in nanoseconds.
int machine_macos_host_cpu_load(int64_t* out) {
    host_cpu_load_info_data_t info;
    mach_msg_type_number_t count = HOST_CPU_LOAD_INFO_COUNT;
    if (host_statistics(machine_macos_host(), HOST_CPU_LOAD_INFO, (host_info_t)&info, &count) != KERN_SUCCESS)
        return 1;
    long hz = sysconf(_SC_CLK_TCK);
    if (hz <= 0) hz = 100;
    int64_t scale = 1000000000LL / hz;
    out[0] = (int64_t)info.cpu_ticks[CPU_STATE_USER] * scale;
    out[1] = (int64_t)info.cpu_ticks[CPU_STATE_SYSTEM] * scale;
    out[2] = (int64_t)info.cpu_ticks[CPU_STATE_IDLE] * scale;
    out[3] = (int64_t)info.cpu_ticks[CPU_STATE_NICE] * scale;
    return 0;
}

// vm_statistics64 + sysctl(hw.memsize) projected to [total, free, available] bytes.
int machine_macos_vm_statistics(int64_t* out) {
    int64_t memsize = 0;
    size_t len = sizeof(memsize);
    if (sysctlbyname("hw.memsize", &memsize, &len, NULL, 0) != 0) return 1;
    vm_size_t page = 0;
    if (host_page_size(machine_macos_host(), &page) != KERN_SUCCESS || page == 0) return 1;
    vm_statistics64_data_t vm;
    mach_msg_type_number_t count = HOST_VM_INFO64_COUNT;
    if (host_statistics64(machine_macos_host(), HOST_VM_INFO64, (host_info64_t)&vm, &count) != KERN_SUCCESS)
        return 1;
    out[0] = memsize;
    out[1] = (int64_t)vm.free_count * (int64_t)page;
    out[2] = (int64_t)(vm.free_count + vm.inactive_count) * (int64_t)page;
    return 0;
}

// xsw_usage via sysctl(vm.swapusage) projected to [total, free] bytes.
int machine_macos_swap_usage(int64_t* out) {
    struct xsw_usage sw;
    size_t len = sizeof(sw);
    if (sysctlbyname("vm.swapusage", &sw, &len, NULL, 0) != 0) return 1;
    out[0] = (int64_t)sw.xsu_total;
    out[1] = (int64_t)sw.xsu_avail;
    return 0;
}

// getloadavg into [one, five, fifteen]; returns the number of samples written (3 on ok). Wrapped so the
// binding resolves machine_macos_getloadavg (symbolPrefix) rather than binding bare libc getloadavg.
int machine_macos_getloadavg(double* out, int n) {
    return getloadavg(out, n);
}

// Enumerate the mounted filesystems into the CALLER's buffer as NUL-separated
// <mntonname>\0<fstypename>\0 pairs, and return the mount count (or -1 when the buffer is too small).
//
// getfsstat into a locally-owned array is re-entrant. getmntinfo is not: it returns a pointer into
// libc-owned static memory that every caller in the process shares, so a second call from any thread
// overwrites the view another caller is still reading, and the caller never owns the buffer. Filling a
// buffer the caller owns removes both hazards and keeps the enumeration coherent for a mount table that
// changes between calls. The struct array this function fills lives inside the call and is freed before
// it returns, so it is a C-heap allocation only, never a managed-heap one.
int machine_macos_mounts(char* out, int32_t cap) {
    int count = getfsstat(NULL, 0, MNT_NOWAIT);
    if (count <= 0) return count;
    size_t bytes = (size_t)count * sizeof(struct statfs);
    struct statfs* mnt = (struct statfs*)malloc(bytes);
    if (mnt == NULL) return -1;
    count = getfsstat(mnt, (int)bytes, MNT_NOWAIT);
    if (count <= 0) {
        free(mnt);
        return count;
    }
    int32_t at = 0;
    for (int i = 0; i < count; i++) {
        size_t plen = strlen(mnt[i].f_mntonname);
        size_t flen = strlen(mnt[i].f_fstypename);
        if (at + (int32_t)plen + (int32_t)flen + 2 > cap) {
            free(mnt);
            return -1;
        }
        memcpy(out + at, mnt[i].f_mntonname, plen + 1);
        at += (int32_t)plen + 1;
        memcpy(out + at, mnt[i].f_fstypename, flen + 1);
        at += (int32_t)flen + 1;
    }
    free(mnt);
    return count;
}

// statfs(path) projected to [total, free] bytes.
int machine_macos_statfs(const char* path, int64_t* out) {
    struct statfs st;
    if (statfs(path, &st) != 0) return 1;
    out[0] = (int64_t)st.f_blocks * (int64_t)st.f_bsize;
    out[1] = (int64_t)st.f_bavail * (int64_t)st.f_bsize;
    return 0;
}

#else

// Non-macOS host: same-signature stubs so the file compiles and every symbol resolves. Each returns a
// failure code (a non-zero int, or a 0 mount/sample count), which MachineMacos maps to Absent. MachineMacos
// is only ever selected when System.operatingSystem is MacOS, so these stubs never run at runtime.
int machine_macos_host_cpu_load(int64_t* out) { (void)out; return 1; }
int machine_macos_vm_statistics(int64_t* out) { (void)out; return 1; }
int machine_macos_swap_usage(int64_t* out) { (void)out; return 1; }
int machine_macos_getloadavg(double* out, int n) { (void)out; (void)n; return 0; }
int machine_macos_mounts(char* out, int32_t cap) { (void)out; (void)cap; return 0; }
int machine_macos_statfs(const char* path, int64_t* out) { (void)path; (void)out; return 1; }

#endif

