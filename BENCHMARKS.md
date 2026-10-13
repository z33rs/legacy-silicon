# Benchmarks

## Vanilla vs OptiFine vs Legacy Silicon (2026-10-12)

**Machine:** MacBook Pro, M1 Max, 32 GB, macOS 26.7. Prism Launcher, arm64 Azul Zulu Java 8 with the same G1 GC flags and a 2 GB heap for every setup.

**Scene:** a vanilla 1.9.4 server on localhost, with each client joining as a spectator at x=-54.5 y=92 z=-2.5, looking 20° down over a dense taiga forest and coast (seed -1993243410). Fixed time (noon) and clear weather.

**Settings (all setups):** 1728×1080 window, Fancy graphics, smooth lighting max, mipmaps 4, VBOs on, VSync off, unlimited framerate, clouds on. OptiFine used the tester's own OptiFine settings (Fast Render, Fast Math, Smart Animations, Lazy Chunk Loading, Render Regions on, Smooth FPS off).

**Method:** `tools/lsbench`, a small Java agent that works the same under Forge, Legacy Fabric and vanilla/OptiFine:
- After joining, it warms up for 90 s, sweeping the camera 360° every 10 s.
- It then keeps sweeping for 40 s while sampling the game's own once-per-second FPS counter.
- Each setup is its own Prism instance, driven by `tools/lsbench/run_bench.py`.

| Setup | RD 16 avg FPS | RD 24 avg FPS |
|---|---|---|
| Vanilla 1.9.4 (Legacy Fabric loader, no mods) | 127.6 / 130.6 | 74.1 |
| OptiFine 1.9.4 HD U I5 + Forge 12.17.0.2317 | 88.2 | 43.6 |
| OptiFine 1.9.4 HD U I5 standalone (jar mod) | 90.9 | 43.5 |
| **Legacy Silicon 0.2.0** | **245.4 / 250.8** | **135.6** |

Raw per-second samples are in `tools/lsbench/results-2026-10-12-m1max.txt`.

**Why OptiFine is slower than vanilla on Apple Silicon.** OptiFine logs `Render Regions not supported, missing: OpenGL 1.3, ARB_copy_buffer`. Apple's OpenGL driver doesn't expose that extension, so OptiFine's draw batching is off, and its other features still add cost. Legacy Silicon's batching never copies GPU buffers, so it works on this driver.

**Caveats:**
- This is one machine and one scene, and each setup ran once (vanilla and Legacy Silicon at RD 16 ran twice).
- The first vanilla RD 16 run happened while another app held most of the RAM. The re-run, after that app closed, gave a similar result.
- OptiFine draws some things differently from vanilla (connected textures, custom sky), so its scene isn't pixel-identical to vanilla.

## Vanilla vs Legacy Silicon, A/B in one session (dev autotest)

The dev autotest (`./gradlew runClient -PautoTest`) alternates vanilla and optimized rendering (V, M, V, M) inside one game session. It also checks visual equivalence with a pixel test and an exact-output test of the visible-chunk search. See the README for details.

| Scenario | Vanilla | Legacy Silicon |
|---|---|---|
| RD 16 | 151 FPS | 277 FPS |
| RD 24 | 79 FPS | 133 FPS |
| RD 32 | 49 FPS | 102 FPS |
| Flying through new terrain at 20 blocks/s, RD 16 | 132–145 FPS | 225–239 FPS |
