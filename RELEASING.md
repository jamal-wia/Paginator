# Releasing a New Version

Step-by-step guide for publishing a new release of the library to Maven Central.

## Table of Contents

- [Prerequisites](#prerequisites)
- [Step 1 — Update the Version](#step-1--update-the-version)
- [Step 2 — Update README Installation Examples](#step-2--update-readme-installation-examples)
- [Step 3 — Commit and Push](#step-3--commit-and-push)
- [Step 4 — Create a GitHub Release](#step-4--create-a-github-release)
- [Step 5 — Monitor the Workflow](#step-5--monitor-the-workflow)
- [Step 6 — Verify on Maven Central](#step-6--verify-on-maven-central)
- [Manual Publishing (without GitHub Actions)](#manual-publishing-without-github-actions)
- [Troubleshooting](#troubleshooting)

---

## Prerequisites

Make sure the following **GitHub Secrets** are configured in the repository
(`Settings → Secrets and variables → Actions → Repository secrets`):

| Secret                   | Description                                                                    |
|--------------------------|--------------------------------------------------------------------------------|
| `MAVEN_CENTRAL_USERNAME` | Sonatype Central Portal token username                                         |
| `MAVEN_CENTRAL_PASSWORD` | Sonatype Central Portal token password                                         |
| `GPG_KEY_ID`             | Last 8 characters of your GPG key ID                                           |
| `GPG_KEY_PASSWORD`       | Passphrase for the GPG key                                                     |
| `GPG_KEY`                | Base64-encoded GPG private key (`gpg --export-secret-keys <KEY_ID> \| base64`) |

## Step 1 — Update the Version

In `paginator/build.gradle.kts`, change the `version` property:

```kotlin
version = "8.2.3" // ← new version
```

## Step 2 — Update README Installation Examples

Update the version in all `implementation(...)` snippets in the README
(sections **KMP**, **Android-only**, and **JVM**) to match the new version.

## Step 3 — Commit and Push

```bash
git add -A
git commit -m "Bump version to 8.2.3"
git push origin master
```

## Step 4 — Create a GitHub Release

1. Go to **[Releases → New release](https://github.com/jamal-wia/Paginator/releases/new)**
2. Click **"Choose a tag"** and type the new version (e.g. `8.2.3`), then select **"Create new tag
   on publish"**
3. Set **Release title** (e.g. `8.2.3`)
4. Describe the changes in the description
5. Click **"Publish release"**

This triggers the **`Publish to Maven Central`** GitHub Actions workflow automatically.

## Step 5 — Monitor the Workflow

1. Go to **[Actions](https://github.com/jamal-wia/Paginator/actions)** and open the running workflow
2. Wait for the workflow to complete successfully (green checkmark)

## Step 6 — Verify on Maven Central

1. Go to [Sonatype Central Portal](https://central.sonatype.com/) → **Deployments**
2. The deployment should appear with status **PUBLISHING** → **PUBLISHED**
3. Once **PUBLISHED**, the artifact is available at:
   ```
   io.github.jamal-wia:paginator:<version>
   ```
4. It may take **5–30 minutes** for the artifact to become resolvable via Gradle after status
   changes to PUBLISHED

## Manual Publishing (without GitHub Actions)

If you need to publish from your local machine instead of CI:

1. Add credentials to `~/.gradle/gradle.properties` (NOT the project's `gradle.properties`):
   ```properties
   mavenCentralUsername=<your-sonatype-token-username>
   mavenCentralPassword=<your-sonatype-token-password>
   signing.keyId=<last-8-chars-of-gpg-key>
   signing.password=<gpg-key-passphrase>
   signing.secretKeyRingFile=<path-to-secring.gpg>
   ```

2. Run:
   ```bash
   ./gradlew :paginator:publishAndReleaseToMavenCentral --no-configuration-cache
   ```

3. The `automaticRelease = true` flag in `build.gradle.kts` ensures the deployment is
   automatically released after validation. No manual "Release" button click is needed.

## Troubleshooting

| Problem                                 | Solution                                                                                                                      |
|-----------------------------------------|-------------------------------------------------------------------------------------------------------------------------------|
| Workflow fails at "Import GPG key"      | Re-export and update the `GPG_KEY` secret: `gpg --export-secret-keys <KEY_ID> \| base64`                                      |
| Deployment stuck at **VALIDATED**       | `automaticRelease = true` was not set. Either click "Release" manually in the Central Portal, or re-run with the flag enabled |
| Deployment **FAILED**                   | Check the Central Portal for validation errors (missing POM fields, signature issues, etc.)                                   |
| Artifact not resolvable after PUBLISHED | Wait up to 30 minutes. Maven Central syncing can be slow                                                                      |
| Signing error locally                   | Ensure `signing.secretKeyRingFile` points to a valid `.gpg` file and passphrase is correct                                    |
