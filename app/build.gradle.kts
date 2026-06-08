plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.blackalert.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.black.alert"
        minSdk = 24
        targetSdk = 34
        versionCode = 7
        versionName = "1.5.0"
    }

    signingConfigs {
        create("release") {
            val ksPath = System.getenv("KEYSTORE_PATH")
            val ksPass = System.getenv("KEYSTORE_PASSWORD") ?: ""
            if (ksPath != null) {
                storeFile = file(ksPath)
                storePassword = ksPass
                keyAlias = "black-alert"
                keyPassword = ksPass
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName(
                if (System.getenv("KEYSTORE_PATH") != null) "release" else "debug"
            )
        }
        debug {
            // גרסת בדיקה — מותקנת במקביל לאמיתית (applicationId נפרד), מאפשרת שרת mock ב-HTTP.
            // שם האפליקציה והרשאת cleartext נדרסים ב-src/debug/.
            applicationIdSuffix = ".test"
            versionNameSuffix = "-test"
        }
    }

    // שתי הפצות: sideload (GitHub, עם עדכון-עצמי) ו-play (תואם מדיניות Google Play).
    flavorDimensions += "store"
    productFlavors {
        create("sideload") {
            dimension = "store"
            isDefault = true
            buildConfigField("boolean", "PLAY_STORE", "false")
        }
        create("play") {
            dimension = "store"
            buildConfigField("boolean", "PLAY_STORE", "true")
            // ב-Play מסירים את העדכון-העצמי, הרשאת התקנה, מיקום-רקע, חיוג ישיר ואופטימיזציית-סוללה
            // (ראו src/play/AndroidManifest.xml + docs/PLAY_COMPLIANCE.md)
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
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-service:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    // Failover push: FCM למכשירים עם Google Play Services. מאותחל ידנית (ללא google-services
    // plugin) כך שהבנייה והאפליקציה עובדות גם בלי הגדרת Firebase ובלי Play Services.
    implementation(platform("com.google.firebase:firebase-bom:33.1.2"))
    implementation("com.google.firebase:firebase-messaging")
    implementation("com.google.android.gms:play-services-base:18.5.0")

    // MQTT — ערוץ push למכשירים ללא Google Play (חיבור מתמשך חסכוני בסוללה).
    // לקוח Java טהור; מנוהל בתוך ה-Foreground Service שלנו (לא ה-Paho Android Service הישן).
    implementation("org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5")
}
