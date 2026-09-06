#!/usr/bin/env python3
"""Build the subtitle-cache mpv against the existing macOS runtime libraries.

Outputs a dylib under --work-dir; never modifies application bundles or installs
into the repository. See README.md for prerequisites and the install step.
"""
import argparse
import os
import platform
import re
from pathlib import Path
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[6]
SOURCES = {
    'mpv': ('https://github.com/mpv-player/mpv.git', None, 'c0dd2b32d52c66b9efa85a7ee69965569503f6b8'),
    'ffmpeg': ('https://github.com/FFmpeg/FFmpeg.git', 'n7.0', '083443d67cb159ce469e5d902346b8d0c2cd1c93'),
    'placebo': ('https://github.com/haasn/libplacebo.git', 'v6.338.2', '64c1954570f1cd57f8570a57e51fb0249b57bb90'),
    'ass': ('https://github.com/libass/libass.git', '0.17.2', 'cbb48cc4f2f076300004b8b06a86bec55281d0c2'),
}


def run(*args, **kwargs):
    return subprocess.run(args, check=True, **kwargs)


def output(*args):
    return subprocess.check_output(args, text=True).strip()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--arch', choices=['arm64', 'x86_64'], required=True)
    parser.add_argument('--work-dir', type=Path, required=True)
    args = parser.parse_args()
    work = args.work_dir.resolve()
    work.mkdir(parents=True, exist_ok=True)
    runtime = ROOT / 'composeApp/src/desktopMain/native/macos/runtime' / args.arch
    for name, (url, tag, commit) in SOURCES.items():
        source = work / name
        if not source.exists():
            if tag:
                run('git', 'clone', '--depth', '1', '--branch', tag, url, str(source))
            else:
                run('git', 'init', str(source))
                run('git', '-C', str(source), 'fetch', '--depth', '1', url, commit)
                run('git', '-C', str(source), 'checkout', '--detach', 'FETCH_HEAD')
        if output('git', '-C', str(source), 'rev-parse', 'HEAD') != commit:
            raise RuntimeError(f'Unexpected source revision in {source}')
    patch = Path(__file__).with_name('subtitle-ram-cache.patch')
    mpv = work / 'mpv'
    # Re-runs accept precisely the already-applied patch; never reset sources.
    applied = subprocess.run(['git', '-C', str(mpv), 'apply', '--reverse', '--check', str(patch)],
                             stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode == 0
    if not applied:
        run('git', '-C', str(mpv), 'apply', '--check', str(patch))
        run('git', '-C', str(mpv), 'apply', str(patch))
    ffmpeg = work / 'ffmpeg'
    if not (ffmpeg / 'libavutil/avconfig.h').exists():
        run('./configure', '--disable-everything', '--disable-programs', '--disable-doc',
            '--disable-autodetect', '--disable-x86asm', cwd=ffmpeg)
    placebo = work / 'placebo/src/include'
    template = placebo / 'libplacebo/config.h.in'
    config = template.read_text().replace('@majorver@', '6').replace('@apiver@', '338')
    (placebo / 'libplacebo/config.h').write_text(config.replace('@extra_defs@', '#define PL_HAVE_OPENGL 1\n#define PL_HAVE_VULKAN 1'))
    ass_include = work / 'ass-include'
    ass_include.mkdir(exist_ok=True)
    if not (ass_include / 'ass').exists():
        (ass_include / 'ass').symlink_to(work / 'ass/libass')
    packages = {
        'libavcodec': ('61.3.100', 'libavcodec.61', ffmpeg),
        'libavfilter': ('10.1.100', 'libavfilter.10', ffmpeg),
        'libavformat': ('61.1.100', 'libavformat.61', ffmpeg),
        'libavutil': ('59.8.100', 'libavutil.59', ffmpeg),
        'libavdevice': ('61.1.100', 'libavdevice.61', ffmpeg),
        'libswscale': ('8.1.100', 'libswscale.8', ffmpeg),
        'libswresample': ('5.1.100', 'libswresample.5', ffmpeg),
        'libass': ('0.17.2', 'libass.9', ass_include),
        'libplacebo': ('6.338.2', 'libplacebo.338', placebo),
    }
    for package, formula, dylib, subdir in [
        ('libarchive', 'libarchive', 'libarchive.13', ''),
        ('libbluray', 'libbluray', 'libbluray.2', ''),
        ('mujs', 'mujs', 'libmujs', ''),
        ('luajit', 'luajit', 'libluajit-5.1.2', 'luajit-2.1'),
        ('lcms2', 'little-cms2', 'liblcms2.2', ''),
        ('uchardet', 'uchardet', 'libuchardet.0', 'uchardet'),
        ('rubberband', 'rubberband', 'librubberband.2', ''),
        ('vulkan', 'vulkan-loader', 'libvulkan.1', ''),
        ('zimg', 'zimg', 'libzimg.2', ''),
        ('libjpeg', 'jpeg-turbo', 'libjpeg.8', ''),
    ]:
        prefix = Path(output('brew', '--prefix', formula))
        version = output('pkg-config', '--modversion', str(prefix / f'lib/pkgconfig/{package}.pc'))
        include = prefix / 'include' / subdir
        if package == 'vulkan':
            include = Path(output('brew', '--prefix', 'vulkan-headers')) / 'include'
        packages[package] = (version, dylib, include)
    pc = work / ('pkgconfig-' + args.arch)
    pc.mkdir(exist_ok=True)
    for name, (version, dylib, include) in packages.items():
        library = runtime / (dylib + '.dylib')
        if library.read_bytes()[:7] == b'version':
            raise RuntimeError(f'Fetch Git LFS runtime first: {library}')
        (pc / (name + '.pc')).write_text(
            ('pl_has_vulkan=1\npl_has_opengl=1\n' if name == 'libplacebo' else '') +
            f'Name: {name}\nDescription: bundled {name}\nVersion: {version}\n'
            f'Libs: {library}\nCflags: -I{include}\n')
    env = os.environ.copy()
    env['PKG_CONFIG_LIBDIR'] = str(pc)
    env.pop('PKG_CONFIG_PATH', None)
    build = work / ('build-' + args.arch)
    swift_flags = (f'-I{placebo} -I{ffmpeg} -I{ass_include} '
                   f'-Xcc -include -Xcc {build}/config.h -target {args.arch}-apple-macosx12.0')
    command = ['meson', 'setup']
    if (build / 'build.ninja').exists():
        command += ['--reconfigure', '--clearcache']
    if args.arch != platform.machine():
        cross = work / (args.arch + '.ini')
        family = 'x86_64' if args.arch == 'x86_64' else 'aarch64'
        cross.write_text(
            "[binaries]\nc = ['clang', '-arch', '" + args.arch + "']\n"
            "cpp = ['clang++', '-arch', '" + args.arch + "']\n"
            "objc = ['clang', '-arch', '" + args.arch + "']\n"
            "objcpp = ['clang++', '-arch', '" + args.arch + "']\n"
            "pkg-config = '" + shutil.which('pkg-config') + "'\n"
            "[host_machine]\nsystem = 'darwin'\ncpu_family = '" + family + "'\n"
            "cpu = '" + args.arch + "'\nendian = 'little'\n"
            "[properties]\nneeds_exe_wrapper = true\n")
        command += ['--cross-file', str(cross)]
    command += [str(build), str(mpv), '-Dlibmpv=true', '-Dcplayer=false',
                '-Dbuild-date=false', '-Dgl=enabled', '-Dplain-gl=enabled',
                '-Dcoreaudio=enabled', '-Dcocoa=enabled', '-Dswift-build=enabled',
                '-Dvideotoolbox-gl=enabled', '-Dvideotoolbox-pl=enabled', '-Dvulkan=enabled',
                '-Dmacos-cocoa-cb=disabled', '-Dmacos-media-player=disabled',
                '-Dmacos-touchbar=disabled', '-Dbuildtype=release', '-Dlua=luajit',
                f'-Dswift-flags={swift_flags}',
                f'-Dc_args=-arch {args.arch} -mmacosx-version-min=12.0',
                f'-Dobjc_args=-arch {args.arch} -mmacosx-version-min=12.0',
                f'-Dobjc_link_args=-arch {args.arch} -mmacosx-version-min=12.0 -Wl,-rpath,@loader_path',
                f'-Dc_link_args=-arch {args.arch} -mmacosx-version-min=12.0 -Wl,-rpath,@loader_path']
    run(*command, env=env)
    run('ninja', '-C', str(build))
    result = work / ('output-' + args.arch) / 'libmpv.2.dylib'
    result.parent.mkdir(exist_ok=True)
    shutil.copy2(build / result.name, result)
    run('install_name_tool', '-id', '@rpath/libmpv.2.dylib', str(result))
    load_commands = output('otool', '-l', str(result))
    rpaths = re.findall(r'cmd LC_RPATH\n.*?\n\s*path (.*?) \(offset', load_commands)
    for path in rpaths:
        if path not in ('@loader_path', '/usr/lib/swift'):
            run('install_name_tool', '-delete_rpath', path, str(result))
    if '@loader_path' not in rpaths:
        run('install_name_tool', '-add_rpath', '@loader_path', str(result))
    minimums = re.findall(r'\bminos ([0-9.]+)', load_commands)
    if not minimums or any(tuple(map(int, v.split('.'))) > (12, 0) for v in minimums):
        raise RuntimeError(f'Unexpected minimum macOS version: {minimums}')
    for line in output('otool', '-L', str(result)).splitlines()[1:]:
        dependency = line.strip().split(' (')[0]
        if dependency.startswith('@rpath/'):
            if not (runtime / Path(dependency).name).exists():
                raise RuntimeError(f'Missing bundled dependency: {dependency}')
        elif not dependency.startswith(('/usr/lib/', '/System/Library/')):
            raise RuntimeError(f'Non-portable dependency: {dependency}')
    run('codesign', '--force', '--sign', '-', str(result))
    run('codesign', '--verify', '--strict', str(result))
    print(f'Built {result}')


if __name__ == '__main__':
    main()
