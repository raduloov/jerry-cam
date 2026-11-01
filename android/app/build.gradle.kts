import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.google.services)
}

fun properties(name: String) = Properties().apply {
    rootProject.file(name).takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
val local = properties("local.properties")
val keystore = properties("keystore.properties")

android {
    namespace = "com.yavorradulov.jerrycam"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.yavorradulov.jerrycam"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        buildConfigField("String", "TURN_KEY_ID", "\"${local.getProperty("turn.keyId", "")}\"")
        buildConfigField("String", "TURN_API_TOKEN", "\"${local.getProperty("turn.apiToken", "")}\"")
    }

    signingConfigs {
        create("release") {
            storeFile = keystore.getProperty("storeFile")?.let { file(it) }
            storePassword = keystore.getProperty("storePassword")
            keyAlias = keystore.getProperty("keyAlias")
            keyPassword = keystore.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    testOptions {
        // android.util.Log calls in production code return defaults instead of throwing in JVM tests.
        unitTests.isReturnDefaultValues = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.database)
    implementation(libs.credentials)
    implementation(libs.credentials.play.services.auth)
    implementation(libs.googleid)
    implementation(libs.coroutines.android)
    implementation(libs.coroutines.play.services)
    implementation(libs.lifecycle.service)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.activity.ktx)
    implementation(libs.fragment)
    implementation(libs.core.ktx)
    implementation(libs.webrtc)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.json)
}
