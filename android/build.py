# SPDX-License-Identifier: AGPL-3.0-only
"""Build the Miuix APK. First run downloads Gradle/Maven dependencies.

Requires existing JDK 17+ and Android SDK. Does not install or operate a phone.
"""
import os
from pathlib import Path
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'artifacts' / 'simlocation-android'
SOURCES = ROOT / 'android' / 'app' / 'src' / 'main'


def run(*args, env=None):
    subprocess.run([str(arg) for arg in args], check=True, env=env)


def main():
    sdk = Path(os.environ.get('ANDROID_SDK_ROOT') or os.environ.get('ANDROID_HOME')
               or Path.home() / 'AppData' / 'Local' / 'Android' / 'Sdk')
    if not (sdk / 'platforms' / 'android-35' / 'android.jar').exists():
        raise SystemExit('Install Android SDK platform 35 first; set ANDROID_HOME if needed.')
    executables = {name: shutil.which(name) for name in ('java', 'javac', 'keytool')}
    if not all(executables.values()):
        raise SystemExit('JDK 17+ with java, javac and keytool must be on PATH.')
    OUT.mkdir(parents=True, exist_ok=True)
    tests = OUT / 'route-tests'
    tests.mkdir(exist_ok=True)
    run(executables['javac'], '--release', '17', '-encoding', 'UTF-8', '-d', tests,
        SOURCES / 'java/org/ethertaco/simlocation/Route.java',
        SOURCES / 'java/org/ethertaco/simlocation/Playback.java',
        SOURCES / 'java/org/ethertaco/simlocation/MotionProfile.java', SOURCES / 'java/org/ethertaco/simlocation/Trajectory.java',
        ROOT / 'tests/android/RouteTest.java', ROOT / 'tests/android/PlaybackTest.java', ROOT / 'tests/android/MotionTest.java')
    for name in ('RouteTest', 'PlaybackTest', 'MotionTest'):
        run(executables['java'], '-cp', tests, 'org.ethertaco.simlocation.' + name)
    key = OUT / 'debug.keystore'
    if not key.exists():
        run(executables['keytool'], '-genkeypair', '-keystore', key,
            '-storepass', 'android', '-keypass', 'android', '-alias', 'simlocation-debug',
            '-dname', 'CN=Kanisuko SimLocation Development', '-keyalg', 'RSA', '-validity', '3650')
    env = dict(os.environ, ANDROID_HOME=str(sdk))
    wrapper = ROOT / 'android' / ('gradlew.bat' if os.name == 'nt' else 'gradlew')
    run(wrapper, '-p', ROOT / 'android', ':app:assembleDebug', '--console=plain', env=env)
    apk = OUT / 'SimLocation-root-debug.apk'
    shutil.copy2(ROOT / 'android/app/build/outputs/apk/debug/app-debug.apk', apk)
    versions = sorted((p for p in (sdk / 'build-tools').iterdir()
                       if p.is_dir() and all(n.isdigit() for n in p.name.split('.'))),
                      key=lambda p: tuple(int(n) for n in p.name.split('.')))
    run(executables['java'], '-jar', versions[-1] / 'lib/apksigner.jar', 'verify', '--verbose', apk)
    print(f'Built: {apk}')


if __name__ == '__main__':
    main()
