plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.omnillm.android.workers"
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
    implementation(libs.kotlinx.coroutines.android)
    // Canonical errors / results only — never :data:persistence or token stores.
    implementation(project(":core:canonical"))
    implementation(project(":core:errors"))
    // Crash-contained workers may load packaged native engines (INV-001: not UI).
    // libomnillm_llama.so packaged here for worker process placement paths.
    implementation(project(":android:native"))
    implementation(project(":engines:llama-cpp"))
    testImplementation(libs.junit)
}
