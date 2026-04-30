import com.vanniktech.maven.publish.KotlinMultiplatform
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.library")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.vanniktech.maven.publish")
}

group = "io.github.jamal-wia"
version = "8.5.0"

mavenPublishing {
    configure(KotlinMultiplatform(javadocJar = com.vanniktech.maven.publish.JavadocJar.Empty()))

    publishToMavenCentral(automaticRelease = true)

    if (project.findProperty("signing.keyId") != null ||
        project.findProperty("signingInMemoryKey") != null ||
        System.getenv("ORG_GRADLE_PROJECT_signingInMemoryKey") != null
    ) {
        signAllPublications()
    }

    coordinates(group.toString(), "paginator-compose", version.toString())

    pom {
        name.set("Paginator Compose — Compose Multiplatform UI bindings for Paginator")
        description.set(
            "Compose Multiplatform integration for the Paginator pagination library. " +
                    "Provides idiomatic bindings between Paginator's PrefetchController and " +
                    "LazyListState / LazyGridState / LazyStaggeredGridState — auto-pagination on " +
                    "scroll without manual snapshotFlow wiring. UI structure is left fully to the " +
                    "user; this artifact only feeds scroll signals to the controller. Targets " +
                    "Android, iOS and JVM (Desktop)."
        )
        url.set("https://github.com/jamal-wia/Paginator")
        inceptionYear.set("2026")

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

        properties.set(
            mapOf(
                "project.tags" to listOf(
                    "kotlin",
                    "kotlin-multiplatform",
                    "kmp",
                    "compose",
                    "compose-multiplatform",
                    "jetpack-compose",
                    "android",
                    "ios",
                    "jvm",
                    "pagination",
                    "paging",
                    "paginator",
                    "lazycolumn",
                    "lazygrid",
                    "lazystaggeredgrid",
                    "infinite-scroll",
                    "prefetch"
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

    sourceSets {
        commonMain.dependencies {
            api(project(":paginator"))
            api(compose.runtime)
            api(compose.foundation)
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
        }
    }
}

android {
    namespace = "com.jamal_aliev.paginator.compose"
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
