import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Version derives from the git commit count so every build increments; CI or a caller can
// override with -PappVersionCode / -PappVersionName.
val gitCommitCount: Int = (project.findProperty("appVersionCode") as String?)?.toIntOrNull() ?: run {
    try {
        val proc = ProcessBuilder("git", "rev-list", "--count", "HEAD")
            .directory(project.rootDir).redirectErrorStream(true).start()
        val text = proc.inputStream.bufferedReader().readText().trim()
        proc.waitFor()
        text.toIntOrNull() ?: 1
    } catch (e: Exception) { 1 }
}
val appVersionName = (project.findProperty("appVersionName") as String?) ?: "1.0.$gitCommitCount"

// The ingest secret lives in deploy/shotarc.env (gitignored), shared with the server, never in git.
val ingestKey: String = rootProject.file("deploy/shotarc.env").let { file ->
    if (!file.exists()) return@let ""
    file.readLines()
        .firstOrNull { it.startsWith("INGEST_KEY=") }
        ?.substringAfter("=")?.trim().orEmpty()
}

android {
    namespace = "com.golfapp.tracker"
    compileSdk = 36

    defaultConfig {
        applicationId = "za.co.shotarc.app"
        minSdk = 26
        targetSdk = 36
        versionCode = gitCommitCount
        versionName = appVersionName

        // Where the dashboard lives. Set golfServerUrl in gradle.properties or on the command
        // line; leave it empty and the app uploads nothing.
        val serverUrl = (project.findProperty("golfServerUrl") as String?).orEmpty()
        buildConfigField("String", "SERVER_BASE_URL", "\"$serverUrl\"")
        buildConfigField("String", "INGEST_KEY", "\"$ingestKey\"")
        // Plain http to a bare IP is blocked by default; allowed only when the URL says http://
        manifestPlaceholders["cleartext"] = serverUrl.startsWith("http://").toString()
    }

    val keystore = project.findProperty("golfKeystore") as String?
    if (keystore != null) {
        signingConfigs {
            create("release") {
                storeFile = file(keystore)
                storePassword = project.findProperty("golfKeystorePassword") as String?
                keyAlias = project.findProperty("golfKeyAlias") as String?
                keyPassword = project.findProperty("golfKeyPassword") as String?
            }
        }
    }

    buildTypes {
        release {
            if (keystore != null) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")

    val camerax = "1.5.3"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")   // the real thing; android.jar only stubs it
}
