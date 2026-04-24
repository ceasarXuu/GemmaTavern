/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

plugins {
  alias(libs.plugins.android.application)
  // Note: set apply to true to enable google-services (requires google-services.json).
  alias(libs.plugins.google.services) apply false
  alias(libs.plugins.kotlin.android)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.protobuf)
  alias(libs.plugins.hilt.application)
  alias(libs.plugins.oss.licenses)
  alias(libs.plugins.ksp)
  kotlin("kapt")
}

fun String.escapeForBuildConfig(): String = replace("\\", "\\\\").replace("\"", "\\\"")

fun parseBooleanGradleProperty(value: String?): Boolean =
  value?.equals("true", ignoreCase = true) == true

fun resolveAuthRedirectScheme(redirectUri: String): String {
  val scheme = redirectUri.substringBefore(':', "")
  return if (scheme.isBlank()) "selfgemma.talk.auth" else scheme
}

val huggingFaceClientId = providers.gradleProperty("HUGGINGFACE_CLIENT_ID").orElse("")
val huggingFaceRedirectUri = providers.gradleProperty("HUGGINGFACE_REDIRECT_URI").orElse("")
val firebaseEnabled = parseBooleanGradleProperty(providers.gradleProperty("ENABLE_FIREBASE").orNull)

android {
  namespace = "selfgemma.talk"
  compileSdk = 35

  defaultConfig {
    applicationId = "selfgemma.talk"
    minSdk = 31
    targetSdk = 35
    versionCode = 25
    versionName = "0.1.1"

    buildConfigField(
      "String",
      "HUGGINGFACE_CLIENT_ID",
      "\"${huggingFaceClientId.get().escapeForBuildConfig()}\"",
    )
    buildConfigField(
      "String",
      "HUGGINGFACE_REDIRECT_URI",
      "\"${huggingFaceRedirectUri.get().escapeForBuildConfig()}\"",
    )
    buildConfigField("boolean", "ENABLE_FIREBASE", firebaseEnabled.toString())

    // Needed for HuggingFace auth workflows when a maintainer configures them.
    manifestPlaceholders["appAuthRedirectScheme"] =
      resolveAuthRedirectScheme(huggingFaceRedirectUri.get())
    manifestPlaceholders["applicationName"] = "selfgemma.talk.SelfGemmaTalkApplication"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  buildTypes {
    debug {
      buildConfigField("boolean", "ENABLE_INTERNAL_DIAGNOSTICS", "true")
      buildConfigField("boolean", "ENABLE_BENCHMARK_UI", "true")
    }
    release {
      buildConfigField("boolean", "ENABLE_INTERNAL_DIAGNOSTICS", "false")
      buildConfigField("boolean", "ENABLE_BENCHMARK_UI", "false")
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = signingConfigs.getByName("debug")
    }
    create("benchmark") {
      initWith(getByName("release"))
      matchingFallbacks += listOf("release")
      buildConfigField("boolean", "ENABLE_INTERNAL_DIAGNOSTICS", "true")
      buildConfigField("boolean", "ENABLE_BENCHMARK_UI", "true")
      signingConfig = signingConfigs.getByName("debug")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  kotlinOptions {
    jvmTarget = "11"
    freeCompilerArgs += "-Xcontext-receivers"
  }
  lint {
    baseline = file("lint-baseline.xml")
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
}

androidComponents {
  beforeVariants(selector().withBuildType("benchmark")) { variantBuilder ->
    variantBuilder.enableAndroidTest = false
  }
}

dependencies {
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.appcompat)
  implementation(libs.androidx.metrics.performance)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.ui)
  implementation(libs.androidx.ui.graphics)
  implementation(libs.androidx.ui.tooling.preview)
  implementation(libs.androidx.material3)
  implementation(libs.androidx.compose.navigation)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.kotlin.reflect)
  implementation(libs.material.icon.extended)
  implementation(libs.androidx.work.runtime)
  implementation(libs.androidx.datastore)
  implementation(libs.androidx.room.runtime)
  implementation(libs.androidx.room.ktx)
  implementation(libs.com.google.code.gson)
  implementation(libs.androidx.lifecycle.process)
  implementation(libs.androidx.profileinstaller)
  implementation(libs.androidx.security.crypto)
  implementation(libs.androidx.webkit)
  implementation(libs.litertlm)
  implementation(libs.commonmark)
  implementation(libs.richtext)
  implementation(libs.tflite)
  implementation(libs.tflite.gpu)
  implementation(libs.tflite.support)
  implementation(libs.camerax.core)
  implementation(libs.camerax.camera2)
  implementation(libs.camerax.lifecycle)
  implementation(libs.camerax.view)
  implementation(libs.openid.appauth)
  implementation(libs.androidx.splashscreen)
  implementation(libs.protobuf.javalite)
  implementation(libs.hilt.android)
  implementation(libs.hilt.navigation.compose)
  implementation(libs.play.services.oss.licenses)
  implementation(platform(libs.firebase.bom))
  implementation(libs.firebase.analytics)
  implementation(libs.firebase.messaging)
  implementation(libs.androidx.exifinterface)
  implementation(libs.androidx.media3.exoplayer)
  implementation(libs.androidx.media3.ui)
  implementation(libs.moshi.kotlin)
  kapt(libs.hilt.android.compiler)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.ui.test.junit4)
  androidTestImplementation(libs.hilt.android.testing)
  debugImplementation(libs.androidx.ui.tooling)
  debugImplementation(libs.androidx.ui.test.manifest)
  ksp(libs.androidx.room.compiler)
  ksp(libs.moshi.kotlin.codegen)
}

ksp {
  arg("room.schemaLocation", "$projectDir/schemas")
  arg("room.incremental", "true")
  arg("room.generateKotlin", "true")
}

protobuf {
  protoc { artifact = "com.google.protobuf:protoc:4.26.1" }
  generateProtoTasks { all().forEach { it.plugins { create("java") { option("lite") } } } }
}
