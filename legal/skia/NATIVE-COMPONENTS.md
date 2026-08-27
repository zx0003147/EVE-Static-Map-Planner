# Skia native component provenance

This record accompanies the Skiko Windows native binary in EVE Static Map Planner.
The sibling `freetype.md`, `harfbuzz.md`, `icu.md`, `jpeg.md`,
`libpng.md`, `zlib.md`, `expat.txt`, and `libwebp.txt` files reproduce
the applicable permissive terms and attribution notices.

- Skiko: `v0.9.37.3`, commit `ecc1b2e7976a2bb06344e0007f05150ccd02c591`, Apache-2.0
- skia-pack: `m138-80d088a-1`, commit `9481c9b3b8e740d240c7f300cf3a0398abbdb052`
- Skia source: milestone 138, revision fragment `80d088a`, BSD-3-Clause

The exact skia-pack Windows build declares bundled rather than system copies of these dependencies:

| Component | Exact Skia DEPS revision | Principal license | Authoritative source |
|---|---|---|---|
| Expat | `8e49998f003d693213b538ef765814c7d21abada` | MIT | <https://github.com/libexpat/libexpat> |
| FreeType | `702e4a1d32e4b911e85cc7df84b3ba395c28dab3` | FreeType License or GPLv2 | <https://chromium.googlesource.com/chromium/src/third_party/freetype2.git> |
| HarfBuzz | `08b52ae2e44931eef163dbad71697f911fadc323` | MIT | <https://github.com/harfbuzz/harfbuzz> |
| ICU | `364118a1d9da24bb5b770ac3d762ac144d6da5a4` | Unicode/ICU | <https://chromium.googlesource.com/chromium/deps/icu.git> |
| libjpeg-turbo | `e14cbfaa85529d47f9f55b0f104a579c1061f9ad` | BSD-style/IJG/zlib | <https://chromium.googlesource.com/chromium/deps/libjpeg_turbo.git> |
| libpng | `ed217e3e601d8e462f7fd1e04bed43ac42212429` | libpng | <https://skia.googlesource.com/third_party/libpng.git> |
| libwebp | `845d5476a866141ba35ac133f856fa62f0b7445f` | BSD-3-Clause | <https://chromium.googlesource.com/webm/libwebp.git> |
| zlib | `646b7f569718921d7d4b5b8e22572ff6c76f2596` | zlib | <https://chromium.googlesource.com/chromium/src/third_party/zlib> |

Revision evidence is the `DEPS` file at Skia revision `80d088a`; build-selection evidence is the tagged skia-pack Windows build configuration. These components are permissively licensed and no redistribution-prohibiting term was identified.
