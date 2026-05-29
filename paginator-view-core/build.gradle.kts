import com.vanniktech.maven.publish.AndroidSingleVariantLibrary

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.maven.publish)
}

group = providers.gradleProperty("paginator.group").get()
version = providers.gradleProperty("paginator.version").get()

mavenPublishing {
    configure(
        AndroidSingleVariantLibrary(
            variant = "release",
            sourcesJar = true,
            publishJavadocJar = false,
        )
    )

    publishToMavenCentral(automaticRelease = true)

    if (project.findProperty("signing.keyId") != null ||
        project.findProperty("signingInMemoryKey") != null ||
        System.getenv("ORG_GRADLE_PROJECT_signingInMemoryKey") != null
    ) {
        signAllPublications()
    }

    coordinates(group.toString(), "paginator-view-core", version.toString())

    pom {
        name.set("Paginator View Core — shared Android View utilities for Paginator")
        description.set(
            "Android View utilities shared between paginator-view-offset and " +
                    "paginator-view-cursor. Contains the scroll-position preservation primitives " +
                    "(RecyclerViewScrollSnapshotRegistry, attachScrollPreservation helper) that back " +
                    "the `preserveScroll = true` parameter on bindToRecyclerView, plus the shared " +
                    "binding primitives (ScrollBinding, ScrollDispatcher, PrefetchErrorChannel). " +
                    "Depends only on paginator-core (for the ScrollWindow index-remapping math); " +
                    "independent of paginator-offset and paginator-cursor."
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

android {
    namespace = "com.jamal_aliev.paginator.view.core"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
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
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        }
    }
}

dependencies {
    implementation(project(":paginator-core"))
    api(libs.androidx.recyclerview)
    api(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
}
