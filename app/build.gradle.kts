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
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/firebaseAssets"))
    sourceSets["main"].res.srcDir(layout.buildDirectory.dir("generated/firebaseRes"))
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/callingAssets"))
    sourceSets["main"].jniLibs.srcDir(layout.buildDirectory.dir("generated/callingJni"))
    val rtcRoot = rootProject.file(".native/telegram-x-calls/app/jni/tgvoip/third_party/webrtc")
    sourceSets["main"].java.srcDirs(listOf("sdk/android/api", "sdk/android/src/java", "rtc_base/java/src").map { rtcRoot.resolve(it) })
    sourceSets["main"].java.srcDir(rootProject.file(".native/telegram-x-calls/thirdparty/WebRTC/src/java"))
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
    implementation("androidx.camera:camera-camera2:1.5.0")
    implementation("androidx.camera:camera-lifecycle:1.5.0")
    implementation("androidx.camera:camera-video:1.5.0")
    implementation("androidx.camera:camera-view:1.5.0")
    implementation("androidx.media3:media3-transformer:1.8.0")
    implementation("androidx.media3:media3-effect:1.8.0")
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

// Optional real Firebase client configuration, without changing build plugins/toolchains.
val generateFirebaseConfiguration by tasks.registering {
    doNotTrackState("Firebase client configuration is supplied locally")
    doLast {
        val source = listOf(file("google-services.json"), rootProject.file("google-services.json")).firstOrNull { it.isFile }
        if (source != null && file("src/main/res/values/firebase-config.xml").exists()) throw GradleException("Use google-services.json or explicit Firebase resources, not both")
        val config = if (source == null) emptyMap<String, String>() else {
            val json = groovy.json.JsonSlurper().parse(source) as Map<*, *>
            val project = json["project_info"] as? Map<*, *> ?: throw GradleException("Firebase project_info is missing")
            val client = (json["client"] as? List<*>)?.mapNotNull { it as? Map<*, *> }?.firstOrNull {
                ((it["client_info"] as? Map<*, *>)?.get("android_client_info") as? Map<*, *>)?.get("package_name") == "com.vector.messenger"
            } ?: throw GradleException("Firebase configuration must contain Android package com.vector.messenger")
            val info = client["client_info"] as Map<*, *>
            val key = (client["api_key"] as? List<*>)?.mapNotNull { (it as? Map<*, *>)?.get("current_key")?.toString() }?.firstOrNull()
            mapOf("applicationId" to info["mobilesdk_app_id"].toString(), "projectId" to project["project_id"].toString(),
                "senderId" to project["project_number"].toString(), "apiKey" to key.orEmpty()).also { values ->
                if (values.values.any { it.isBlank() || it == "null" }) throw GradleException("Incomplete Firebase Android client configuration; values are not printed")
            }
        }
        val output = layout.buildDirectory.file("generated/firebaseAssets/firebase-client.json").get().asFile
        output.parentFile.mkdirs()
        output.writeText(groovy.json.JsonOutput.toJson(config))
        // Standard resources let FirebaseInitProvider initialize before a cold FCM receiver.
        // The assets fallback alone would be too late for SDK components started before onCreate.
        fun xml(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        val names = mapOf("applicationId" to "google_app_id", "projectId" to "project_id", "senderId" to "gcm_defaultSenderId", "apiKey" to "google_api_key")
        val resources = layout.buildDirectory.file("generated/firebaseRes/values/firebase-client.xml").get().asFile
        resources.parentFile.mkdirs()
        resources.writeText("<resources>" + config.entries.joinToString("") { (key, value) ->
            "<string name=\"${names.getValue(key)}\" translatable=\"false\" formatted=\"false\">${xml(value)}</string>"
        } + "</resources>")
    }
}
tasks.named("preBuild").configure { dependsOn(generateFirebaseConfiguration) }

// Calling is packaged separately from the existing, independently verified TDLib inventory.
val verifyCallingNative by tasks.registering {
    doNotTrackState("Verify source-built call engine before packaging")
    doLast {
        val library = layout.buildDirectory.file("generated/callingJni/arm64-v8a/libdefault_calls.so").get().asFile
        if (library.exists()) {
            val manifestFile = rootProject.file("native/calling-manifest.json")
            if (!manifestFile.exists()) throw GradleException("Calling native provenance missing. Run scripts/Build-CallingAndroid.ps1.")
            val manifest = groovy.json.JsonSlurper().parse(manifestFile) as Map<*, *>
            val sha = MessageDigest.getInstance("SHA-256").digest(library.readBytes()).joinToString("") { "%02x".format(it) }
            if (manifest["sha256"] != sha || manifest["abi"] != "arm64-v8a") throw GradleException("Calling engine checksum/ABI mismatch.")
        }
    }
}
tasks.named("preBuild").configure { dependsOn(verifyCallingNative) }
