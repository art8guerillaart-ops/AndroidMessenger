plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlin.kapt") // Room (SignalProtocolStore, см. data/signal/)
}

android {
    namespace = "com.example.messenger"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.messenger"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1-stage1"

        // Базовый адрес бэкенда.
        // 10.0.2.2 — это localhost хоста внутри Android-эмулятора.
        // Если сервер задеплоен (например, на Render/VPS) — подставь его https-адрес.
        buildConfigField("String", "BASE_URL", "\"http://10.0.2.2:8000\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true // требуется libsignal-android
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")

    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.7.7")

    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-kotlinx-serialization:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("io.coil-kt:coil-compose:2.6.0")

    // Signal Protocol (E2E-шифрование личных переписок).
    // libsignal-android — AAR со скомпилированными .so под Android-ABI
    // (arm64-v8a/armeabi-v7a/x86/x86_64); он сам подтягивает libsignal-client
    // как API-слой (SessionBuilder, SessionCipher и т.д.) — именно эти классы
    // используются в коде.
    // Зафиксировано на 0.72.0 по двум независимым причинам:
    // 1) начиная с 0.74.0 сам libsignal-client требует kotlin-stdlib 2.1.0,
    //    метаданные которой не читает Kotlin-компилятор 1.9.24 этого проекта;
    // 2) начиная с 0.73.0 нативный конструктор PreKeyBundle_New сделал Kyber
    //    (post-quantum) ключ ОБЯЗАТЕЛЬНЫМ параметром (был Option<&KyberPublicKey>,
    //    стал &KyberPublicKey) — без реального Kyber-ключа бандл собрать нельзя,
    //    а PQXDH явно вне scope задачи. 0.72.0 — последняя версия, где оба условия
    //    выполняются одновременно; публичный API (SessionBuilder/SessionCipher/
    //    стор-интерфейсы), которым пользуется код, между версиями не менялся.
    implementation("org.signal:libsignal-android:0.72.0")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")

    // Room — хранилище для AndroidSignalProtocolStore (data/signal/).
    implementation("androidx.room:room-runtime:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
