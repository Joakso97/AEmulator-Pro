import java.util.Properties
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val legalAssetsDir = layout.buildDirectory.dir("generated/legalAssets")
val copyLegalNotices = tasks.register<Copy>("copyLegalNotices") {
    from(rootProject.file("LICENSE"), rootProject.file("NOTICE.md"),
        rootProject.file("docs/build-source.md"), rootProject.file("docs/license-audit.md"))
    into(legalAssetsDir.map { it.dir("legal") })
}

 
 
val signProps = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
}
val canSign = signProps.getProperty("storeFile")?.let { rootProject.file(it).isFile } == true
val debugStore = File(System.getProperty("user.home"), ".android/debug.keystore")
val hasSignStore = canSign || debugStore.isFile

 
 
 
val engineSrc = file("src/main/assets/engines")
val engineOut = layout.buildDirectory.dir("generated/engineAssets")
val packEngines = tasks.register("packEngines") {
    inputs.dir(engineSrc)
    outputs.dir(engineOut)
    doLast {
        val o = engineOut.get().asFile
        o.deleteRecursively()
        File(o, "blobs").mkdirs()
        val idx = StringBuilder()
         
        engineSrc.walkTopDown().filter { it.isFile && !it.name.startsWith(".") }.sortedBy { it.invariantSeparatorsPath }.forEach { f ->
            val sha = MessageDigest.getInstance("SHA-1").digest(f.readBytes()).joinToString("") { "%02x".format(it) }
            val b = File(o, "blobs/$sha")
            if (!b.exists()) f.copyTo(b)
            val rel = f.relativeTo(engineSrc).path.replace('\\', '/')
            idx.append("engines/$rel\t$sha\t${f.length()}\n")
        }
        File(o, "engines.idx").writeText(idx.toString())
    }
}
tasks.named("preBuild") { dependsOn(packEngines) }

android {
    namespace = "app.aemu"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.aemu.pro"  
        minSdk = 26
        targetSdk = 35
        versionCode = 332
        versionName = "0.0.0.3.32"  
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    signingConfigs {
        if (hasSignStore) create("release") {
            storeFile = if (canSign) rootProject.file(signProps.getProperty("storeFile")) else debugStore
            storePassword = if (canSign) signProps.getProperty("storePassword") else "android"
            keyAlias = if (canSign) signProps.getProperty("keyAlias") else "androiddebugkey"
            keyPassword = if (canSign) signProps.getProperty("keyPassword") else "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasSignStore) signingConfig = signingConfigs.getByName("release")
        }
        debug {
            applicationIdSuffix = ""
             
            if (hasSignStore) signingConfig = signingConfigs.getByName("release")
        }
    }

     
    sourceSets["main"].assets.setSrcDirs(listOf(engineOut, legalAssetsDir, file("src/main/skinAssets")))

    packaging {
         
        jniLibs {
            useLegacyPackaging = true
            excludes += listOf("**/x86/**", "**/x86_64/**", "**/armeabi-v7a/**", "**/mips/**", "**/mips64/**")
        }
    }

    androidResources {
        noCompress += listOf("payload", "tar", "gz", "so", "img")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    tasks.withType<JavaCompile>().configureEach {
        options.compilerArgs.add("-Xlint:deprecation")
    }
    tasks.withType<Test> {
        maxParallelForks = 1
        val systemRoot = System.getenv("SystemRoot") ?: "C:\\WINDOWS"
        environment.clear()
        environment("SystemRoot", systemRoot)
        environment("PATH", "$systemRoot\\system32;$systemRoot")
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
            optIn.add("androidx.compose.material3.ExperimentalMaterial3ExpressiveApi")
            optIn.add("androidx.compose.material3.ExperimentalMaterial3Api")
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    lint { abortOnError = false; checkReleaseBuilds = false }
}

dependencies {
    val bom = platform("androidx.compose:compose-bom:2025.11.00")
    implementation(bom)
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3:1.5.0-alpha16")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.tukaani:xz:1.10")
    implementation("org.apache.commons:commons-compress:1.28.0")
    implementation("com.github.junrar:junrar:8.1.1")
    implementation("org.brotli:dec:0.1.2")
    implementation("net.lingala.zip4j:zip4j:2.11.6")
    implementation("com.github.luben:zstd-jni:1.5.7-4@aar")
    implementation("org.anarres.lzo:lzo-commons:1.0.6")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
}

tasks.named("preBuild") { dependsOn(copyLegalNotices) }

tasks.register<Copy>("copyReleaseApks") {
    dependsOn("assembleRelease")
    from(layout.buildDirectory.dir("outputs/apk/release")) {
        include("*.apk")
        rename { "AEmulatorPro.apk" }
    }
    into(rootProject.layout.projectDirectory.dir("dist"))
}

tasks.register<Copy>("copyDebugApk") {
    dependsOn("assembleDebug")
    doNotTrackState("Copying APK artifact to release-apks/debug")
    from(layout.buildDirectory.dir("outputs/apk/debug")) {
        include("app-debug.apk")
        rename { "AEmulatorPlus+.apk" }
    }
    into(rootProject.layout.projectDirectory.dir("release-apks/debug"))
}
