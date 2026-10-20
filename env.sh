# Toolchain environment for Wplayer builds: source ./env.sh
# Defaults match a macOS setup with Homebrew (openjdk@21, android-commandlinetools);
# variables already set in your environment win.
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home}"
export ANDROID_HOME="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
export ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-$ANDROID_HOME/ndk/30.0.16248370}"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
