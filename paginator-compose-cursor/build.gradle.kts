import com.vanniktech.maven.publish.KotlinMultiplatform
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
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

    coordinates(group.toString(), "paginator-compose-cursor", version.toString())

    pom {
        name.set("Paginator Compose Cursor — Compose Multiplatform bindings for the cursor paginator")
        description.set(
            "Compose Multiplatform integration for the cursor variant of the Paginator pagination " +
                    "library. Provides idiomatic bindings between CursorPaginatorPrefetchController and " +
                    "LazyListState / LazyGridState / LazyStaggeredGridState — auto-pagination on scroll " +
                    "without manual snapshotFlow wiring. Pair with paginator-cursor; for page-number " +
                    "feeds use paginator-compose-offset instead. Targets Android, iOS, JVM, JS, Wasm."
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

    // iosX64 is intentionally omitted: Compose Multiplatform 1.11.0+ no longer publishes
    // an iosX64 variant (Apple deprecated Intel-Mac simulators). Apple-Silicon Macs use
    // iosSimulatorArm64. Consumers with iosX64 targets can still depend on :paginator-cursor
    // (no Compose dependency), but the Compose binding follows compose-multiplatform's own
    // target list.
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
            api(project(":paginator-cursor"))
            api(project(":paginator-compose-core"))
            api(libs.compose.multiplatform.runtime)
            api(libs.compose.multiplatform.foundation)
            api(libs.compose.multiplatform.lifecycle.runtime.compose)
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

android {
    namespace = "com.jamal_aliev.paginator.compose.cursor"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
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
