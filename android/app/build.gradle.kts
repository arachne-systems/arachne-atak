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
require(atakVersion in setOf("5.6.0", "5.7.0", "5.8.0")) { "Unsupported build target: $atakVersion" }
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
val fabricJniLibs = file(providers.gradleProperty("fabricJniLibs").getOrElse("../../.cache/jniLibs"))
listOf("arm64-v8a", "x86_64").forEach { abi ->
    require(file("$fabricJniLibs/$abi/libfabric_android.so").isFile) {
        "Missing $abi/libfabric_android.so under $fabricJniLibs; run ../scripts/build-plugin.sh first"
    }
}
// Keep the release runtime inventory reproducible and available to dependency scans.
configurations.matching { it.name == "civReleaseRuntimeClasspath" }.configureEach {
    resolutionStrategy.activateDependencyLocking()
}
android {
    namespace = "dev.arachne.atak"
    useLibrary("org.apache.http.legacy")
    buildFeatures { buildConfig = true }
    sourceSets.getByName("main").jniLibs.srcDir(fabricJniLibs)
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
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.arachne.atak"
        minSdk = 26
        targetSdk = 35
        versionCode = 6
        versionName = "0.0.3-alpha"
        testInstrumentationRunner = "dev.arachne.atak.FabricSessionInstrumentation"
        manifestPlaceholders["atakApiVersion"] = "com.atakmap.app@$atakVersion.CIV"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    signingConfigs {
        getByName("debug") {
            storeFile = layout.buildDirectory.file("android_keystore").get().asFile
            storePassword = "tnttnt"
            keyAlias = "wintec_mapping"
            keyPassword = "tnttnt"
        }
    }
}
kotlin { jvmToolchain(17) }
dependencies { implementation("com.google.zxing:core:3.5.4") }

val verifyToolbarIconContract = tasks.register("verifyToolbarIconContract") {
    val androidNamespace = "http://schemas.android.com/apk/res/android"
    val manifestFile = file("src/main/AndroidManifest.xml")
    val pluginFile = file("src/main/kotlin/dev/datafabric/atak/FabricPlugin.kt")
    val styleFile = file("src/main/kotlin/dev/datafabric/atak/ArachneStyle.kt")
    val iconFile = file("src/main/res/drawable/arachne_toolbar_icon.xml")
    inputs.files(manifestFile, pluginFile, styleFile, iconFile)
    doLast {
        val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val appIcon = factory.newDocumentBuilder().parse(manifestFile).getElementsByTagName("application")
            .item(0).attributes.getNamedItemNS(androidNamespace, "icon")?.nodeValue
        check(appIcon == "@drawable/arachne_toolbar_icon") { "ATAK's manifest icon must use transparent toolbar artwork" }
        check(Regex("MarshalManager\\.marshal\\(\\s*ArachneStyle\\.toolbarIcon\\(context\\)")
            .containsMatchIn(pluginFile.readText())) { "ToolbarItem must use ArachneStyle.toolbarIcon()" }
        check(Regex("fun toolbarIcon\\(context: Context\\).*R\\.drawable\\.arachne_toolbar_icon")
            .containsMatchIn(styleFile.readText())) { "ArachneStyle.toolbarIcon() must resolve the transparent vector" }
        val root = factory.newDocumentBuilder().parse(iconFile).documentElement
        check(root.tagName == "vector" && !root.hasAttributeNS(androidNamespace, "background") &&
            root.getElementsByTagName("path").length > 0) { "Toolbar artwork must be a non-empty transparent vector" }
    }
}
tasks.named("preBuild").configure { dependsOn(verifyToolbarIconContract) }
