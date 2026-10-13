# Changelog

## 0.2.0-beta (Minecraft 1.9.4)

First public beta.

- **Region batching:** chunks are drawn in 8×4×8-chunk regions with one `glMultiDrawArrays` per region instead of one draw per chunk. Region storage grows by adding pages instead of copying buffers, so it works on Apple's OpenGL driver, which has no `ARB_copy_buffer`.
- **Faster visible-chunk search**, with identical output to vanilla (verified frame-by-frame in the test suite).
- Camera-chunk scan cache and an entity-pass chunk cache, both with identical output to vanilla.
- Faster `BufferBuilder` growth and translucent sorting (byte-identical), plus a fix for a vanilla buffer-growth overflow.
- Startup check that Java and the native libraries are Apple Silicon (arm64), not running under Rosetta.
- Benchmarks (M1 Max): ~1.9× vanilla and ~2.8× OptiFine at render distance 16 and 24. See [BENCHMARKS.md](BENCHMARKS.md).

Known limitations: tested on one Mac (M1 Max) so far. GPU memory for chunk storage is about 2× the mesh size. Display-list mode (VBOs off) isn't optimized; region batching always uses VBOs.
