plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// 시연용 안내 음성 앱. 내비 안내 음성처럼 오디오 포커스를 잠깐 요청하고 안내음을 냅니다.
// 놓친문장이 시스템 경로로 멈췄다가 그 문장 처음부터 다시 재생하는 것을 보여 줄 때 씁니다.
android {
    namespace = "com.missedsentence.navprompt"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.missedsentence.navprompt"
        minSdk = 33
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
        abortOnError = false
        checkReleaseBuilds = false
    }
}
