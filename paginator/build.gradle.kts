import com.vanniktech.maven.publish.KotlinMultiplatform
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.maven.publish)
}

group = providers.gradleProperty("paginator.group").get()
version = providers.gradleProperty("paginator.version").get()

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
        name.set("Paginator — KMP pagination library for Android, iOS, JVM & Web")
        description.set(
            "Paginator is a pagination / paging library for Kotlin Multiplatform (Android, iOS, " +
                    "JVM, Desktop, JS, Wasm). A pure-Kotlin alternative to Jetpack Paging 3 with cursor-based " +
                    "pagination, bidirectional scroll (chat / messenger feeds), jump-to-page, " +
                    "bookmarks, pluggable page caching (LRU / FIFO / TTL), element-level CRUD, " +
                    "infinite scroll, prefetch, state serialization via kotlinx.serialization, and " +
                    "reactive UI state via Kotlin Flows. Zero platform dependencies — usable across " +
                    "data, domain and presentation layers in Clean Architecture."
        )
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

        issueManagement {
            system.set("GitHub Issues")
            url.set("https://github.com/jamal-wia/Paginator/issues")
        }

        ciManagement {
            system.set("GitHub Actions")
            url.set("https://github.com/jamal-wia/Paginator/actions")
        }

        // Free-form properties — surfaced on Maven Central / MvnRepository search pages
        // and improve discoverability for long-tail queries.
        properties.set(
            mapOf(
                "project.tags" to listOf(
                    "kotlin",
                    "kotlin-multiplatform",
                    "kmp",
                    "android",
                    "ios",
                    "jvm",
                    "js",
                    "wasm",
                    "web",
                    "pagination",
                    "paging",
                    "paginator",
                    "cursor-pagination",
                    "infinite-scroll",
                    "bidirectional-pagination",
                    "jetpack-paging-alternative",
                    "coroutines",
                    "flow",
                    "clean-architecture",
                    "messenger",
                    "chat-feed",
                    "graphql-connections"
                ).joinToString(", ")
            )
        )
    }
}

kotlin {
    androidTarget {
        publishLibraryVariants("release", "debug")
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    iosX64()
    iosArm64()
    iosSimulatorArm64()

    js(IR) {
        nodejs()
    }

    wasmJs {
        nodejs()
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.atomicfu)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
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
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
