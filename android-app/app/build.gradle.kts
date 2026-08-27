import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val repositorySigningPropertiesFile = rootProject.projectDir.parentFile.resolve(
    "release-signing/wzryncauto-release.properties",
)
val releaseSigningPropertiesFile = file(
    providers.gradleProperty("wzryReleaseSigningProperties")
        .orElse(providers.environmentVariable("WZRY_RELEASE_SIGNING_PROPERTIES"))
        .orElse(repositorySigningPropertiesFile.absolutePath)
        .get(),
)
val releaseSigningProperties = Properties().apply {
    if (releaseSigningPropertiesFile.isFile) {
        releaseSigningPropertiesFile.inputStream().use(::load)
    }
}
val releaseStoreFile = releaseSigningProperties.getProperty("storeFile")?.let { configured ->
    val configuredFile = file(configured)
    val siblingFile = releaseSigningPropertiesFile.parentFile.resolve(configuredFile.name)
    when {
        siblingFile.isFile -> siblingFile
        configuredFile.isFile -> configuredFile
        else -> siblingFile
    }
}

android {
    namespace = "com.lispace.wzryncauto"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.lispace.wzryncauto"
        minSdk = 28
        targetSdk = 35
        versionCode = 22
        versionName = "0.3.19"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        if (releaseSigningPropertiesFile.isFile) {
            create("release") {
                storeFile = requireNotNull(releaseStoreFile)
                storePassword = requireNotNull(releaseSigningProperties.getProperty("storePassword"))
                keyAlias = requireNotNull(releaseSigningProperties.getProperty("keyAlias"))
                keyPassword = requireNotNull(releaseSigningProperties.getProperty("keyPassword"))
            }
        }
    }

    buildTypes {
        release {
            if (releaseSigningPropertiesFile.isFile) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

tasks.matching {
    it.name.contains("Release", ignoreCase = true)
}.configureEach {
    doFirst {
        check(releaseSigningPropertiesFile.isFile) {
            "缺少正式签名配置：${releaseSigningPropertiesFile.absolutePath}"
        }
        check(releaseStoreFile?.isFile == true) {
            "缺少正式签名密钥：${releaseStoreFile?.absolutePath ?: "<未配置>"}"
        }
    }

}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.datastore:datastore-preferences:1.1.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.10.01"))
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
