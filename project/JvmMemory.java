import java.lang.management.ManagementFactory;

/** Every heap the build sets, derived from the machine's memory: each sbt driver's, and each forked JVM's and Node test
  * process's beside it.
  *
  * A quarter of memory is left to the OS and to every JVM's off-heap; the rest is the budget. A driver and the forked
  * JVMs that can run beside it split the budget in proportion to their measured needs, so together they never pass it.
  * The needs are weights and floors, never a heap: they decide how many forks fit and each one's fraction, and the
  * self-tests check every heap against them at the runner sizes they were measured on.
  *
  * Java, not Scala, so that scripts/sbt-heap-lib.sh runs this same file with `java project/JvmMemory.java`, which needs
  * no build, while the sbt meta-build compiles it for build.sbt: one derivation for the scripts, CI and a plain `sbt`.
  */
public final class JvmMemory {

    // Measured needs, each the most the JVM was seen to hold. A driver's is its live G1 heap; a fork's is its RSS,
    // except the test fork's, which fills its heap.
    // The test-jvm driver over the CI JVM row, podman-ci 16 GiB.
    static final long TEST_DRIVER_NEED_MB = 4716;
    // kyo-tasty's classpath suites fail in a 4096 MB fork and fill a 5120 MB one (5673 MB RSS, ubuntu-latest).
    static final long TEST_FORK_NEED_MB = 5120;
    // The docs driver over `doctest`, ubuntu-24.04-arm; `kyoJVM/doc` holds 4532 MB.
    static final long DOCS_DRIVER_NEED_MB = 8708;
    // A doctest fork, ubuntu-24.04-arm; a scaladoc fork holds 1177 MB.
    static final long DOCS_FORK_NEED_MB = 1744;
    // The run driver linking kyoUiJS tests, ubuntu-24.04-arm (Wasm 5796 MB on ubuntu-latest; the JS test phase 5602 MB).
    static final long RUN_DRIVER_NEED_MB = 5932;
    // The Node processes of one JS test task, windows-x64 (2182 MB on linux-x64; a Wasm task's peak at 1865 MB).
    static final long NODE_NEED_MB = 2209;

    // Concurrency caps, not heaps. kyo-pod splits a suite into a podman fork and a docker fork, and more test forks
    // contend on the container daemons. A doctest fork takes two cores (-XX:ActiveProcessorCount=2).
    static final int TEST_FORK_CAP = 2;
    static final int DOCTEST_FORK_CAP = 2;
    static final int SCALADOC_FORK_CAP = 1;

    private JvmMemory() {}

