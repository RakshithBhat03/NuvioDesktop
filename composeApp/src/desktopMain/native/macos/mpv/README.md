# Bounded subtitle packet cache

The macOS bridge enables `demuxer-subtitle-cache-bytes=32MiB` in the bundled
libmpv. This patch retains compressed subtitle packets from the existing input
and replays only the selected subtitle queue, without seeking the source or
clearing audio/video queues. The shared limit includes estimated packet metadata
and backing-buffer sizes. Decoder allocations are separate. Entries older than
120 seconds behind the reader are pruned during insertion, subject to packet
ordering and the byte limit. Closing the demuxer frees the cache.

The optimization applies to MKV/WebM, MP4/MOV and AVI single-file demuxers.
HLS/DASH and standalone subtitle demuxers retain their existing behavior to avoid
opening unselected network resources. A missing/evicted cue does not trigger a
reread; playback continues until available subtitle data can be displayed.
Normal user seeks can still fetch media. No movie disk cache or second reader is
used. Addon subtitles still download their own subtitle file through `sub-add`.

## Source and rebuild

`subtitle-ram-cache.patch` applies to commit
`c0dd2b32d52c66b9efa85a7ee69965569503f6b8`, the exact `c0dd2b3` source revision
recorded in the original bundled IINA runtime. That commit is retrievable from
the mpv-player/mpv Git object database even though the former iina/mpv repository
URL returns 404. The build script fetches and verifies the full commit hash.

An earlier iteration incorrectly substituted the upstream v0.38.0 tag. That
substitution has been withdrawn. This build preserves the original revision's
additional audio, subtitle, rendering and other fixes, then applies only the
subtitle RAM-cache patch. The existing dependency dylibs are retained. Compiler
and SDK differences mean the rebuilt binary is not byte-identical; build-date
remains disabled for reproducibility.

The build script pins mpv, FFmpeg, libass and libplacebo header sources and links
against the repository's existing dependency libraries. Other compatible headers
come from Homebrew. Prerequisites: Xcode, Python 3, Git LFS, Meson, Ninja,
pkg-config, and Homebrew libarchive, libbluray, mujs, luajit, little-cms2,
uchardet, rubberband, vulkan-loader, vulkan-headers, zimg and jpeg-turbo.

From the repository root, after materializing the runtime with Git LFS:

```sh
python3 composeApp/src/desktopMain/native/macos/mpv/build-macos.py --arch arm64 --work-dir /tmp/nuvio-mpv-build
python3 composeApp/src/desktopMain/native/macos/mpv/build-macos.py --arch x86_64 --work-dir /tmp/nuvio-mpv-build
```

The script outputs signed dylibs under `output-ARCH` and does not install them.
After validation, replace only the corresponding repository runtime libmpv file.
Never change an installed or signed application bundle. Preserve mpv's upstream
GPL/LGPL notices and provide the pinned source and patch when distributing it.
Upstream source and license: https://github.com/mpv-player/mpv/tree/c0dd2b32d52c66b9efa85a7ee69965569503f6b8

## Validation performed

- Both architectures rebuilt with macOS 12 minimum and portable runtime paths.
- HTTP integration suite passed on arm64 and x86_64 under Rosetta. MKV and MP4
  covered builtin/off/back switching, pause, cached seek, addon subtitles,
  return to builtin, cache eviction with a 1 KiB cap, and stream close.
- An ongoing, partially downloaded MKV switched without a new movie request.
- No anonymous disk cache descriptor was opened.
- Offscreen OpenGL rendering passed with VideoToolbox hardware decoding.
- Native bridges compiled for both architectures.

Run the HTTP suite with:

```sh
python3 composeApp/src/desktopMain/native/macos/tests/test_subtitle_ram_cache.py
NUVIO_TEST_ARCH=x86_64 python3 composeApp/src/desktopMain/native/macos/tests/test_subtitle_ram_cache.py
```

`subtitle_ram_render_test.mm` is the offscreen OpenGL test. The user reports
successful manual playback and subtitle switching with the corrected build.
Specific HDR, image-based subtitle, and signed/notarized release verification
remain unconfirmed.
