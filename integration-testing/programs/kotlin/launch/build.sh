#!/usr/bin/env bash
# Builds the Kotlin interop programs (installDist) against the Kotlin SDK checkout in KOTLIN_SDK, with
# that checkout's own Gradle wrapper and a composite build (settings.gradle.kts). Skips Gradle when
# neither the sources nor the SDK commit changed since the last build.
# Launcher contract: integration-testing/README.md, "Contracts". Env: KOTLIN_SDK.
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
sdk="$(cd "${KOTLIN_SDK:?KOTLIN_SDK is not set}" && pwd)"
stamp="$here/build/.kt-peer-stamp"
key="$( { git -C "$sdk" rev-parse HEAD 2>/dev/null || echo "$sdk"; echo "$sdk";
          cd "$here" && find settings.gradle.kts build.gradle.kts gradle.properties src -type f -print0 \
            | sort -z | xargs -0 sha256sum; } | sha256sum | cut -d' ' -f1)"
if [[ -x "$here/build/install/kt-peer/bin/kt-peer" && -f "$stamp" && "$(cat "$stamp")" == "$key" ]]; then
    exit 0
fi
# A failed build must not leave the previous programs behind to run.
rm -rf "$here/build/install" "$stamp"
# The SDK's Gradle toolchain is JDK 21: let Gradle also find one that setup-java exported (CI).
"$sdk/gradlew" -p "$here" --quiet --console=plain -PkotlinSdk="$sdk" \
    -Porg.gradle.java.installations.fromEnv=JAVA_HOME_21_X64,JAVA_HOME_21_ARM64 installDist
echo "$key" > "$stamp"
