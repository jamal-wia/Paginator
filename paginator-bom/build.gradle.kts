import com.vanniktech.maven.publish.JavaPlatform

plugins {
    `java-platform`
    alias(libs.plugins.maven.publish)
}

group = providers.gradleProperty("paginator.group").get()
version = providers.gradleProperty("paginator.version").get()

// BOM declares versions for the rest of the suite. Constraints reference the same
// `paginator.version` property as the modules they pin, so the BOM and the artifacts it
// pins cannot drift — bumping `paginator.version` in gradle.properties updates every
// constraint in lockstep.
dependencies {
    constraints {
        api("${project.group}:paginator:${project.version}")
        api("${project.group}:paginator-compose:${project.version}")
        api("${project.group}:paginator-view:${project.version}")
    }
}

mavenPublishing {
    configure(JavaPlatform())

    publishToMavenCentral(automaticRelease = true)

    if (project.findProperty("signing.keyId") != null ||
        project.findProperty("signingInMemoryKey") != null ||
        System.getenv("ORG_GRADLE_PROJECT_signingInMemoryKey") != null
    ) {
        signAllPublications()
    }

    coordinates(group.toString(), "paginator-bom", version.toString())

    pom {
        name.set("Paginator BOM — Bill of Materials for the Paginator suite")
        description.set(
            "Bill of Materials (BOM) for the Paginator pagination library. Importing this " +
                    "platform via `implementation(platform(\"io.github.jamal-wia:paginator-bom:<version>\"))` " +
                    "lets consumers declare paginator, paginator-compose, and paginator-view without " +
                    "specifying versions individually — the BOM keeps them aligned and prevents the " +
                    "classpath from ending up with a mix of versions across artifacts."
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
                    "android",
                    "ios",
                    "jvm",
                    "pagination",
                    "paging",
                    "paginator",
                    "bom",
                    "bill-of-materials",
                    "version-catalog",
                    "platform"
                ).joinToString(", ")
            )
        )
    }
}
