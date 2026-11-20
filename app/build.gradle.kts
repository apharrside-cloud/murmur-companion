import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Optional: put build output somewhere else (useful when a sync tool watches the project folder).
System.getenv("MN_BUILD_DIR")?.let { layout.buildDirectory.set(file(it)) }

// Release signing is optional. Point MN_KEYSTORE_PROPS at a .properties file with storeFile, storePassword,
// keyAlias and keyPassword. Without it, builds are signed with the standard Android debug key.
val keystoreProps = Properties().apply {
    val path = System.getenv("MN_KEYSTORE_PROPS")
    if (!path.isNullOrBlank() && File(path).isFile) File(path).inputStream().use { load(it) }
}

android {
    namespace = "com.pharrlabs.murmur"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.pharrlabs.murmur"
        minSdk = 31
        targetSdk = 35
        // CI passes -PmnVersionName=x.y.z -PmnVersionCode=n for releases.
        versionCode = (project.findProperty("mnVersionCode") as String?)?.toInt() ?: 1
        versionName = (project.findProperty("mnVersionName") as String?) ?: "0.1.0"
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        debug { if (keystoreProps.isNotEmpty()) signingConfig = signingConfigs.getByName("release") }
        release {
            if (keystoreProps.isNotEmpty()) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
        }
    }

    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20231013")
}
