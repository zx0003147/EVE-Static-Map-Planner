# Real 3D performance profile

This profile measures the CPU camera/projection/culling/depth-sort pipeline. It does not include
Compose recomposition, Skia rasterization, GPU presentation, or desktop compositor latency, so the
reported projection FPS is not an end-to-end UI frame rate.

Profile environment:

- Date: 2026-10-05
- Runtime: JDK 25
- Viewport: 1920 × 1080
- Static universe: 8,490 systems and 6,989 canonical stargate edges
- Warm-up: 120 pre-created camera frames per scenario
- Sample: 600 pre-created moving-camera frames per scenario
- Timing/allocation scope: `Real3DFrameProjectionWorkspace.project()` only

| Scenario | Systems | Edges | Labels | Avg ms | p50 ms | p95 ms | p99 ms | Avg allocated KiB | GC count / pause ms |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| Full map | 5,486 | 6,989 | 71 | 2.084 | 1.959 | 2.880 | 3.306 | 31.602 | 0 / 0 |
| Typical zoom | 3,940 | 5,252 | 629 | 1.740 | 1.643 | 2.368 | 2.763 | 15.623 | 0 / 0 |
| Rotated view | 5,486 | 6,989 | 871 | 1.960 | 1.862 | 2.635 | 2.910 | 31.602 | 0 / 0 |
| Inside map | 1,047 | 1,433 | 1,060 | 0.607 | 0.533 | 0.915 | 1.355 | 3.558 | 0 / 0 |
| Route visible | 3,932 | 5,117 | 629 | 1.696 | 1.579 | 2.367 | 2.950 | 15.483 | 0 / 0 |
| Four jump spheres | 3,860 | 5,003 | 615 | 1.652 | 1.531 | 2.352 | 2.827 | 15.478 | 0 / 0 |
| Common overlays | 2,896 | 3,898 | 2,935 | 1.339 | 1.280 | 1.863 | 2.192 | 15.483 | 0 / 0 |

The calibrated pre-optimization full-map baseline was 4.082 ms average, 5.268 ms p95, 6.561 ms p99,
1,513.775 KiB allocated per frame, and 3 collections / 26 ms of GC pause during the sample. The
optimized run reduced allocation by 97.9% and full-map p95 by 45.3%. Per-scenario correctness
checksums remained identical before and after the optimization. Remaining projection allocation is
primarily JVM depth-sort workspace and small fixed per-frame values.

Run the repeatable profile with:

```powershell
.\gradlew.bat :data:real3DPerformanceProfile -Preal3DProfileDatabase="C:\path\to\static.db"
```
