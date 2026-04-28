import com.vanniktech.maven.publish.KotlinMultiplatform
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.library")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.vanniktech.maven.publish")
}

group = "io.github.jamal-wia"
version = "8.4.0"

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
        name.set("Paginator — KMP pagination library for Android, iOS & JVM")
        description.set(
            "Paginator is a pagination / paging library for Kotlin Multiplatform (Android, iOS, " +
                    "JVM, Desktop). A pure-Kotlin alternative to Jetpack Paging 3 with cursor-based " +
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
