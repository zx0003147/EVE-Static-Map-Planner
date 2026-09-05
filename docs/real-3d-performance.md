# Real 3D performance profile

This profile measures the CPU camera/projection/culling/depth-sort pipeline. It does not include
Compose recomposition, Skia rasterization, GPU presentation, or desktop compositor latency, so the
reported projection FPS is not an end-to-end UI frame rate.

Profile environment:

- Date: 2026-09-04
- Runtime: JDK 25
- Viewport: 1920 × 1080
- Static universe: 8,490 systems and 6,989 canonical stargate edges
- Warm-up: 40 frames per scenario
- Sample: 240 moving-camera frames per scenario

| Scenario | Systems | Edges | Labels | Extra primitives | Avg ms | p95 ms | Approx. projection FPS | Avg allocated KiB |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| Full map | 5,486 | 6,989 | 71 | 0 | 2.093 | 2.768 | 477.681 | 1,513.845 |
| Typical zoom | 3,964 | 5,329 | 633 | 0 | 1.732 | 2.655 | 577.400 | 1,122.320 |
| Rotated view | 5,486 | 6,989 | 871 | 0 | 1.915 | 2.633 | 522.188 | 1,513.843 |
| Inside map | 1,063 | 1,437 | 1,076 | 0 | 0.653 | 1.252 | 1,531.323 | 296.206 |
| Route visible | 3,965 | 5,181 | 633 | 1 route leg | 1.648 | 2.557 | 606.629 | 1,108.961 |
| Four jump spheres | 3,899 | 5,064 | 620 | 2,848 | 2.267 | 3.179 | 441.077 | 1,678.291 |
| Common overlays | 2,923 | 3,926 | 2,962 | 564 | 1.616 | 2.297 | 618.761 | 880.927 |

The worst measured CPU projection p95 was 3.179 ms with four tessellated jump spheres. The 60 FPS
CPU budget is 16.667 ms. Remaining allocation hotspots are immutable screen-coordinate values and
route/sphere projection records. Static XYZ geometry, label layouts, decoded marker images, sphere
meshes, feature ownership links, path effects, and the large frame collections are cached or reused.

Run the repeatable profile with:

```powershell
.\gradlew.bat :data:real3DPerformanceProfile -Preal3DProfileDatabase="C:\path\to\static.db"
```
