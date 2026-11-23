plugins {
    alias(libs.plugins.android.application)
}

// GPS del teléfono: manda la ubicación por UDP a la tableta (conectada al hotspot del teléfono). Va aparte de LiteDash.
android {
    namespace = "dev.davidkrm.gpsphone"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.davidkrm.gpsphone"
        minSdk = 23
        targetSdk = 35
        versionCode = 2
        versionName = "1.1"
    }
}
