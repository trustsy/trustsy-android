// Same plugin versions as the producer (../../build.gradle.kts).
plugins {
    id("com.android.application") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.2.21" apply false
}

// The version this tree last PUBLISHED, read from the repository's own
// metadata, so the check never pins a version that silently goes stale.
val trustsyVerifyVersion by extra(
    rootDir.resolve("../../build/repository/com/trustsy/trustsy-verify/maven-metadata.xml")
        .takeIf { it.isFile }
        ?.readText()
        ?.let { Regex("<release>([^<]+)</release>").find(it)?.groupValues?.get(1) }
        ?: error(
            "nothing published: run `./gradlew :trustsy-verify:publishReleasePublicationToTrustsyRepository` " +
                "in sdk/android first",
        ),
)
