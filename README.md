# DH Affinity

A small Fabric client mod for **Minecraft 1.21.11** that lets you choose which CPU cores
[Distant Horizons](https://modrinth.com/mod/distanthorizons) works on, and which cores the rest of
the game uses — from an in-game menu, for any CPU.

It was built for asymmetric CPUs like the **Ryzen 9 9950X3D**: the game (render thread, vanilla
chunk workers, GC, …) stays on the V-cache CCD (logical CPUs 0–15) while DH's LOD generation runs
on the second CCD (16–31) instead of competing for the same cores and cache. Groups may overlap
freely; nothing stops you from giving both groups the same cores.

If you use a Process Lasso "javaw → cache CCD" rule (or similar), this mod replaces it. **Remove that rule** — while it is
active, Windows refuses to move any thread outside the process mask, so DH cannot be put on the
other CCD. The mod widens the process mask itself and enforces the split per thread.


> **Status: beta.** Tested on one Windows 11 machine (Ryzen 9 9950X3D, Distant Horizons 3.2.0-b, Fabric 1.21.11) plus the
> Linux backend's unit tests. Off-thread GPU upload is on by default and falls back to DH's normal path automatically on any
> GL error; it can also be switched off in the menu. The hot-path hooks are matched against DH 3.2.x and disable themselves
> (leaving plain core pinning) if a future DH build changes those internals. If something misbehaves, run `/dhaffinity profile 40` and `/dhaffinity status` and include both outputs in a report at
> https://github.com/haakonhaug/dhaffinity/issues.

## Install

1. Create a Minecraft **1.21.11** profile with **Fabric Loader ≥ 0.19.3**
   (<https://fabricmc.net/use/installer/>).
2. Put **Distant Horizons for 1.21.11** in `mods/` (built and tested against **3.2.0-b**; DH
   already bundles the Fabric API modules this mod needs). [ModMenu](https://modrinth.com/mod/modmenu)
   is optional but gives you a "Configure" button.
3. Put `dhaffinity-<version>.jar` in `mods/`.
4. Remove/disable any Process Lasso (or similar) CPU-affinity rule for `javaw.exe`.
5. After the first launch, check DH's own **Number of Threads** (DH settings → Advanced → Multi
   Threading): DH sizes it from the CPUs it saw at first start. With 16 cores reserved for DH, 16
   threads is a good value.

## The menu

Open it from ModMenu → DH Affinity → Configure, or type `/dhaffinity gui`.

- One row per thread group, one toggle per logical CPU (the grid sizes itself to your CPU). Click a
  cell to toggle it, click-and-drag to paint several.
- Rows: **Minecraft & everything else** and **Distant Horizons**. "Advanced" reveals the
  **main/render thread** on its own row and one row per DH pool (World Gen, LOD Builder, Render
  Loader, IO, Update Propagator, Network Compression) so, for example, world generation and the
  render-side loader can live on different cores. Each optional row has a "Same as …" box.
- Presets: "All", "None" and one button per detected L3-cache group — on a 9950X3D that is
  "0–15" and "16–31", i.e. the two CCDs. On a CPU with a single L3 you get lower/upper half instead.
- **Save & Apply** writes `config/dhaffinity.json` and re-pins every thread within one sweep;
  no restart needed. The only thing the menu refuses is an empty group (a thread with no CPU
  cannot run).

## Chunk-generation workers (1.0.1)

Minecraft generates its chunks on its background worker pool (`Worker-Main-N`; C2ME does the
generation on its own `c2me-worker-N` threads instead). In a brand-new world those threads run
flat out, and on a split-CCD CPU they used to share the game cores with the render thread —
enough to starve it and produce the "new world stutter" the first version could not explain.

`Worker-Main` is vanilla's *general* background pool: besides chunk generation it also does
world saving, resource reloads and — without Sodium — chunk meshing. Name matching cannot split
one pool, so the whole pool moves. With Sodium installed (the usual case) meshing has its own
threads and stays with the game.

Since 1.0.1 they form their own group, **Chunk generation workers**, shown in the menu under
Distant Horizons and defaulting to *Same as Distant Horizons* (the high-clock CCD on a 9950X3D).
Threads are recognised by name, so this needs no other mod and works with plain vanilla:

```json
"chunkGenMask": "",
"chunkGenThreadPatterns": ["Worker-Main-", "c2me-worker-"]
```

* `chunkGenMask` — CPUs for the group; empty = same as `dhMask`.
* `chunkGenThreadPatterns` — regular expressions matched against the **start** of a thread's
  name (Linux truncates names to 15 characters, so prefixes are the reliable form). Add another
  generator mod's threads by adding its prefix; an empty list disables the group. Only heavy
  background pools belong here — sound, networking and Sodium's chunk meshing threads stay with
  the game on purpose.
* `/dhaffinity status` prints `Chunk generation -> CPUs … | matched N threads (Worker-Main x15,
  c2me-worker x18)`, so you can see the patterns working. The group is only applied while
  *Manage non-DH threads* is on, and only in singleplayer / LAN-host worlds — on a remote server those
  threads do no terrain generation, so they stay with the game (since 1.0.2).

Upgrading from 1.0.0: this is on by default. To keep the old behaviour, open the menu, untick
*Same as Distant Horizons* on the new row and select the game cores.

## How it works

1. **`preLaunch`** (before Minecraft's own threads exist): read the config, widen the process
   affinity to all CPUs, remember the main thread's native ID, start the sweeper.
2. **DH workers pin themselves.** Two small Mixins swap in a wrapping thread factory the moment
   DH creates its pools (`PriorityTaskPicker.Executor#createThreadPool` for the heavy pools —
   World Gen, LOD Builder, Render Loader, IO, Update Propagator, Network Compression — and
   `ThreadPoolUtil#setupThreadPools` for its small ones). The first thing each worker does is
   register its OS thread ID and pool name and pin itself to its group's CPUs.
3. **A background sweeper reconciles everything else.** A daemon thread at minimum priority,
   itself pinned to the game CPUs, periodically lists the process' native threads and makes sure
   each one has the mask it should have *according to the current config*: DH workers → their
   pool's mask, the main thread → its mask, everything else → the game mask. It reads the current
   mask first and only writes when it differs, so in steady state a sweep is one thread snapshot
   plus one read per thread and **no writes**. It also re-widens the process mask if something
   shrinks it again (and warns once). Because it enforces desired state instead of remembering
   what it pinned, reused thread IDs, external tools and config changes cannot confuse it.
4. Cadence: every `startupSweepMs` (250 ms) for the first `startupWindowMs` (30 s) while the game
   is still spawning thread pools, then every `steadySweepMs` (2 s). It never runs on the render
   thread. On Windows a sweep costs a few milliseconds of wall-clock time on that lowest-priority
   thread (the OS thread snapshot is system-wide); that is invisible to frame times.
5. Because widening the process mask makes Java report all CPUs, Minecraft would otherwise size
   its background worker pool for 32 CPUs while the sweeper confines it to 16. The mod therefore
   sets `-Dmax.bg.threads` to (game CPUs − 1) at preLaunch unless you set it yourself
   (`capVanillaWorkerThreads`).

Windows uses `kernel32` through the JNA that Minecraft already bundles; Linux uses
`sched_setaffinity`. Other platforms log "unsupported" and do nothing.

## Config — `config/dhaffinity.json`

Created on first launch from your CPU's topology (two or more L3 groups → the group with the
largest cache gets the game and the rest go to DH; otherwise both groups start with all CPUs and
nothing is separated until you choose cores in the menu):

```json
{
  "enabled": true,
  "gameMask": "0-15",
  "mainThreadMask": "",
  "dhMask": "16-31",
  "dhPoolMasks": {},
  "manageNonDhThreads": true,
  "manageProcessAffinity": true,
  "capVanillaWorkerThreads": true,
  "startupSweepMs": 250,
  "startupWindowMs": 30000,
  "steadySweepMs": 2000,
  "logPins": false,
  "offThreadGpuUpload": true,
  "renderThreadTaskBudgetMs": 0,
  "uploadPacing": "auto"
}
```

| Key | Meaning |
|---|---|
| `gameMask` | CPUs for everything that is not a DH worker. CPU list (`0-15`, `0,2,4-7`) or hex (`0x0000FFFF`). Empty = all CPUs. |
| `mainThreadMask` | CPUs for the main/render thread only. Empty = same as `gameMask`. |
| `dhMask` | CPUs for Distant Horizons worker threads. Empty = all CPUs. |
| `dhPoolMasks` | Per-pool overrides, e.g. `{ "World Gen": "16-23", "IO": "24-31" }`. Pools not listed (or empty) follow `dhMask`. DH pool names: `World Gen`, `LOD Builder`, `Render Loader`, `IO`, `Update Propagator`, `Network Compression`, plus small single-thread pools. |
| `manageNonDhThreads` | `false` = only pin DH threads and leave everything else alone (e.g. if you keep another tool for the game threads). |
| `manageProcessAffinity` | Keep the Windows process mask at "all CPUs" so thread pins can succeed. |
| `capVanillaWorkerThreads` | Set `-Dmax.bg.threads` to (game CPUs − 1) at start-up if it is not set already. Only applies while `manageNonDhThreads` is on (otherwise the game threads are not confined), and only at start-up, not on reload. |
| `startupSweepMs` / `steadySweepMs` | Sweep intervals; values below 50 ms become 50. |
| `startupWindowMs` | Length of the fast-sweep window after launch; 0 disables it. |
| `logPins` | Log every individual pin. Useful once to verify, noisy afterwards. |
| `offThreadGpuUpload` | Run DH's GPU buffer uploads on a dedicated thread with a shared OpenGL context (see below). |
| `renderThreadTaskBudgetMs` | Per-frame time cap for DH's GL tasks on the render thread while uploads are not off-loaded; 0 = DH default. |
| `uploadPacing` | `"auto"`, `"off"`, or a number of finished LOD sections handed to the renderer per frame (see below). |

Masks that name CPUs the machine does not have are trimmed with a warning; a group that would end
up empty falls back to all CPUs (the log and `/dhaffinity status` say so). Overlaps are fine.

## Off-thread GPU upload (experimental, OFF by default since 1.0.2)

1.0.0 uploaded Distant Horizons' finished LOD buffers on a dedicated thread with a second OpenGL
context sharing Minecraft's, with adaptive pacing. It measured well in the render-thread profiler, but the design is a known
cost: on NVIDIA a second shared context carries a per-frame tax and serialises driver work against
the render thread (Sodium's NVIDIA workaround makes every such wait show up inside
the render thread's own GL calls). Distant Horizons' developers built and removed the same design.
Since 1.0.2 it is off by default and DH's normal render-thread upload path is used; the checkbox
"Off-thread GPU upload (experimental)" turns it back on for experiments, with "Hand-over pacing"
(Auto / Off / N sections per frame) limiting how many finished sections are handed over per frame.
Existing 1.0.0/1.0.1 configs are migrated once (`configVersion`); a choice made afterwards is kept.

## The lock nobody saw: DH's keyed-level lookup

Profiling a Ryzen 9 9950X3D system showed 76% of hitch-frame time with the render thread *blocked*
inside DH's `KeyedClientLevelManager_fabric.getServerKeyedLevel`: it takes one global monitor on
every call, and in singleplayer (no server key) it never caches its "null" answer, so every DH
worker thread and the render thread pay the lock plus a wrapper lookup each time. Faster DH
workers on dedicated cores turn that into a lock convoy the render thread queues behind. The mod
puts a lock-free single-entry cache in front of it (invalidated whenever DH changes the keys; a
null answer is only cached while keyed levels are disabled, so servers that assign keys are never
masked). `/dhaffinity profile` shows "(BLOCKED on a lock)" next to any such sample.

### Lock #2: `ClientLevelWrapper_fabric.getWrapper`

Right behind the keyed-level manager sits DH's `synchronizedMap` of Minecraft level → DH wrapper. Every DH
call site asks for the wrapper, and each ask takes that monitor twice. With the workers un-throttled the
render thread queued behind it for 66% of the remaining hitch time. `ClientLevelWrapperMixin` keeps a
single-entry lock-free cache (level → wrapper) that is filled from DH's own result, cleared when DH unloads
the wrapper, and bypassed entirely once a server enables keyed levels (DH's override logic then runs
untouched). Singleplayer and normal servers never take the lock on the hot path any more.

## Stutter that is not DH's fault: garbage collection

Standing still and still hitching every few seconds, with all game cores spiking at once in Task
Manager, is the JVM's garbage collector pausing every thread. DH's generation allocates a lot,
so it triggers collections often. `/dhaffinity status` shows GC pauses and hitches over the last
60 seconds so you can tell the two apart. The fix is JVM flags in the launcher profile
(Installations → edit → JVM arguments). Minecraft 1.21.11 runs on Java 21, where generational
ZGC keeps pauses under a millisecond:

```
-Xms8G -Xmx8G -XX:+UseZGC -XX:+ZGenerational
```

If you prefer to keep G1, at least size its threads for the game cores instead of all 32:

```
-Xms8G -Xmx8G -XX:+UseG1GC -XX:MaxGCPauseMillis=20 -XX:ParallelGCThreads=12 -XX:ConcGCThreads=3
```

## Reading the numbers

`/dhaffinity status` reports **spikes** (frames over 2x the running average and over 8 ms — what a
frametime graph shows as a blip) separately from **hitches** (>2.5x and >33 ms). Judge changes by
spikes, average and worst frame, not by hitch count alone; `/dhaffinity profile <seconds>` attributes
spike frames to whatever the render thread was doing.

## In-game commands

* `/dhaffinity gui` — open the menu.
* `/dhaffinity` or `/dhaffinity status` — backend, CPU count and L3 layout, the masks in force,
  process mask, whether the DH hooks are active, DH threads alive per pool with their cores, DH's
  configured thread count, sweeper statistics, the GPU upload thread's state and throughput, and
  frame/GC diagnostics for the last 60 seconds (frames, hitches over 33/50 ms, worst frame, GC
  pauses).
* `/dhaffinity profile [seconds]` — sample the render thread (~250×/s) for the given time (default 30)
  while you fly, then print what it was doing during the hitch frames: DH rendering, vanilla
  chunk building/upload, shader compilation, an OpenGL driver call, this mod, … The same report
  goes to `logs/latest.log`. Sampling briefly pauses the render thread each time, so run it on
  demand, not permanently.
* `/dhaffinity reload` — re-read the config file and apply it on the next sweep.
* `/dhaffinity sweep` — run a sweep now.

## Verifying it works (Windows)

1. Make sure any external affinity rule (e.g. Process Lasso) is gone, launch, open a world with DH generating LODs.
2. Task Manager → Performance → CPU → right-click the graph → *Change graph to → Logical
   processors*. CPUs 16–31 light up under DH load while the game stays on 0–15.
3. `/dhaffinity status` should show `Process affinity: … — ok`, `DH hook: active`, DH threads
   alive per pool pointing at 16–31, and sweeps with `corrected 0` once things settle (a few
   milliseconds each, measured as wall-clock time on a lowest-priority thread, so the number
   grows under load — that is time the thread spent waiting, not stutter). With
   `"logPins": true` the log lists every pin.
4. A frametime overlay should show no periodic stutter; the sweep is invisible.

If status says the process affinity is **RESTRICTED**, something outside the game is still
applying a process-level rule.

## Building

Requires JDK 21.

```
./gradlew build
```

The jar lands in `build/libs/`. Unit tests cover mask parsing, config validation, the
reconciliation logic (against a simulated OS), and, on Linux, the real `sched_setaffinity` path.
The Windows backend and the menu cannot be exercised by the tests; verify them in-game as
described above. `./gradlew runClient` starts a dev client with DH and ModMenu on the classpath.

## Limitations

* 64 logical CPUs / a single Windows processor group.
* GC and JIT threads are treated as "game" threads (same as a Process Lasso rule would).
* Coupled to a handful of DH internals: `PriorityTaskPicker.Executor#createThreadPool` and
  `ThreadPoolUtil#setupThreadPools` (affinity), `RenderThreadTaskHandler#queueRunningOnRenderThread`
  / `#runRenderThreadTasks` and `GLBuffer#uploadBuffer` (GPU upload). If a DH update changes any of them, the affected
  feature logs an error and reports itself inactive instead of crashing.
* Off-thread upload only exists for DH's OpenGL renderer (its Vulkan path is untouched).
* DH's one-off single-thread helpers (delayed-save caches, cleanup, queue drivers, its logger)
  are created through a class that loads before Mixin can hook it; they stay with the game
  threads, exactly as they did under a Process Lasso rule. All the heavy work is in the pools
  above.
