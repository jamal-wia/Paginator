import com.vanniktech.maven.publish.KotlinMultiplatform
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.library")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.vanniktech.maven.publish")
}

group = "io.github.jamal-wia"
version = "8.2.0"

mavenPublishing {
    configure(KotlinMultiplatform(javadocJar = com.vanniktech.maven.publish.JavadocJar.Empty()))

    publishToMavenCentral(automaticRelease = true)

    if (project.findProperty("signing.keyId") != null ||
        project.findProperty("signingInMemoryKey") != null ||
        System.getenv("ORG_GRADLE_PROJECT_signingInMemoryKey") != null
    ) {
        signAllPublications()
    }

    coordinates(group.toString(), "paginator", version.toString())

    pom {
        name.set("Paginator")
        description.set("A powerful, flexible pagination library for Kotlin Multiplatform (KMP) with bidirectional navigation, bookmarks, CRUD, caching, and reactive state via Kotlin Flows.")
        url.set("https://github.com/jamal-wia/Paginator")
        inceptionYear.set("2023")

        licenses {
            license {
                name.set("MIT License")
                url.set("https://opensource.org/licenses/MIT")
                distribution.set("repo")
            }
        }

        developers {
            developer {
                id.set("jamal-wia")
                name.set("Jamal Aliev")
                url.set("https://github.com/jamal-wia")
            }
        }

        scm {
            url.set("https://github.com/jamal-wia/Paginator")
            connection.set("scm:git:git://github.com/jamal-wia/Paginator.git")
            developerConnection.set("scm:git:ssh://git@github.com/jamal-wia/Paginator.git")
        }
    }
}

kotlin {
    androidTarget {
        publishLibraryVariants("release", "debug")
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_1_8)
        }
    }

    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_1_8)
        }
    }

    iosX64()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            implementation("org.jetbrains.kotlinx:atomicfu:0.32.1")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
        }
    }
}

android {
    namespace = "com.jamal_aliev.paginator"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}
