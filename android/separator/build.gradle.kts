// Pure-Kotlin MDX-Net separation (STFT + ONNX model + overlap-add), shared by the Android app
// and testable on a desktop JVM against reference data from tools/make_mdx_golden.py.
plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("com.github.wendykierp:JTransforms:3.2")
    // The app supplies onnxruntime-android, which has the same ai.onnxruntime API.
    compileOnly("com.microsoft.onnxruntime:onnxruntime:1.31.0")

    testImplementation("com.microsoft.onnxruntime:onnxruntime:1.31.0")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "4g"
    // Model file for the end-to-end tests (skipped when it's missing).
    systemProperty(
        "mdx.model",
        System.getenv("MDX_MODEL")
            ?: "${System.getProperty("user.home")}/.cache/playback-maker/models/UVR-MDX-NET-Inst_HQ_4.onnx",
    )
}
