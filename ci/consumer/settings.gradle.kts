// A separate build that integrates com.trustsy:trustsy-verify the way an app
// does: from the published repository ONLY, never `project(":trustsy-verify")`.
// It fails when the published artifact is not consumable — a dependency in the
// wrong POM scope, a transitive dependency no public repository serves, R8
// rules an app would need but the AAR does not carry. See README "Distribute".
//
//   cd sdk/android
//   ./gradlew :trustsy-verify:publishReleasePublicationToTrustsyRepository
//   ./gradlew -p ci/consumer assembleRelease
//   ./gradlew -p ci/consumer assembleRelease -PtrustsyPomOnly
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

// The artifact carries Gradle module metadata AND a POM. Gradle prefers the
// .module file, so a broken POM — what Maven and every non-Gradle consumer
// reads — would hide behind a valid one. -PtrustsyPomOnly resolves the trustsy
// repository from the POM alone.
val pomOnly = providers.gradleProperty("trustsyPomOnly").isPresent

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven {
            name = "trustsy"
            url = uri(rootDir.resolve("../../build/repository"))
            content { includeGroup("com.trustsy") }
            if (pomOnly) {
                metadataSources {
                    mavenPom()
                    artifact()
                    ignoreGradleMetadataRedirection()
                }
            }
        }
        // What the producer resolves from; nothing else. No mavenLocal: a
        // stale local copy would stand in for the artifact under test.
        google()
        mavenCentral()
    }
}

rootProject.name = "trustsy-verify-consumer"
include(":app")
