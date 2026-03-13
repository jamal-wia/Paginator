// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    id("com.android.application") version "8.13.1" apply false
    id("com.android.library") version "8.13.1" apply false
    id("org.jetbrains.kotlin.android") version "2.3.10" apply false
    id("org.jetbrains.kotlin.multiplatform") version "2.3.10" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.10"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.3.10" apply false
    id("com.vanniktech.maven.publish") version "0.30.0" apply false
}