# Prior Art: Slurm and Kubernetes as Declarative-Requirements Schedulers

## 1. What it is

**Slurm** (Simple Linux Utility for Resource Management) is the dominant HPC batch scheduler:
users submit jobs (shell scripts wrapping MPI/multi-node compute) to a central controller
(`slurmctld`), which queues them and dispatches to compute nodes (`slurmd`) once resources
are free. Optimized for long-running, resource-exclusive, tightly-coupled parallel jobs.

**Kubernetes** is a container orchestrator: users submit pod specs (long-running services,
by default) to the API server; `kube-scheduler` binds each pod to a node; `kubelet` on that
node runs it. Optimized for many small, independent, restartable units with soft resource
sharing as the default and hard exclusivity as an opt-in.

Both separate **declaring what a unit of work needs** from **deciding where it runs**: that
is the architecture we want to borrow for `Workers`.

## 2. Unit of work

- **Slurm job**: `sbatch script.sh` submits a batch script requesting an allocation
  (`--nodes`, `--ntasks`, `--cpus-per-task`) plus a time limit. A job runs to completion or
  the wall-clock limit under `--time`.
- **K8s pod**: the smallest deployable unit, one or more co-located containers, submitted
  declaratively as a YAML `Pod` (usually wrapped by a `Job`, `Deployment`, etc.). Pods are
  ephemeral by design; controllers re-create them on failure per `restartPolicy`.

## 3. Grouping / queues

- **Slurm partitions** are named node groups, each a de facto queue with its own access
  policy and limits:
  ```
  PartitionName=pdebug Nodes=mcr[0-191] MaxTime=30 MaxNodes=32 Default=YES
  PartitionName=pbatch Nodes=mcr[192-1151]
  ```
  Docs: "Nodes can be in more than one partition and each partition can have different
  constraints (permitted users, time limits, job size limits, etc.). Each partition can
  thus be considered a separate queue." A job selects one with `-p, --partition=<name>`.
- **K8s** has no named-queue primitive on the scheduling path itself; grouping is via
  **namespaces** (a policy/quota boundary, see ResourceQuota below) and **node labels**
  matched by `nodeSelector`/affinity. The closer analogue to a Slurm partition is a
  labeled node pool selected via affinity, not a queue object.

## 4. Declarative config / requirements model (the core of this research)

This is the vocabulary both systems use to let a task declare what it needs, leaving the
scheduler to admit/place it. It is the direct precedent for `Task`'s `locks`, `weight`,
`isolation`, `timeout`.

### Requests vs. limits: the key K8s distinction

Two numbers, two consumers, two enforcement mechanisms:

> "When you specify the resource *request* for containers in a Pod, the kube-scheduler
> uses this information to decide which node to place the Pod on. When you specify a
> resource *limit* for a container, the kubelet enforces those limits so that the running
> container is not allowed to use more of that resource than the limit you set."

```yaml
resources:
  requests:            # scheduler-side: admission / placement arithmetic
    memory: "256Mi"
    cpu: "250m"
  limits:               # kubelet-side: runtime enforcement
    memory: "512Mi"
    cpu: "500m"
```

CPU limit is soft-enforced (throttling); memory limit is hard (OOM kill). `requests` is
exactly the number a scheduler subtracts from a capacity pool at admission time: this is
the natural model for `weight` (permits consumed against the global cap), independent of
any runtime enforcement question.

### Exclusive use

Slurm: `--exclusive[={user|mcs|topo}]`, "The job allocation can not share nodes ... with
other running jobs." A binary opt-out of co-location, scoped to the whole allocation, not a
named resource. This is the direct precedent for a task-level `.exclusive` flag: "acquire
the entire capacity pool, not just my `weight`."

### Resource shape: `--cpus-per-task`, `--mem`, `--gres`

```
-c, --cpus-per-task=<ncpus>     # processors per task
--mem=<size>[units]             # real memory per node (K|M|G|T)
--gres=<list>                   # generic consumable resources: name[[:type]:count]
```
`--gres` (e.g. `--gres=gpu:2`) generalizes past CPU/mem to arbitrary named countable
resources: the pattern to borrow if `weight` ever needs to become a *vector* of named
permits (cpu-slots, gpu-slots, license-seats) rather than one scalar.

