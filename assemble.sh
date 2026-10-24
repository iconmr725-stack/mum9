#!/bin/bash
# Puts the flat uploaded files into the right folders and writes the Gradle files. Run by the GitHub build.
set -e
P=build_project
rm -rf "$P"
J=$P/app/src/main/java/com/mumflorence/app
R=$P/app/src/main/res
mkdir -p "$J" "$R/values" "$R/xml" "$R/drawable-nodpi" "$R/mipmap-anydpi-v26" "$P/app/src/main/assets"
for f in *.kt; do cp "$f" "$J/"; done
cp AndroidManifest.xml "$P/app/src/main/"
cp strings.xml colors.xml "$R/values/"
cp accessibility_config.xml "$R/xml/"
for d in mdpi hdpi xhdpi xxhdpi xxxhdpi; do
  mkdir -p "$R/mipmap-$d"
  cp "ic_launcher_$d.png" "$R/mipmap-$d/ic_launcher.png"
  cp "ic_launcher_$d.png" "$R/mipmap-$d/ic_launcher_round.png"
done
cp ic_launcher_adaptive.xml "$R/mipmap-anydpi-v26/ic_launcher.xml"
cp ic_launcher_adaptive.xml "$R/mipmap-anydpi-v26/ic_launcher_round.xml"
cp ic_fg.png mom_photo.jpg "$R/drawable-nodpi/"
cp knowledge.txt "$P/app/src/main/assets/"

cat > "$P/settings.gradle.kts" <<'EOF'
pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "MumFlorence"
include(":app")
EOF
cat > "$P/build.gradle.kts" <<'EOF'
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
}
EOF
cat > "$P/gradle.properties" <<'EOF'
org.gradle.jvmargs=-Xmx3g
android.useAndroidX=true
android.nonTransitiveRClass=true
EOF
cat > "$P/app/build.gradle.kts" <<'EOF'
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.mumflorence.app"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.mumflorence.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint { abortOnError = false; checkReleaseBuilds = false }
    packaging { jniLibs { useLegacyPackaging = true } }
}
dependencies {
    implementation("net.java.dev.jna:jna:5.13.0@aar")
    implementation("com.alphacephei:vosk-android:0.3.45")
}
EOF
echo "Assembled into $P"
find "$P" -type f | sort