    /** The memory this machine gives its processes, in MB: SBT_HEAP_MEMORY_MB when set, else what the JVM sees (the
      * cgroup limit in a container, else physical memory).
      */
    public static long memoryMb() {
        String forced = System.getenv("SBT_HEAP_MEMORY_MB");
        if (forced != null && !forced.isBlank()) return Long.parseLong(forced.trim());
        return ((com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean()).getTotalMemorySize() >> 20;
    }

    /** SBT_TASK_LIMIT, which build.sbt applies as limitAll and so also bounds the forks; 0 when unset. */
    public static int taskLimit() {
        String limit = System.getenv("SBT_TASK_LIMIT");
        return limit == null || limit.isBlank() ? 0 : Integer.parseInt(limit.trim());
    }

    public static long budgetMb(long memory) { return memory - memory / 4; }

    /** How many test tasks run at once: half the cores on CI, where SBT_TASK_LIMIT bounds them further, and most of
      * them locally. Each JS or Wasm one is a Node process.
      */
    public static int testTaskCap() {
        int cores = Runtime.getRuntime().availableProcessors();
        return Math.max(1, System.getenv("CI") != null ? cores / 2 : (int) Math.ceil(cores * 0.8));
    }

    private static long driverNeed(String kind) {
        switch (kind) {
            case "test": return TEST_DRIVER_NEED_MB;
            case "docs": return DOCS_DRIVER_NEED_MB;
            case "node": return RUN_DRIVER_NEED_MB;
            default: throw new IllegalArgumentException("unknown fork kind '" + kind + "'");
        }
    }

    private static long forkNeed(String kind) {
        switch (kind) {
            case "test": return TEST_FORK_NEED_MB;
            case "docs": return DOCS_FORK_NEED_MB;
            default: return NODE_NEED_MB;
        }
    }

    private static int forkCap(String kind) {
        switch (kind) {
            case "test": return TEST_FORK_CAP;
            case "docs": return DOCTEST_FORK_CAP + SCALADOC_FORK_CAP;
            default: return testTaskCap();
        }
    }

    /** How many processes of `kind` run beside the driver ("test" and "docs" forked JVMs, "node" Node test processes): the
      * most the build's concurrency allows whose needs fit the budget beside the driver's, and at least one.
      */
    public static int forks(String kind, long memory, int taskLimit) {
        long driver = driverNeed(kind);
        int cap = forkCap(kind);
        if (taskLimit > 0) cap = Math.min(cap, taskLimit);
        int n = 1;
        while (n < cap && driver + (n + 1) * forkNeed(kind) <= budgetMb(memory)) n++;
        return n;
    }

    /** The budget split between a driver and its forks in proportion to their measured needs, so each gets its need on
      * a machine where they fit and the same fraction of more on a larger one.
      */
    private static long share(String kind, long need, long memory, int taskLimit) {
        long whole = driverNeed(kind) + forks(kind, memory, taskLimit) * forkNeed(kind);
        return budgetMb(memory) * need / whole;
    }

    public static long forkHeapMb(String kind, long memory, int taskLimit) {
        return share(kind, forkNeed(kind), memory, taskLimit);
    }

    /** The driver heap of an sbt role. Roles that run nothing JVM beside the driver get the whole budget; `run` shares it
      * with its Node test processes, test-jvm and docs with their forks. A Native test binary is not a heap the build
      * sizes, so `run` budgets Node's share beside it on every platform.
      */
    public static long driverHeapMb(String role, long memory, int taskLimit) {
        switch (role) {
            case "compile":
            case "classnames":
            case "link":
            case "publish":
            case "tool":
                return budgetMb(memory);
            case "run":
                return share("node", RUN_DRIVER_NEED_MB, memory, taskLimit);
            case "test-jvm":
                return share("test", TEST_DRIVER_NEED_MB, memory, taskLimit);
            case "docs":
                return share("docs", DOCS_DRIVER_NEED_MB, memory, taskLimit);
            default:
                throw new IllegalArgumentException("unknown role '" + role + "'");
        }
    }

    public static int testForks() { return forks("test", memoryMb(), taskLimit()); }
    public static long testForkHeapMb() { return forkHeapMb("test", memoryMb(), taskLimit()); }
    public static int docsForks() { return forks("docs", memoryMb(), taskLimit()); }
    public static long docsForkHeapMb() { return forkHeapMb("docs", memoryMb(), taskLimit()); }
    public static int nodeProcesses() { return forks("node", memoryMb(), taskLimit()); }
    public static long nodeHeapMb() { return forkHeapMb("node", memoryMb(), taskLimit()); }

    /** `memory`, `driver <role>`, `forks <kind>` or `fork-heap <kind>`, printed in MB or as a count. */
    public static void main(String[] args) {
        long memory = memoryMb();
        int limit = taskLimit();
        try {
            switch (args.length == 0 ? "" : args[0]) {
                case "memory":
                    System.out.println(memory);
                    break;
                case "driver":
                    System.out.println(driverHeapMb(args[1], memory, limit));
                    break;
                case "forks":
                    System.out.println(forks(args[1], memory, limit));
                    break;
                case "fork-heap":
                    System.out.println(forkHeapMb(args[1], memory, limit));
                    break;
                default:
                    System.err.println("usage: java project/JvmMemory.java memory | driver <role> | forks <kind> | fork-heap <kind>");
                    System.exit(2);
            }
        } catch (IllegalArgumentException | ArrayIndexOutOfBoundsException e) {
            System.err.println("JvmMemory: " + e.getMessage());
            System.exit(2);
        }
    }
}