### Node/queue affinity vs. anti-affinity

K8s node placement, from hard constraint to node grouping:
```yaml
nodeSelector:
  topology.kubernetes.io/zone: antarctica-east1
```
```yaml
affinity:
  nodeAffinity:
    requiredDuringSchedulingIgnoredDuringExecution:      # hard
      nodeSelectorTerms:
      - matchExpressions:
        - {key: topology.kubernetes.io/zone, operator: In, values: [antarctica-east1]}
    preferredDuringSchedulingIgnoredDuringExecution:      # soft, weighted 1-100
    - weight: 1
      preference:
        matchExpressions:
        - {key: another-node-label-key, operator: In, values: [another-node-label-value]}
```
**Anti-affinity** is the same shape inverted, applied pod-to-pod with a `topologyKey`
defining the granularity of "together":
```yaml
affinity:
  podAntiAffinity:
    requiredDuringSchedulingIgnoredDuringExecution:
    - labelSelector:
        matchExpressions:
        - {key: app, operator: In, values: [cache]}
      topologyKey: kubernetes.io/hostname
```
This required/preferred split (hard vs. scored-soft) is a cleaner requirements vocabulary
than Slurm has for this axis: Slurm's placement constraints (`--nodelist`, `--exclude`,
features via `--constraint`) are hard-only, with no soft-preference scoring.

### Taints/tolerations: the inverse of affinity

