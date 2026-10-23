package io.github.eitancohen02.playbackmaker

import ai.onnxruntime.OrtSession
import android.app.Activity
import android.os.Bundle
import android.os.Debug
import android.util.Log
import android.view.WindowManager
import io.github.eitancohen02.playbackmaker.pipeline.ModelManager
import io.github.eitancohen02.playbackmaker.separator.MdxParams
import io.github.eitancohen02.playbackmaker.separator.OnnxSpectrogramModel
import kotlin.concurrent.thread
import kotlin.random.Random

/**
 * Compares onnxruntime session settings on a real phone:
 * `adb shell am start -n io.github.eitancohen02.playbackmaker/.BenchmarkActivity`, then read logcat tag "Benchmark".
 */
class BenchmarkActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The process is paused if the screen turns off mid-benchmark.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val model = ModelManager.modelFile(this)
        val runs = intent.getIntExtra("runs", 6)
        thread {
            val p = MdxParams.INST_HQ_4
            val random = Random(1)
            val input = FloatArray(4 * p.dimF * p.dimT) { random.nextFloat() * 2 - 1 }
            val configs = listOf<Pair<String, (OrtSession.SessionOptions) -> Unit>>(
                "default" to {},
                "no-arena" to { it.setCPUArenaAllocator(false) },
                "no-arena+no-pattern" to { it.setCPUArenaAllocator(false); it.setMemoryPatternOptimization(false) },
            )
            // Two rounds, so phone heat-up affects every config.
            repeat(2) { round ->
                for ((name, configure) in configs) {
                    val options = OrtSession.SessionOptions().also(configure)
                    System.gc()
                    val base = Debug.getNativeHeapAllocatedSize()
                    var peak = base
                    var sampling = true
                    val sampler = thread {
                        while (sampling) {
                            peak = maxOf(peak, Debug.getNativeHeapAllocatedSize())
                            Thread.sleep(50)
                        }
                    }
                    val times = OnnxSpectrogramModel(model.path, options = options).use { onnx ->
                        onnx.run(input) // warm-up
                        List(runs) {
                            val t = System.nanoTime()
                            onnx.run(input)
                            (System.nanoTime() - t) / 1_000_000
                        }
                    }
                    sampling = false
                    sampler.join()
                    Log.i(
                        "Benchmark",
                        "round ${round + 1} $name: avg ${times.average().toInt()} ms/chunk $times, " +
                            "peak native +${(peak - base) / (1 shl 20)} MB",
                    )
                }
            }
            Log.i("Benchmark", "done")
        }
    }
}
