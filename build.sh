#!/usr/bin/env bash
# Compila lo Store MG4 senza Gradle: testi -> aapt2 -> javac -> D8 -> firma (stessa chiave platform del launcher).
# Uso: ./build.sh
# Serve tools/app-key.txt con la chiave dello store (non inclusa nel repository): senza la chiave
# giusta l'app compila, ma il catalogo non risponde.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
T="$ROOT/tools"
APP="$ROOT/app"
OUT="$ROOT/build"
ANDROID_JAR="$T/android.jar"
KEYSTORE="${KEYSTORE:-$ROOT/keys/platform.p12}"
KEY_PASS="${KEY_PASS:-android}"
KEY_ALIAS="${KEY_ALIAS:-platform}"

source "$ROOT/version.properties"   # VERSION_CODE, VERSION_NAME
OUT_NAME="MG4Store-$VERSION_NAME.apk"

rm -rf "$OUT"
mkdir -p "$OUT/res" "$OUT/gen" "$OUT/classes" "$OUT/dex" "$OUT/tools"

echo "==> testi (8 lingue)"
python3 "$T/i18n.py"

echo "==> risorse (aapt2)"
"$T/aapt2" compile --dir "$APP/res" -o "$OUT/res/compiled.zip"
"$T/aapt2" link -I "$ANDROID_JAR" --manifest "$APP/AndroidManifest.xml" \
  --min-sdk-version 26 --target-sdk-version 28 \
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
  --java "$OUT/gen" -o "$OUT/base.apk" "$OUT/res/compiled.zip"

echo "==> chiave dello store e versione"
# La chiave condivisa con il Worker sta in tools/app-key.txt (esclusa da git); nell'APK e' offuscata con XOR.
python3 - "$T/app-key.txt" "$OUT/gen/com/mg4/store" "$VERSION_NAME" <<'PY'
import os, random, re, sys
src, dst, ver = sys.argv[1], sys.argv[2], sys.argv[3]
key = re.sub(r"[^A-Za-z0-9]", "", open(src).read()) if os.path.exists(src) else ""
if not key:
    sys.exit("manca tools/app-key.txt (la chiave APP_KEY del Worker)")
x = [random.randrange(1, 255) for _ in key]
enc = [ord(c) ^ x[i] for i, c in enumerate(key)]
os.makedirs(dst, exist_ok=True)
open(os.path.join(dst, "AppKey.java"), "w").write(
    "package com.mg4.store;\n\n/** Generato da build.sh: non modificare, non salvare in git. */\n"
    "final class AppKey {\n    private AppKey() {}\n"
    "    private static final int[] A = {%s};\n    private static final int[] B = {%s};\n"
    "    static String get() {\n        char[] c = new char[A.length];\n"
    "        for (int i = 0; i < A.length; i++) c[i] = (char) (A[i] ^ B[i]);\n        return new String(c);\n    }\n}\n"
    % (",".join(map(str, enc)), ",".join(map(str, x))))
open(os.path.join(dst, "BuildInfo.java"), "w").write(
    "package com.mg4.store;\n\n/** Generato da build.sh. */\nfinal class BuildInfo {\n    private BuildInfo() {}\n"
    "    static final String VERSION = \"%s\";\n}\n" % ver)
print("    chiave: presente")
PY

echo "==> java (javac, bytecode 8)"
find "$APP/java" "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
if ! javac -nowarn -encoding UTF-8 --release 8 -classpath "$ANDROID_JAR" \
     -d "$OUT/classes" @"$OUT/sources.txt" > "$OUT/javac.log" 2>&1; then
  grep -v 'Picked up JAVA_TOOL_OPTIONS' "$OUT/javac.log"
  echo "compilazione fallita"; exit 1
fi
grep -v -E '^(Picked up JAVA_TOOL_OPTIONS|Note: )' "$OUT/javac.log" || true

echo "==> dex (D8)"
find "$OUT/classes" -name '*.class' > "$OUT/classes.txt"
java -cp "$T/d8.jar" com.android.tools.r8.D8 --release --min-api 26 \
  --lib "$ANDROID_JAR" --output "$OUT/dex" @"$OUT/classes.txt" 2>&1 | grep -v 'Picked up JAVA_TOOL_OPTIONS' || true
test -f "$OUT/dex/classes.dex" || { echo "D8 fallito"; exit 1; }

echo "==> pacchetto"
cp "$OUT/base.apk" "$OUT/unsigned.apk"
(cd "$OUT/dex" && zip -q -X "$OUT/unsigned.apk" classes*.dex)

echo "==> firma"
javac -nowarn -d "$OUT/tools" -cp "$T/d8.jar" "$T/src/Sign.java" 2>&1 | grep -v 'Picked up' || true
java -cp "$OUT/tools:$T/d8.jar" Sign "$KEYSTORE" "$KEY_PASS" "$KEY_ALIAS" \
  "$OUT/unsigned.apk" "$OUT/$OUT_NAME" 2>&1 | grep -v 'Picked up' || true
test -f "$OUT/$OUT_NAME"
ls -la "$OUT/$OUT_NAME"