`kubectl taint nodes node1 key1=value1:NoSchedule` marks a node as repelling pods by
default; a pod must carry a matching `tolerations` entry to be admitted there. Effects:
`NoSchedule` (hard, doesn't evict running pods), `PreferNoSchedule` (soft), `NoExecute`
(evicts non-tolerating pods immediately, or after `tolerationSeconds`). This is affinity's
dual: the *pool* declares an exclusion, the *task* opts in, worth naming distinctly from
task-side affinity since the declaration direction differs.

### Priority and preemption

K8s `PriorityClass`:
```yaml
apiVersion: scheduling.k8s.io/v1
kind: PriorityClass
metadata: {name: high-priority}
value: 1000000              # -2147483648..1000000000, higher = more important
preemptionPolicy: PreemptLowerPriority   # or "Never"
globalDefault: false
```
When a pod can't be scheduled, the scheduler finds nodes where evicting lower-priority
pods would free enough room, evicts them (respecting `PodDisruptionBudget` on a
best-effort basis: "if no such victims are found, preemption will still happen ... despite
their PDBs being violated"), then schedules the pending pod. Slurm's analogue is QOS-based
preemption: `PreemptType=preempt/qos` lets a job's QOS preempt other QOS's running jobs,
configured via `sacctmgr`, with per-QOS resource limits (`GrpTRES=cpu=24`) layered on top.

### Dependencies / ordering

Slurm: `-d, --dependency=<dependency_list>`, "Defer the start of this job until the
specified dependencies have been satisfied" (e.g. `afterok:<jobid>`). K8s has no native
pod-level equivalent (workflow tools like Argo layer this on top): this is a place K8s'
vocabulary is *not* the one to borrow; Slurm's is more directly applicable to a task
graph.

### Bulk submission

Slurm job arrays: `--array=0-31`, `--array=1,3,5,7`, `--array=1-7:2`, with a concurrency
cap via `%`: `--array=0-15%4` (max 4 running at once). Submitting one array creates a
single job record ("only one job record is created") that the controller can bulk-reason
about, meaning millions of tasks in one submission rather than N independent submissions.

### Namespace-wide caps

K8s `ResourceQuota`, "constraints that limit aggregate resource consumption per
namespace":
```yaml
spec:
  hard:
    requests.cpu: "10"
    requests.memory: "20Gi"
    pods: "100"
```
This is a pool-level cap orthogonal to any one task's declared requirements: the
namespace-scoped equivalent of `Workers`' global `parallelism=N`, but scoped per group
rather than globally.

## 5. Worker/process model

- **Slurm**: `slurmd` runs one per compute node, launches job steps as OS processes
  (optionally under `srun` for MPI rank distribution) directly on bare metal or VMs.
  Persistent daemon, long node lifetime.
- **K8s**: `kubelet` runs one per node, pods are containers under a container runtime
  (containerd/CRI-O). Nodes may autoscale; pods are cattle, routinely rescheduled anywhere
  cluster capacity exists.

Neither maps directly onto forked-worker-process-per-task, since both assume a
node/daemon layer above individual work units; `Workers`' "worker process" is closer to a
Slurm *job step* or a K8s *pod* than to either `slurmd`/`kubelet`.

## 6. Scheduling & concurrency control

- **Slurm** (backfill scheduler): "considers pending jobs in priority order, determining
  when and where each will start ... If [a lower priority job] doing so does not delay the
  expected start time of *any* higher priority job", it may start early, filling gaps.
  Otherwise resources are reserved for the higher-priority job's expected execution
  window (nodes go "Planned"), and lower-priority jobs in that partition wait. This
  requires reasonably accurate time estimates (`DefaultTime`, `MaxTime`) to work well.
- **K8s** (two-phase, per pod, no backfill/holds): "The *filtering* step finds the set of
  Nodes where it's feasible to schedule the Pod ... the *scoring* step ranks the remaining
  nodes to choose the most suitable Pod placement," assigning to the highest score (random
  tie-break). Filtering enforces hard constraints (requests fit, required affinity,
  tolerations satisfied); scoring applies soft preferences (preferred affinity weights,
  spread, etc.).

Both admission-check *before* starting a unit of work against a numeric capacity pool.
That is directly the mechanism `Workers.run(Config(parallelism=N))` needs: sum of running
tasks' `weight` (or 1 if `.exclusive`, consuming the whole pool) must not exceed `N`
before a fork proceeds.

## 7. Worker & node reliability (crash, hang, orphan, and pressure handling)

This is the machinery that keeps the *pool* trustworthy once tasks are actually running:
how a scheduler notices a worker died, notices a worker is stuck without dying, ensures a
killed task leaves nothing behind, and protects the pool from one bad task starving the
rest. Concrete mechanisms from both systems, then the mapping to `Workers`.

### Liveness / health detection

Slurm distinguishes controller-to-daemon reachability from workload-level health, with two
separate mechanisms:
- **`SlurmdTimeout`**: the interval the controller waits for `slurmd` to respond before
  marking that node `DOWN` (default 300 seconds). This is a heartbeat timeout, not a
  job-level check.
- **`HealthCheckProgram`** / **`HealthCheckInterval`**: "Fully qualified pathname of a
  script to execute as user root periodically on all compute nodes that are not in the
  NOT_RESPONDING state," run every `HealthCheckInterval` seconds (default 0, disabled) and
  itself bounded by `HealthCheckTimeout` (default 60s). This is an active probe of node
  condition (disk, memory, GPU sanity), independent of whether `slurmd` is merely alive.

K8s runs the same split at the workload level via probes on each container, not the node:
```yaml
livenessProbe:
  httpGet: {path: /healthz, port: 8080}
  initialDelaySeconds: 3
  periodSeconds: 3
  failureThreshold: 3        # default 3 consecutive failures
readinessProbe:
  exec: {command: [cat, /tmp/healthy]}
```
On liveness failure "the kubelet will kill and restart the container." On readiness
failure the pod is "marked unready and will not receive traffic from any services," but is
**not** restarted, a distinct signal for "alive but not ready to do work" versus "dead."
`startupProbe` additionally gates both, suppressing liveness/readiness checks until a
slow-starting container finishes booting, so startup latency is never misread as a hang.

For node-level liveness, K8s uses two heartbeat channels ("Updates to the `.status` of a
Node" and per-node `Lease` objects in `kube-node-lease`), polled by the node controller
every `node-monitor-period` (default 5s); after `node-monitor-grace-period` (default 40s)
of silence the node's `Ready` condition flips to `Unknown`.

### Hung / stuck detection and recovery

A process that is alive but wedged (not exiting on signal) is a distinct failure mode from
a process that is simply gone, and both systems treat it as one. Slurm's
**`UnkillableStepTimeout`** (commonly configured around 120 to 180 seconds, recommended at
least 5x `MessageTimeout`) is how long the controller waits for a job step's processes to
actually terminate after being asked to; if that expires, the node is drained (removed
from scheduling) and **`UnkillableStepProgram`** runs, so an operator-defined action
(alert, reboot the node) fires automatically rather than the job silently squatting
forever. This is layered on top of the ordinary time limit: **`KillWait`**, "the interval,
in seconds, given to a job's processes between the SIGTERM and SIGKILL signals upon
reaching its time limit" (default 30s), and **`OverTimeLimit`**, "Number of minutes by
which a job can exceed its time limit before being canceled" (default 0, no grace).

K8s has no separate "hung" concept at the pod level; a stuck-but-alive process is only
caught if it fails to answer its own `livenessProbe` (application-defined), or if the whole
Job exceeds `activeDeadlineSeconds`, at which point the Job controller "terminates all
in-flight Pods" unconditionally. There's no K8s equivalent of "escalate if the SIGTERM
didn't actually land" at the platform level, that's assumed to be handled by
`terminationGracePeriodSeconds` and, ultimately, the container runtime forcing a SIGKILL
when that expires.

### Crash / node-failure detection and automatic reschedule

Already covered in outline in section 6, restated here as reliability machinery, not just
scheduling: Slurm marks a job `NODE_FAIL` and, unless `--no-requeue` was set at submission,
automatically resubmits it to `PENDING` with the same job ID (`--open-mode=append` avoids
clobbering prior output on the retry). K8s's Job controller recreates a pod that dies
mid-run, up to `backoffLimit` (default 6) total pod failures, after which the whole Job is
marked `Failed` and stops retrying. Neither system resumes partial work: both restart the
unit of work from the top on the next attempt. That is a deliberate simplicity choice, not
a limitation either system is trying to work around, since neither has a checkpoint model
for arbitrary user code.

### Orphan prevention and cleanup

This is where the two systems diverge most and Slurm's answer is the sharper one to steal.
Slurm's default recommended configuration is `ProctrackType=proctrack/cgroup` with
`PrologFlags=Contain`: every process a job step spawns, including anything it forks or
execs, is placed in a Linux cgroup dedicated to that job step. This "constrains processes
to the resources they have requested" and has "the useful side effect of being able to
track all children of the job on that node," so killing the job means tearing down the
cgroup, which guarantees every descendant dies, not just the one PID Slurm originally
launched. The weaker alternative, `ProctrackType=proctrack/pgid` (process-group tracking),
is explicitly discouraged because a process that escapes the group (e.g. by `ssh`-ing to
another node) is invisible to it; `pam_slurm_adopt` exists specifically to catch such
escapees into an "extern" cgroup. K8s gets the equivalent property for free from container
isolation: a container's process tree lives in its own cgroup/namespace by construction, so
deleting the container's cgroup on pod teardown has the same all-descendants-die property,
plus `ownerReferences`-driven garbage collection to clean up any K8s-object-level orphans
(a pod whose owning Job was deleted).

### Graceful vs. forced eviction and drain

K8s makes this an explicit two-tier operation. `kubectl cordon <node>` marks a node
unschedulable without touching anything currently running there (soft, reversible).
`kubectl drain --ignore-daemonsets <node>` cordons and then evicts every evictable pod:
"Safe evictions allow the pod's containers to gracefully terminate and will respect the
PodDisruptionBudgets you have specified," meaning each pod gets its
`terminationGracePeriodSeconds` (SIGTERM, then SIGKILL if it hasn't exited) and drain
overall respects `PodDisruptionBudget.minAvailable`/`maxUnavailable`, refusing to evict
past the budget unless forced. A forced eviction (`--force`, or a `0s` grace period) skips
both protections. Preemption (section 4) is the same forced-eviction path triggered by the
scheduler rather than an operator. Slurm's analogous graceful-then-forced sequence is
per-job rather than per-node: `KillWait`'s SIGTERM-then-SIGKILL window is the only
"graceful" step Slurm offers; there is no Slurm equivalent of PDB-protected node draining,
because Slurm's failure unit (a job) has no notion of "N replicas must stay up."

### Resource-leak avoidance and pressure eviction

K8s kubelet actively protects node health against creeping resource exhaustion,
independent of any single container's own `limits`. It watches eviction signals such as
`memory.available` (`node.status.capacity[memory] - node.stats.memory.workingSet`),
`nodefs.available`, `imagefs.available`, and `pid.available`, each comparable against a
threshold of the form `[signal][operator][quantity]` (e.g. `memory.available<100Mi`).
**Hard** thresholds trigger eviction with "a `0s` grace period (immediate shutdown)"; the
docs are explicit that under a hard threshold "the kubelet does not respect your
configured PodDisruptionBudget or the pod's `terminationGracePeriodSeconds`," pressure
eviction overrides both protections that voluntary drain respects. **Soft** thresholds
respect a configured grace period first. Victim selection: "The kubelet ranks pods for
eviction and then evicts pods based on their priority level relative to other pods and
their resource usage relative to their requests," so a low-priority pod consuming well
past its `requests` is evicted before a high-priority one at its request line. This is
distinct from **OOMKill**, which is the kernel enforcing one container's own `memory`
limit (section 4), not node-wide pressure. Slurm has no equivalent active node-pressure
eviction; its posture is preventive (cgroup-enforced `--mem` request) rather than reactive.

### Delivery / execution guarantees on mid-run death

Neither system offers more than **at-least-once, whole-unit retry**. Slurm: `NODE_FAIL`
requeues the entire job to `PENDING`, rerun from the start, bounded only by
`--no-requeue` opting out entirely (no numeric retry cap; an admin/QOS layer would have to
add one). K8s Job: a died pod is recreated up to `backoffLimit`, and if the Job's own
`activeDeadlineSeconds` is exceeded, all in-flight pods are terminated and the Job is
marked `Failed`, with in-flight work lost, not preserved or resumed. `podFailurePolicy`
(1.28+) is the one refinement worth calling out: it lets a Job react to *why* a pod failed
(specific container exit code, or pod condition) rather than treating every failure
identically, e.g. don't retry on a container exit code that signals a non-retryable
application error.

### Backpressure / overload protection

K8s enforces this at **admission time**, not by queuing: `ResourceQuota` caps aggregate
`requests`/`limits`/object-counts per namespace, and a pod that would push the namespace
over quota is rejected outright when created, never silently queued to wait for room.
Independently, a pod that fits no node's available capacity simply sits `Pending`
indefinitely (`PodScheduled=False`, reason `Unschedulable`), with no automatic backoff
signal to the submitter beyond that status. Slurm's equivalent is QOS/association limits
(`GrpTRES=cpu=24` and friends, section 4): a job that would exceed them is left `PENDING`
with a reason code, not rejected, since Slurm's whole model is a queue that admits jobs as
resources free up, closer to how `Workers` should behave (queue behind the cap) than to
K8s's reject-at-admission posture.

### Lessons for Workers' reliability model

- **Crash detection, retry with a bound**: model Slurm's `NODE_FAIL` requeue and K8s's
  `backoffLimit` together, not either alone. Detect a dead worker process via exit code or
  a broken IPC channel (the direct analog of `slurmd` going silent past `SlurmdTimeout`),
  then retry the task from the top up to `Task.retry`'s bound, never unboundedly (Slurm's
  gap: `--no-requeue` is all-or-nothing, no count). A retry is always a full re-run, never
  a resume, matching both systems; `Workers` should not pretend to checkpoint arbitrary
  task thunks.
- **Hung-task detection, a second, longer timeout**: `Task.timeout` alone (Slurm's
  `--time`/`KillWait` analog: send the kill signal, give a short grace window, force-kill)
  is not sufficient. Steal `UnkillableStepTimeout`'s idea directly: if a worker hasn't
  actually exited some bounded time after being force-killed, that is a distinct,
  worse failure (a wedged process, possibly in uninterruptible I/O) that should escalate
  differently, flagging the worker for removal from the pool and surfacing an operator-
  visible signal, rather than the reaper looping forever assuming the kill worked.
- **Orphan-free kill**: adopt Slurm's cgroup reasoning over its pgid reasoning. A forked
  worker (and anything it spawns) needs an OS-level containment boundary that can be torn
  down atomically as a unit (process group plus cgroup on Linux, a Job Object on Windows),
  so killing a task on timeout or cancellation is guaranteed to kill every descendant, not
  just the immediate child `Workers` itself launched. Tracking a single PID (Slurm's
  discouraged `pgid` mode) is exactly the failure mode to avoid: a task that shells out and
  the shelled-out process outlives the kill.
- **Worker recycling over leak detection**: K8s's node-pressure eviction exists because
  pods are long-lived and a node must defend itself reactively. `Workers`' forked
  processes are short-lived task executors by design, so the correct answer is closer to
  never needing pressure detection at all: recycle (retire and replace) a worker process
  after N tasks or after any task that hit `.exclusive`/timeout/crash, rather than trying
  to detect creeping resource pressure in a long-lived worker. This sidesteps K8s's whole
  eviction-signal machinery as unneeded complexity for this shape of workload.
- **Graceful-then-forced cancellation**: when a caller interrupts a `Workers.fork` result
  (Fiber interruption), mirror `kubectl drain`'s two-step shape rather than either
  extreme: signal the worker to stop cooperatively first (bounded grace window, like
  `terminationGracePeriodSeconds`/`KillWait`), then force-kill via the containment
  boundary above if it hasn't exited. Never block the interrupting fiber indefinitely on
  an uncooperative worker.
- **Backpressure via queuing, not admission rejection**: `Workers.run(Config(parallelism=N))`
  should behave like Slurm's QOS limits (queue behind the cap) rather than K8s
  `ResourceQuota` (reject at admission), since `Workers` is explicitly a task queue, not a
  live-service platform where an operator wants immediate, loud rejection of over-quota
  requests. `Task.Queue.maxWorkers` is the namespaced-quota analog, nested inside the
  global cap exactly as ResourceQuota nests inside overall cluster capacity.

## 8. Results

Neither system has a first-class "return value" channel: a Slurm job's result is its exit
code plus whatever it wrote to stdout/output files; a K8s Job's result is pod
phase (`Succeeded`/`Failed`) plus logs. This is the one place `Workers.fork(...): Fiber[A,E]`,
a typed, in-band result channel, is a genuine improvement over both models, not something
to imitate.

## 9. Transport/protocol

- **Slurm**: controller-to-node RPC over its own binary protocol (Munge-authenticated),
  `slurmctld` talking to `slurmd`. Not an area to mine: bespoke and HPC-specific.
- **K8s**: everything through the API server as REST/JSON (or protobuf) against `etcd`;
  `kubelet` watches for pod specs assigned to its node. Reinforces the general shape
  (declare a spec, a controller watches and reconciles) but is far heavier machinery than
  a forked-worker-process model needs; not directly portable.

## 10. Lessons for `Workers`

Mapping the mined vocabulary onto the sketched API:

- `requests`/`limits` maps to **`weight`**: `weight` is a *requests*-only concept
  (admission arithmetic against the global cap `N`); `Workers` has no runtime-enforcement
  layer analogous to `limits`, and shouldn't invent one, since that's what OS-level
  process limits (ulimit/cgroups) are for at the fork boundary, if ever needed.
- `--exclusive` maps to **`.exclusive`**: same semantics, a task that claims the whole
  pool rather than its `weight`'s share. Slurm's binary flag (no vector form) is the right
  scope for v1; `--gres`'s named-resource generality is a plausible *future* extension of
  `weight` from scalar to vector, not needed now.
- K8s affinity/anti-affinity maps to **`Task.Queue` targeting / `locks`**:
  `Task.Queue(name, {classpath, maxWorkers})` is closer to a Slurm *partition* (a named
  pool with its own cap) than to K8s affinity. True anti-affinity ("don't run these two
  together") maps cleanly onto named `locks` with `exclusive` semantics per lock name, so
  there is no need for a separate affinity DSL when the point is mutual exclusion, not
  node topology.
- `--dependency` is worth adopting literally as an ordering primitive if `Workers` ever
  grows a task-graph feature; not in the current sketch's surface, but Slurm's vocabulary
  (`afterok:<id>`, etc.) is the right one to reach for over K8s' (which has none native).
- QOS/`PriorityClass` plus preemption: **avoid for v1**. Both are substantial machinery
  (victim search, PDB-aware eviction, grace periods) built for *long-running,
  expensive-to-restart* work competing for scarce global capacity. A `Task.priority`
  field that reorders admission into the `N`-cap (Slurm's backfill-order model, no
  preemption of already-running work) captures the useful 80%; live eviction of a running
  forked worker is a correctness hazard (mid-task state, partial output) `Workers`'
  target workloads (serializable task thunks) don't need to pay for.
- Job arrays' `%` concurrency cap is a good precedent for **per-`Task.Queue`
  `maxWorkers`**: a named group can have its own sub-cap independent of, and nested
  inside, the global cap, exactly like `--array=0-15%4` bounds one array's concurrency
  inside the cluster's overall capacity.

### Steal / avoid

- **Steal**: requests-vs-limits as two *separate* concepts even when `Workers` only
  implements the requests half (`weight`). Keeps the door open without conflating
  admission-time accounting and runtime enforcement.
- **Steal**: `--exclusive` as a plain boolean flag scoped to the whole task, not a named
  resource. Matches `Workers`' sketch exactly and needs no new vocabulary.
- **Steal**: backfill's priority-respecting fill-the-gaps admission order, without the
  preemption half: the safe subset of "priority" for restart-costly work.
- **Steal**: job-array-style per-group concurrency caps (`Task.Queue.maxWorkers`) nested
  inside the global cap.
- **Avoid**: full K8s-style required/preferred node affinity DSL. `Workers` has no node
  topology to place against (worker processes are fungible up to `Task.Queue`/`locks`
  constraints); a scoring/weighting layer on top of named pools and locks is unwarranted
  complexity for this problem shape.
- **Avoid**: live preemption/eviction of a running worker (K8s preemption, Slurm QOS
  preemption). Both exist to reclaim capacity from long-running, resumable/checkpointed
  work; forked task thunks are neither, so the correctness cost of killing one mid-flight
  outweighs the scheduling flexibility gained.
- **Steal**: cgroup-style "track and tear down every descendant as one unit," not
  single-PID tracking, as the model for orphan-free kill on timeout or cancellation.
- **Steal**: bounded-retry-then-fail, whole-unit rerun from the top, never a partial
  resume, as the crash-recovery contract (both systems do this; it is the right contract
  for arbitrary serializable task thunks, not a gap to fill in later).
- **Steal**: a distinct, longer "still hasn't died" escalation timeout
  (`UnkillableStepTimeout`) layered above the ordinary task timeout/kill-grace window, so
  a wedged worker that ignores its kill signal is detected and flagged, not assumed dead.
- **Avoid**: K8s-style node-pressure eviction and leak-detection heuristics on long-lived
  workers. `Workers`' worker processes are meant to be short-lived task executors; recycle
  them by policy (after N tasks, or after any crash/timeout) instead of building reactive
  pressure detection K8s only needs because its pods are long-lived.
- **Avoid**: PodDisruptionBudget-style "protect N running replicas during voluntary
  disruption." `Workers` tasks are not a replicated service with an availability floor to
  protect; there is nothing in the sketch this maps onto.
