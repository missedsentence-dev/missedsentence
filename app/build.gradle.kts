plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.missedsentence.car"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.missedsentence.car"
        // Pleos App Market 요건은 28 이상이고, NaviHelper SDK 공식 샘플이 33을 쓰므로 33으로 맞춥니다.
        minSdk = 33
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 공모전 제출용 release APK를 바로 설치할 수 있도록 디버그 키로 서명합니다.
            // Pleos App Market 실제 배포 시에는 별도 서명 키로 교체합니다.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        // 차량 전용 권한 선언 때문에 release 빌드가 lint 경고로 멈추지 않도록 합니다.
        abortOnError = false
        checkReleaseBuilds = false
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.pleos.vehicle.sdk)
    implementation(libs.pleos.navihelper.sdk)
}
