import java.util.Properties

plugins {
    id("com.android.application")
}

val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
val libjxlDir = rootProject.file(localProperties.getProperty("libjxl.dir", "third_party/libjxl"))

android {
    namespace = "dev.s24cam"
    compileSdk = 36
    ndkVersion = "30.0.16248370"

    defaultConfig {
        // Samsung's cameraserver only lets allowlisted package names open the hidden
        // camera IDs (20, 52, 54, 56, 58). This id is on that list and is not
        // installed on the target phone.
        applicationId = "com.samsung.android.ruler"
        minSdk = 34
        targetSdk = 36
        versionCode = 34
        versionName = "0.5.3"
        ndk {
            abiFilters += "arm64-v8a"
        }
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DLIBJXL_DIR=${libjxlDir.absolutePath.replace('\\', '/')}",
                    "-DANDROID_STL=c++_static",
                    "-DCMAKE_POLICY_VERSION_MINIMUM=3.5"
                )
                targets += listOf("s24jxl", "s24lumdir")
            }
        }
    }

    buildTypes {
        // Debuggable builds run the DNG encoder several times slower, so test with
        // release (signed with the local debug key so it installs over the debug build).
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
            externalNativeBuild {
                cmake {
                    arguments += "-DCMAKE_BUILD_TYPE=Release"
                }
            }
        }
    }

    buildFeatures {
        buildConfig = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "4.1.2"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

// Full-frame reconstruction only; --args="--write ..." / "--compare ..." enables DN comparison.
tasks.register<JavaExec>("directional16Bench") {
    dependsOn("compileDebugUnitTestJavaWithJavac")
    mainClass.set("dev.s24cam.dng.Directional16Bench")
    classpath = files(provider {
        tasks.named<org.gradle.api.tasks.testing.Test>("testDebugUnitTest").get().classpath
    })
    workingDir(rootProject.projectDir)
    maxHeapSize = "600m"
}

// Enforce a heap cap well below Android's 512 MB; native stores are bounded rings.
tasks.register<JavaExec>("lumdir16Bench") {
    dependsOn("compileDebugUnitTestJavaWithJavac")
    mainClass.set("dev.s24cam.dng.Lumdir16Main")
    classpath = files(provider {
        tasks.named<org.gradle.api.tasks.testing.Test>("testDebugUnitTest").get().classpath
    })
    workingDir(rootProject.projectDir)
    maxHeapSize = "280m"
    jvmArgs("-XX:MaxDirectMemorySize=1g")
    val hostMedian = rootProject.file("build/lumdir-host")
    if (hostMedian.resolve("s24lumdir.dll").exists()) jvmArgs("-Djava.library.path=${hostMedian.absolutePath}")
}
tasks.withType<Test>().configureEach {
    maxHeapSize = "512m"
    jvmArgs("-XX:MaxDirectMemorySize=1g")
    // Default desktop tests exercise Java fallback; opt in after building the host DLL.
    if (providers.gradleProperty("lumdirNativeTests").isPresent) {
        val hostKernels = rootProject.file("build/lumdir-host")
        require(hostKernels.resolve("s24lumdir.dll").exists()) { "Run tools/build-lumdir-host.cmd first" }
        inputs.file(hostKernels.resolve("s24lumdir.dll"))
        jvmArgs("-Djava.library.path=${hostKernels.absolutePath}", "-Ds24cam.lumdir.requireNative=true")
    }
}
