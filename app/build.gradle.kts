import java.net.URI
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// sherpa-onnx(기기 안 SenseVoice 인식)는 Maven에 없고 공식 GitHub 릴리스로 AAR(약 50MB)만 배포된다.
// 저장소에 넣지 않고, 없으면 처음 빌드할 때 받아 SHA-256을 확인한다.
val sherpaVersion = "1.13.8"
val sherpaSha256 = "633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96"
val sherpaAar = file("libs/sherpa-onnx-$sherpaVersion.aar")

fun sha256(f: File): String {
    val md = MessageDigest.getInstance("SHA-256")
    f.inputStream().use { input ->
        val buf = ByteArray(1 shl 16)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
    }
    return md.digest().joinToString("") { "%02x".format(it) }
}

if (!sherpaAar.exists()) {
    val url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$sherpaVersion/sherpa-onnx-$sherpaVersion.aar"
    logger.lifecycle("sherpa-onnx AAR 받는 중: $url")
    sherpaAar.parentFile.mkdirs()
    val tmp = File(sherpaAar.path + ".part")
    URI(url).toURL().openStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
    tmp.renameTo(sherpaAar)
}
check(sha256(sherpaAar) == sherpaSha256) {
    "sherpa-onnx AAR 체크섬이 맞지 않습니다. ${sherpaAar.path} 를 지우고 다시 빌드하세요."
}

android {
    namespace = "com.lumen.player"
    compileSdk = 35
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "com.lumen.player"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    // ML Kit 번역과 whisper 네이티브 라이브러리 때문에 범용 APK가 120MB를 넘는다.
    // 휴대폰에는 대부분 arm64-v8a APK(약 40MB)를 설치하면 된다
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }
    // 기기 안 음성 인식(whisper.cpp)
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }
    lint {
        // Media3의 @UnstableApi는 의도적으로 사용한다
        disable += "UnsafeOptInUsageError"
        checkReleaseBuilds = false
    }
    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                "META-INF/BCKEY.DSA",
                "META-INF/BCKEY.SF",
            )
        }
        jniLibs {
            // sherpa-onnx의 C/C++ API 라이브러리는 JNI(Kotlin API)가 쓰지 않는다
            excludes += setOf("**/libsherpa-onnx-c-api.so", "**/libsherpa-onnx-cxx-api.so")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.documentfile)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.exoplayer.dash)
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.media3.session)
    implementation(libs.media3.ui)
    implementation(libs.media3.ffmpeg)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    // 로컬 AI 번역(Gemma 4·Qwen 2.5)과 Claude 번역
    implementation(libs.litertlm.android)
    implementation(libs.anthropic.java)
    implementation(libs.datastore.preferences)

    implementation(libs.coroutines.android)
    implementation(libs.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.coil.compose)
    implementation(libs.coil.video)

    implementation(libs.smbj)
    implementation(libs.commons.net)
    implementation(libs.nfs.client)

    // 기기 안 번역
    implementation(libs.mlkit.translate)
    implementation(libs.mlkit.language.id)
    implementation(libs.coroutines.play.services)
    // 기기 안 SenseVoice 인식(Silero VAD + SenseVoice, onnxruntime)
    implementation(files(sherpaAar))

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.okhttp.mockwebserver)
}

// whisper.cpp는 git 서브모듈이다. git pull만 하고 서브모듈을 안 받았으면 빌드 전에 받아 온다
val initWhisperSubmodule by tasks.registering(Exec::class) {
    val marker = file("src/main/cpp/whisper.cpp/CMakeLists.txt")
    onlyIf { !marker.exists() && rootProject.file(".git").exists() }
    workingDir = rootProject.projectDir
    commandLine("git", "submodule", "update", "--init", "--depth", "1", "app/src/main/cpp/whisper.cpp")
}
tasks.named("preBuild") { dependsOn(initWhisperSubmodule) }
