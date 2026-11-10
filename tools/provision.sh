#!/usr/bin/env bash
# Installs the toolchain needed to build the APK and run the vision scripts.
# Everything lands in /home/user/tools (outside the repo, so it is never committed).
#
#   bash tools/provision.sh
#
# Sources (only GitHub/npm/PyPI are reachable in this sandbox):
#   JDK 17 + Android build-tools/platform  -> github.com/whynusn/android-sdk-bundle
#   Kotlin compiler                        -> npm registry (package "kotlin-compiler")
#   Python deps                            -> PyPI
set -euo pipefail
TOOLS="${TOOLS:-/home/user/tools}"
mkdir -p "$TOOLS"
cd "$TOOLS"

if [ ! -d jdk-17.0.20.1+1 ]; then
  echo "==> JDK"
  for i in 00 01 02; do
    [ -s "p$i" ] || curl -sSL -o "p$i" \
      "https://api.github.com/repos/whynusn/android-sdk-bundle/contents/OpenJDK17.tar.gz.part-$i" \
      -H "Accept: application/vnd.github.raw"
  done
  cat p00 p01 p02 > jdk.tar.gz && tar xzf jdk.tar.gz && rm -f p00 p01 p02 jdk.tar.gz
fi

if [ ! -f android-sdk/platforms/android-34/android.jar ]; then
  echo "==> Android SDK"
  mkdir -p android-sdk
  for f in build-tools_r34-linux.zip platform-34_r03.zip; do
    [ -s "$f" ] || curl -sSL -o "$f" \
      "https://api.github.com/repos/whynusn/android-sdk-bundle/contents/$f" -H "Accept: application/vnd.github.raw"
  done
  rm -rf android-sdk/work && mkdir -p android-sdk/work
  (cd android-sdk/work && unzip -q ../../build-tools_r34-linux.zip && unzip -q ../../platform-34_r03.zip)
  mkdir -p android-sdk/build-tools/34.0.0 android-sdk/platforms
  mv android-sdk/work/android-14/* android-sdk/build-tools/34.0.0/
  mv android-sdk/work/android-34 android-sdk/platforms/
  rm -rf android-sdk/work build-tools_r34-linux.zip platform-34_r03.zip
  chmod +x android-sdk/build-tools/34.0.0/*
fi

if [ ! -x kotlinc/bin/kotlinc ]; then
  echo "==> Kotlin compiler"
  curl -sSL -o kc.tgz "https://registry.npmjs.org/kotlin-compiler/-/kotlin-compiler-2.4.21.tgz"
  mkdir -p kotlinc && tar xzf kc.tgz -C kotlinc --strip-components=1 && rm kc.tgz
  chmod +x kotlinc/bin/*
fi

echo "==> Python deps"
python3 -m pip install --quiet --break-system-packages pillow numpy pymupdf fonttools
echo
echo "done: JAVA_HOME=$TOOLS/jdk-17.0.20.1+1  SDK=$TOOLS/android-sdk  KOTLINC=$TOOLS/kotlinc/bin/kotlinc"
