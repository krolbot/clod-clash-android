import groovy.json.JsonOutput
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

plugins {
    kotlin("android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.android.application")
    id("kotlinx-serialization")
}

android {
    buildFeatures {
        compose = true
    }

    defaultConfig {
        val sentryDsn = System.getenv("SENTRY_DSN").orEmpty()
        val sentryRelease = System.getenv("SENTRY_RELEASE").orEmpty()
        buildConfigField("String", "SENTRY_DSN", JsonOutput.toJson(sentryDsn))
        buildConfigField("String", "SENTRY_RELEASE", JsonOutput.toJson(sentryRelease))
    }
}

dependencies {
    compileOnly(project(":hideapi"))

    implementation(project(":core"))
    implementation(project(":service"))
    implementation(project(":design"))
    implementation(project(":common"))

    implementation(libs.kotlin.coroutine)
    implementation(libs.androidx.core)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.coordinator)
    implementation(libs.google.material)
    implementation(libs.quickie.bundled)
    implementation(libs.androidx.activity.ktx)
    // разбор манифеста обновлений (latest.json)
    implementation(libs.kotlin.serialization.json)

    // Jetpack Compose. Activity хостят экраны из :design, поэтому рантайм нужен и здесь.
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.androidx.compose.runtime)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.sentry.android)

}

tasks.getByName("clean", type = Delete::class) {
    delete(file("release"))
}

val geoFilesDownloadDir = "src/main/assets"

data class GeoAsset(
    val url: String,
    val outputFileName: String,
    val sha256: String,
)

val geoAssets = listOf(
    GeoAsset(
        "https://github.com/MetaCubeX/meta-rules-dat/releases/download/latest/geoip.metadb",
        "geoip.metadb",
        "af2e40f90aa30e67a26d2f5546c7cd927181a714017a14900a2a0ceaf634a1a4",
    ),
    GeoAsset(
        "https://github.com/MetaCubeX/meta-rules-dat/releases/download/latest/geosite.dat",
        "geosite.dat",
        "31caedc9b4a38d471c7b83bf358b318548b4ad1912ef9885fa1ccfc60118311c",
    ),
    GeoAsset(
        "https://github.com/MetaCubeX/meta-rules-dat/releases/download/latest/GeoLite2-ASN.mmdb",
        "ASN.mmdb",
        "08abe94859725a638ab668d0ccc1fa10516b0d9623bb0098daf64ec227d33627",
    ),
    GeoAsset(
        "https://github.com/MetaCubeX/meta-rules-dat/releases/download/latest/BundleMRS.7z",
        "BundleMRS.7z",
        "f586164982c67a7c8361ced7ef24802bdde1ae05d720df305917f5086807164d",
    ),
)

task("downloadGeoFiles") {
    doLast {
        geoAssets.forEach { asset ->
            val outputPath = file("$geoFilesDownloadDir/${asset.outputFileName}")
            outputPath.parentFile.mkdirs()
            val temporaryPath = Files.createTempFile(
                outputPath.parentFile.toPath(),
                asset.outputFileName,
                ".download",
            )
            try {
                URL(asset.url).openStream().use { input ->
                    Files.copy(input, temporaryPath, StandardCopyOption.REPLACE_EXISTING)
                }
                val digest = MessageDigest.getInstance("SHA-256")
                val actual = temporaryPath.toFile().inputStream().use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        digest.update(buffer, 0, read)
                    }
                    digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
                }
                check(actual == asset.sha256) {
                    "Checksum mismatch for ${asset.outputFileName}"
                }
                Files.move(
                    temporaryPath,
                    outputPath.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
                println("${asset.outputFileName} verified and stored in $outputPath")
            } finally {
                Files.deleteIfExists(temporaryPath)
            }
        }
    }
}

afterEvaluate {
    val downloadGeoFilesTask = tasks["downloadGeoFiles"]

    tasks.forEach {
        if (it.name.startsWith("assemble")) {
            it.dependsOn(downloadGeoFilesTask)
        }
    }
}

tasks.getByName("clean", type = Delete::class) {
    delete(file(geoFilesDownloadDir))
}