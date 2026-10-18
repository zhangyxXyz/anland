"""Build an isolated device probe from the production Binder bridge sources.

Requires ANDROID_HOME and JAVA_HOME. Does not install or change the device.
The output APK is also the root helper's CLASSPATH for testing publication to
com.anland.bridgeprobe.binder. No production APK signing key is used.
"""
import os
from pathlib import Path
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'outputs/binder-bridge-probe'
SDK = Path(os.environ['ANDROID_HOME'])
JAVA = Path(os.environ['JAVA_HOME']) / 'bin'
BT = SDK / 'build-tools/36.0.0'
PLATFORM = SDK / 'platforms/android-36/android.jar'
EXE = '.exe' if os.name == 'nt' else ''
BAT = '.bat' if os.name == 'nt' else ''


def run(*args):
    subprocess.run([str(arg) for arg in args], check=True)


OUT.mkdir(parents=True, exist_ok=True)
classes = OUT / 'classes'
classes.mkdir(exist_ok=True)
sources = [ROOT / 'app/src/main/java/com/anlandnext' / name for name in
           ('HostBinderProvider.java', 'RootBinderBridge.java')]
sources += [ROOT / 'app/libawl/src/main/java/com/anlandnext/awl/HostConnection.java',
            ROOT / 'tests/binder_bridge/BridgeProbe.java']
run(JAVA / ('javac' + EXE), '-source', '17', '-target', '17', '-classpath', PLATFORM,
    '-d', classes, *sources)
run(JAVA / ('jar' + EXE), 'cf', OUT / 'classes.jar', '-C', classes, '.')
run(BT / ('d8' + BAT), '--lib', PLATFORM, '--min-api', '29',
    '--output', OUT, OUT / 'classes.jar')
unsigned = OUT / 'probe-unsigned.apk'
run(BT / ('aapt2' + EXE), 'link', '-I', PLATFORM,
    '--manifest', ROOT / 'tests/binder_bridge/AndroidManifest.xml', '-o', unsigned)
with zipfile.ZipFile(unsigned, 'a') as archive:
    archive.write(OUT / 'classes.dex', 'classes.dex')
key = OUT / 'probe.keystore'
if not key.exists():
    run(JAVA / ('keytool' + EXE), '-genkeypair', '-keystore', key,
        '-storepass', 'android', '-keypass', 'android', '-alias', 'probe',
        '-dname', 'CN=Disposable Anland Probe', '-keyalg', 'RSA', '-validity', '3650')
run(BT / ('zipalign' + EXE), '-f', '4', unsigned, OUT / 'probe-aligned.apk')
run(BT / ('apksigner' + BAT), 'sign', '--ks', key, '--ks-pass', 'pass:android',
    '--out', OUT / 'probe.apk', OUT / 'probe-aligned.apk')
print(OUT / 'probe.apk')
