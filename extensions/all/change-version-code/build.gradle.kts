extension {
    name = "extensions/all/versioncode/change-version-code.mpe"
}

android {
    namespace = "app.morphe.extension"
    // Local build accommodation 2026-09-17: Google has not published
    // platforms;android-37 yet (build-tools 37.0.0 exists, platform does not).
    // This module only uses android.content.pm.PackageInfo / android.os.Build
    // (no API 37 symbols), so compiling against 36 is equivalent.
    // Revert to 37 once the platform is published upstream.
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    compileOnly(libs.annotation)
}
