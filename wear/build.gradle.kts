import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  id("com.android.application")
  alias(libs.plugins.compose.compiler)
}

android {
  namespace = "echo.music.iad1tya.wear"
  compileSdk = 36

  defaultConfig {
    // Must match the phone app's applicationId (and signing key) so the Data Layer pairs the two
    // and Play delivers the watch app as part of the same listing.
    applicationId = "echo.music.iad1tya"
    // Wear OS 3 (API 30) is the oldest version that supports Compose for Wear OS Material 3.
    minSdk = 30
    targetSdk = 36
    versionCode = 162
    versionName = "1.4.1"
  }

  signingConfigs {
    create("release") {
      val keystoreFile = rootProject.file("keystore.jks")
      if (keystoreFile.exists()) {
        storeFile = keystoreFile
      } else {
        val localKeystore = project(":app").file("keystore/release.keystore")
        if (localKeystore.exists()) storeFile = localKeystore
      }
      storePassword = System.getenv("STORE_PASSWORD")
      keyAlias = System.getenv("KEY_ALIAS")
      keyPassword = System.getenv("KEY_PASSWORD")
    }
    getByName("debug") {
      keyAlias = "androiddebugkey"
      keyPassword = "android"
      storePassword = "android"
      storeFile = file("${System.getProperty("user.home")}/.android/debug.keystore")
    }
  }

  buildTypes {
    release {
      isMinifyEnabled = true
      isShrinkResources = true
      signingConfig = signingConfigs.getByName("release")
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
    debug {
      // Keep in sync with :app, otherwise debug builds of phone and watch can't talk to each other.
      applicationIdSuffix = ".debug"
      signingConfig = signingConfigs.getByName("debug")
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
  }

  kotlin {
    jvmToolchain(21)
    compilerOptions { jvmTarget.set(JvmTarget.JVM_21) }
  }

  buildFeatures { compose = true }

  testOptions { unitTests.isIncludeAndroidResources = true }

  dependenciesInfo {
    includeInApk = false
    includeInBundle = false
  }

  lint {
    warningsAsErrors = false
    abortOnError = false
  }
}

dependencies {
  implementation(project(":wearcommon"))

  implementation(libs.activity)
  implementation(libs.compose.runtime)
  implementation(libs.compose.foundation)
  implementation(libs.compose.ui)
  implementation(libs.compose.ui.tooling)
  implementation(libs.viewmodel)
  implementation(libs.viewmodel.compose)
  implementation(libs.lifecycle.process)

  implementation(libs.wear)
  implementation(libs.wear.remote.interactions)
  implementation(libs.wear.compose.material3)
  implementation(libs.wear.compose.foundation)
  implementation(libs.wear.compose.navigation)

  implementation(libs.media3)
  implementation(libs.media3.session)

  implementation(libs.play.services.wearable)
  implementation(libs.coroutines.play.services)
  implementation(libs.coroutines.guava)
  implementation(libs.guava)

  testImplementation(libs.junit)
  testImplementation(libs.robolectric)
  testImplementation(libs.androidx.test.core)
}
