#!/bin/bash

set -euo pipefail

JAVA_HOME_DEFAULT="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
export JAVA_HOME="${JAVA_HOME:-$JAVA_HOME_DEFAULT}"
export PATH="${JAVA_HOME}/bin:$PATH"

BUILD_VARIANT="${BUILD_VARIANT:-debug}"
GRADLE_TASK="assembleDebug"
APK_GLOB="app/build/outputs/apk/debug/*.apk"

if [ "${BUILD_VARIANT}" = "release" ]; then
    GRADLE_TASK="assembleRelease"
    APK_GLOB="app/build/outputs/apk/release/*.apk"
fi

echo "Building AirSync Android (${BUILD_VARIANT})..."
echo "Using JAVA_HOME=${JAVA_HOME}"

if [ ! -f "app/build.gradle.kts" ]; then
    echo "Android project not found"
    exit 1
fi

./gradlew "${GRADLE_TASK}"

mkdir -p ./release

APK_PATH="$(find ${APK_GLOB%/*} -name "$(basename "${APK_GLOB}")" -type f | head -1)"
if [ -z "${APK_PATH}" ]; then
    echo "Built APK not found"
    exit 1
fi

APK_NAME="AirSync-${BUILD_VARIANT}.apk"
cp "${APK_PATH}" "./release/${APK_NAME}"

echo "Packaged APK: ./release/${APK_NAME}"

grant_android_permissions() {
    local package_name="com.sameerasw.airsync"
    local listener_component="${package_name}/${package_name}.service.MediaNotificationListener"

    echo "Granting AirSync permissions via adb..."

    adb shell pm grant "${package_name}" android.permission.POST_NOTIFICATIONS >/dev/null 2>&1 || true
    adb shell pm grant "${package_name}" android.permission.READ_CALL_LOG >/dev/null 2>&1 || true
    adb shell pm grant "${package_name}" android.permission.READ_CONTACTS >/dev/null 2>&1 || true
    adb shell pm grant "${package_name}" android.permission.READ_PHONE_STATE >/dev/null 2>&1 || true
    adb shell pm grant "${package_name}" android.permission.CAMERA >/dev/null 2>&1 || true

    adb shell cmd appops set --uid "${package_name}" MANAGE_EXTERNAL_STORAGE allow >/dev/null 2>&1 || true
    adb shell cmd deviceidle whitelist +"${package_name}" >/dev/null 2>&1 || true
    adb shell cmd notification allow_listener "${listener_component}" >/dev/null 2>&1 || true

    local enabled_listeners
    enabled_listeners="$(adb shell settings get secure enabled_notification_listeners 2>/dev/null | tr -d '\r')"
    if [[ "${enabled_listeners}" != *"${listener_component}"* ]]; then
        if [ -n "${enabled_listeners}" ] && [ "${enabled_listeners}" != "null" ]; then
            adb shell settings put secure enabled_notification_listeners "${enabled_listeners}:${listener_component}" >/dev/null 2>&1 || true
        else
            adb shell settings put secure enabled_notification_listeners "${listener_component}" >/dev/null 2>&1 || true
        fi
    fi

    echo "ADB permission pass complete."
}

if adb get-state >/dev/null 2>&1; then
    adb install -r "./release/${APK_NAME}"
    grant_android_permissions
    echo "Installed ./release/${APK_NAME} via adb"
else
    echo "No adb device detected. Install manually from ./release/${APK_NAME}"
fi
