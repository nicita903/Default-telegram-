import java.util.Properties
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
}

android {
    namespace = "com.vector.messenger"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.vector.messenger"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true }
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/telegramAssets"))
    // Official Android CMake target already strips this prebuilt library; no SDK NDK install is needed by AGP.
    packaging { jniLibs { keepDebugSymbols += "**/libtdjsonjava.so" } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("com.google.firebase:firebase-messaging:25.0.1")
    implementation("androidx.work:work-runtime-ktx:2.10.5")
    implementation("androidx.media3:media3-exoplayer:1.8.0")
    implementation("androidx.media3:media3-ui:1.8.0")
    implementation("androidx.media3:media3-session:1.8.0")
    implementation("com.google.zxing:core:3.5.3") // Local QR encoding only; no network or scanner permission.
    implementation(project(":core"))
    implementation(platform("androidx.compose:compose-bom:2025.10.01"))
    implementation("androidx.activity:activity-compose:1.12.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.9.4")
    implementation("androidx.navigation:navigation-compose:2.9.5")
    implementation("com.google.dagger:hilt-android:2.58")
    ksp("com.google.dagger:hilt-compiler:2.58")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.10.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

val generateTelegramConfiguration by tasks.registering {
    // API configuration necessarily ships in the APK; exclude generated plaintext from build caching.
    doNotTrackState("Contains developer Telegram API configuration")
    doLast {
        val properties = Properties()
        val localFile = rootProject.file("telegram.properties")
        if (localFile.exists()) localFile.inputStream().use(properties::load)
        val apiId = (properties.getProperty("api.id")?.takeIf { it.isNotBlank() } ?: System.getenv("TELEGRAM_API_ID"))?.toIntOrNull() ?: 0
        val apiHash = properties.getProperty("api.hash")?.takeIf { it.isNotBlank() } ?: System.getenv("TELEGRAM_API_HASH") ?: ""
        if ((apiId != 0 || apiHash.isNotEmpty()) && (apiId <= 0 || !apiHash.matches(Regex("[a-fA-F0-9]{32}")))) {
            throw GradleException("Invalid Telegram API configuration. See docs/TDLIB_AUTH.md; values are not printed.")
        }
        val output = layout.buildDirectory.file("generated/telegramAssets/telegram-config.json").get().asFile
        output.parentFile.mkdirs()
        output.writeText(groovy.json.JsonOutput.toJson(mapOf("apiId" to apiId, "apiHash" to apiHash)))
    }
}
tasks.named("preBuild").configure { dependsOn(generateTelegramConfiguration) }

val verifyTdlibNative by tasks.registering {
    doNotTrackState("Verify local TDLib binaries on every build")
    doLast {
        val directory = file("src/main/jniLibs")
        val binaries = fileTree(directory) { include("**/*.so") }.files
        if (binaries.isEmpty()) return@doLast // Account UI reports missing native setup; demo screens still build.
        val manifestFile = rootProject.file("native/tdlib-manifest.json")
        if (!manifestFile.exists()) throw GradleException("Native TDLib provenance is missing. Run scripts/Build-TdlibAndroid.ps1.")
        val manifest = groovy.json.JsonSlurper().parse(manifestFile) as Map<*, *>
        if (manifest["tdlibCommit"] != "c8828d50f5eac2fc7089ce1089d7ce9095c61b5e") throw GradleException("Unexpected TDLib source pin; review schema compatibility before updating.")
        val declared = (manifest["libraries"] as List<*>).associate { value ->
            val item = value as Map<*, *>
            "${item["abi"]}/${item["file"]}" to item["sha256"].toString()
        }
        if (binaries.size != declared.size) throw GradleException("TDLib binary inventory differs from the reviewed manifest.")
        binaries.forEach { binary ->
            val hash = MessageDigest.getInstance("SHA-256").digest(binary.readBytes()).joinToString("") { "%02x".format(it) }
            if (declared[binary.relativeTo(directory).invariantSeparatorsPath] != hash) throw GradleException("TDLib binary checksum differs from the reviewed manifest.")
        }
    }
}
tasks.named("preBuild").configure { dependsOn(verifyTdlibNative) }
