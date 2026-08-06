plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.omnillm.android.parserisolated"
    compileSdk = libs.versions.compileSdk.get().toInt()
    ndkVersion = libs.versions.ndk.get()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
}

dependencies {
    implementation(libs.kotlin.stdlib)
    implementation(libs.androidx.core.ktx)
    // No :data:*, no secrets, no engines — isolated parser stays narrow.
    implementation(project(":core:canonical"))
    implementation(project(":core:errors"))
    testImplementation(libs.junit)
}
