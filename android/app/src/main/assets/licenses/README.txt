MURF embedded local mode — native components (shipped as lib*.so in the APK)

libproot.so, libproot-loader.so, libproot-loader32.so
  PRoot (Termux fork) — GPL-2.0-or-later — https://github.com/termux/proot
  Source: commit 52d65a4d (2026-11-01), built unmodified by android/native/build-proot.sh
  in https://github.com/winnifredbalistreri60-prog/murf (flags: PROOT_UNBUNDLE_LOADER,
  16 KB page alignment). Full license text: proot-GPL-2.0.txt.
  The corresponding source code is available at the URLs above; on request we will
  provide it for at least three years.

talloc 2.5.0 (statically linked into libproot.so)
  LGPL-3.0-or-later — https://talloc.samba.org — https://www.samba.org/ftp/talloc/talloc-2.5.0.tar.gz
  sha256 912afa237510ae542a7733998eb18a12bcda35ab6729c8e2ddb43e8d0ebab007
  Full license text: talloc-LGPL-3.0.txt. You may relink libproot.so against a
  modified talloc using build-proot.sh (TALLOC_VERSION / sources in android/native/).

These programs are separate executables started by MURF via exec; MURF itself is MIT.
The Ubuntu root filesystem is NOT bundled: it is downloaded from cdimage.ubuntu.com on
first run (ubuntu-base 24.04, each package under its own license).
