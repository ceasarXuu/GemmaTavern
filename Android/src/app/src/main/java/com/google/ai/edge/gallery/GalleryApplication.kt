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

package selfgemma.talk

import android.app.Application
import android.util.Log
import com.google.firebase.FirebaseApp
import dagger.hilt.EntryPoint
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import selfgemma.talk.data.DataStoreRepository
import selfgemma.talk.ui.theme.ThemeSettings

@HiltAndroidApp
class SelfGemmaTalkApplication : Application() {

  override fun onCreate() {
    super.onCreate()

    // Load saved theme.
    ThemeSettings.themeOverride.value =
      EntryPointAccessors
        .fromApplication(this, SelfGemmaTalkApplicationEntryPoint::class.java)
        .dataStoreRepository()
        .readTheme()
    Log.d("SelfGemmaTalkApplication", "loaded theme from DataStore entry point")

    if (BuildConfig.ENABLE_FIREBASE) {
      runCatching { FirebaseApp.initializeApp(this) }
        .onFailure { exception ->
          Log.w("SelfGemmaTalkApplication", "Firebase initialization failed", exception)
        }
    } else {
      Log.i("SelfGemmaTalkApplication", "Firebase disabled for this build")
    }
  }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface SelfGemmaTalkApplicationEntryPoint {
  fun dataStoreRepository(): DataStoreRepository
}
