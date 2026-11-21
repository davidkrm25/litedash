plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.irvingguerra.litedash.feature.fuel"
    compileSdk = 37
    defaultConfig { minSdk = 23 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // Https: la descarga de los precios.
    implementation(project(":core:ui"))
    testImplementation(libs.junit)
    // org.json de verdad en las pruebas (en Android viene en el sistema).
    testImplementation(libs.json)
}
