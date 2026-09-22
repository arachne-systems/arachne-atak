buildscript {
    val sdk = providers.environmentVariable("FABRIC_TAK_SDK").orNull
    val repo = providers.gradleProperty("takrepo.url").orNull
    repositories {
        google()
        mavenCentral()
        if (repo != null) maven {
            url = uri(repo)
            credentials {
                username = providers.gradleProperty("takrepo.user").orNull
                password = providers.gradleProperty("takrepo.password").orNull
            }
        }
    }
    dependencies {
        if (repo != null) classpath("com.atakmap.gradle:atak-gradle-takdev:3.+")
        else {
            require(sdk != null) { "Set FABRIC_TAK_SDK or takrepo.url" }
            classpath(files("$sdk/atak-gradle-takdev.jar"))
        }
    }
}
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
val takSdk = providers.environmentVariable("FABRIC_TAK_SDK").orNull
val atakVersion = providers.gradleProperty("atakVersion").getOrElse("5.8.0")
require(atakVersion == "5.8.0") { "Supported build target: ATAK-CIV 5.8.0" }
extra["ATAK_VERSION"] = atakVersion
extra["takrepoUrl"] = providers.gradleProperty("takrepo.url").getOrElse("https://localhost/")
extra["takrepoUser"] = providers.gradleProperty("takrepo.user").getOrElse("invalid")
extra["takrepoPassword"] = providers.gradleProperty("takrepo.password").getOrElse("invalid")
if (takSdk != null) extra["sdkPath"] = takSdk
extra["isDevKitEnabled"] = object : groovy.lang.Closure<Boolean>(project) {
    fun doCall(): Boolean = providers.gradleProperty("takrepo.url").isPresent
}
apply(plugin = "atak-takdev-plugin")
repositories { google(); mavenCentral() }
// Keep the release runtime inventory reproducible and available to dependency scans.
configurations.matching { it.name == "civReleaseRuntimeClasspath" }.configureEach {
    resolutionStrategy.activateDependencyLocking()
}
android {
    namespace = "dev.arachne.atak"
    useLibrary("org.apache.http.legacy")
    buildFeatures { buildConfig = true }
    sourceSets.getByName("main").jniLibs.srcDir(providers.gradleProperty("fabricJniLibs").getOrElse("../../.cache/jniLibs"))
    packaging { jniLibs.useLegacyPackaging = true }
    bundle { storeArchive { enable = false } }
    flavorDimensions += "application"
    productFlavors { create("civ") { dimension = "application" } }
    buildTypes {
        getByName("debug") {
            matchingFallbacks += "sdk"
            // Debug rig link: the controller's endpoint key from
            // `.cache/rig/controller.json` (written by `cargo run -p arachne-rig`).
            // Empty when absent; the link then stays off. Release builds never
            // carry this field or the link code.
            val rig = rootProject.file("../.cache/rig/controller.json")
            val rigKey = if (rig.exists()) Regex("\"endpoint_key\":\"([0-9a-f]{64})\"").find(rig.readText())?.groupValues?.get(1).orEmpty() else ""
            buildConfigField("String", "RIG_KEY", "\"$rigKey\"")
        }
        getByName("release") {
            isMinifyEnabled = true
            matchingFallbacks += "odk"
            proguardFiles("proguard-gradle.txt", "proguard-repackage.txt")
        }
    }
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.arachne.atak"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "0.0.2-alpha"
        testInstrumentationRunner = "dev.arachne.atak.FabricSessionInstrumentation"
        manifestPlaceholders["atakApiVersion"] = "com.atakmap.app@$atakVersion.CIV"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
kotlin { jvmToolchain(17) }
dependencies { implementation("com.google.zxing:core:3.5.4") }
