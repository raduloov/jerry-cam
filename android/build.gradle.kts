plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.google.services) apply false
    // Newer Kotlin compiler for AGP's built-in Kotlin: Firebase and Credential libraries ship Kotlin 2.4 metadata.
    alias(libs.plugins.kotlin.android) apply false
}
