import com.vanniktech.maven.publish.AndroidSingleVariantLibrary

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("com.vanniktech.maven.publish")
}

group = "io.github.jamal-wia"
version = "8.5.0"

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

    coordinates(group.toString(), "paginator-view", version.toString())

    pom {
        name.set("Paginator View — Android View bindings for Paginator")
        description.set(
            "Android View integration for the Paginator pagination library. Provides idiomatic " +
                    "bindings between Paginator's PrefetchController and RecyclerView " +
                    "(LinearLayoutManager / GridLayoutManager / StaggeredGridLayoutManager) — " +
                    "auto-pagination on scroll without manual OnScrollListener wiring. Adapter is " +
                    "left fully to the user; this artifact only feeds scroll signals to the " +
                    "controller. Android-only."
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
                    "android",
                    "android-view",
                    "recyclerview",
                    "linearlayoutmanager",
                    "gridlayoutmanager",
                    "staggeredgridlayoutmanager",
                    "pagination",
                    "paging",
                    "paginator",
                    "infinite-scroll",
                    "prefetch"
                ).joinToString(", ")
            )
        )
    }
}

android {
    namespace = "com.jamal_aliev.paginator.view"
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
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        }
    }
}

dependencies {
    api(project(":paginator"))
    api("androidx.recyclerview:recyclerview:1.4.0")
    api("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
}
