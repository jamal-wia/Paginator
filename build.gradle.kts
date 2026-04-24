// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    id("com.android.application") version "9.1.1" apply false
    id("com.android.library") version "9.1.1" apply false
    id("org.jetbrains.kotlin.android") version "2.3.20" apply false
    id("org.jetbrains.kotlin.multiplatform") version "2.3.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.20"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.3.20" apply false
    id("com.vanniktech.maven.publish") version "0.36.0" apply false
}