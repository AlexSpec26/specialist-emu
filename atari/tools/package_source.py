#!/usr/bin/env python3
"""Package corresponding source for the Atari APK, excluding unrelated products."""
import io
import subprocess
import sys
import tarfile
import tempfile
from pathlib import Path

output = Path(sys.argv[1]).resolve()
paths = ['atari', 'tools/AtariNativeTest.c', 'tools/AtariJniTest.java',
         'build.gradle', 'settings.gradle', 'gradle.properties', '.github/workflows/atari.yml']
archive = subprocess.check_output(['git', 'archive', '--format=tar', 'HEAD', *paths])
with tempfile.TemporaryDirectory() as temporary:
    source = Path(temporary) / 'atari-source'
    source.mkdir()
    with tarfile.open(fileobj=io.BytesIO(archive)) as stream:
        stream.extractall(source, filter='data')
    # Standalone source package: unrelated app/ and zx/ projects are not included.
    settings = source / 'settings.gradle'
    settings.write_text('\n'.join(line for line in settings.read_text().splitlines()
                                  if line.strip() not in ("include ':app'", "include ':zx'")) + '\n')
    (source / 'BUILDING.md').write_text('''# Atari APK corresponding source

This archive contains the exact application and vendored engine sources used for
this release. Only settings.gradle excludes unrelated SPEC/ZX projects.

Build with JDK 17, Gradle 8.11.1, Android SDK platform 35, NDK 27.2.12479018.
Set ANDROID_HOME to your SDK location, then run:

    gradle :atari:assembleDebug

For release signing set SIGNING_KEYSTORE, SIGNING_STORE_PASSWORD,
SIGNING_KEY_ALIAS, SIGNING_KEY_PASSWORD and run :atari:assembleRelease.
Without a signing key the release APK is unsigned. Never commit your key.

See atari/README.md, atari/COPYING, atari/THIRD_PARTY.md and the preserved
vendored source notices for licenses, tests and original upstream revision.
''')
    with tarfile.open(output, 'w:gz') as stream:
        stream.add(source, arcname='atari-source')
print(output.name)
