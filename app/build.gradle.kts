
plugins {
    id("com.android.application")
    
}

android {
    namespace = "com.aliya.hy_vq"
    compileSdk = 33
    
    defaultConfig {
        applicationId = "com.aliya.hy_vq"
        // 版本适配策略：支持 Android 9 (API 28) 及以上；
        // compileSdk/targetSdk 保持 33，暂不适配 Android 17 (API 37)
        minSdk = 28
        targetSdk = 33
        versionCode = 27
        versionName = "2.5.0"
        
        vectorDrawables { 
            useSupportLibrary = true
        }
    }
    
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        viewBinding = true
        
    }
    
}

dependencies {
    // ── AndroidX 基础 ──
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("com.google.android.material:material:1.9.0")
    implementation("androidx.appcompat:appcompat:1.6.1")

    // ── 网络：OkHttp + WebSocket ──
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // ── JSON 解析 ──
    implementation("com.google.code.gson:gson:2.10.1")

    // ── 图片加载 ──
    implementation("com.github.bumptech.glide:glide:4.16.0")
    annotationProcessor("com.github.bumptech.glide:compiler:4.16.0")

    // ── 本地数据库：Room ──
    implementation("androidx.room:room-runtime:2.5.2")
    annotationProcessor("androidx.room:room-compiler:2.5.2")

    // ── 内嵌 WebSocket 服务器：NanoHTTPD ──
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("org.nanohttpd:nanohttpd-websocket:2.3.1")

    // ── 加密 ──
    implementation("commons-codec:commons-codec:1.16.0")
}