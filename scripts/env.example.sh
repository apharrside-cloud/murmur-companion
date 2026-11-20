#!/bin/bash
# Example build environment. Copy to scripts/env.sh (git-ignored) and adjust, then: source scripts/env.sh
export JAVA_HOME=/path/to/jdk17
export ANDROID_HOME=/path/to/android-sdk
export ANDROID_SDK_ROOT=$ANDROID_HOME
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
echo "sdk.dir=$ANDROID_HOME" > "$ROOT/local.properties"
# export MN_BUILD_DIR=/somewhere/else      # optional: build output elsewhere
# export MN_KEYSTORE_PROPS=/path/ks.props  # optional: release signing (storeFile, storePassword, keyAlias, keyPassword)
