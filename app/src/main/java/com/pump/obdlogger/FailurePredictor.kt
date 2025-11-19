package com.pump.obdlogger

import android.content.Context
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder

class FailurePredictor(context: Context) {

    private val min = floatArrayOf(
        0.0f,
        -0.12903225806451613f,
        -6.0f
    )

    private val scale = floatArrayOf(
        8.350033400133601e-05f,
        0.00016129032258064516f,
        0.06666666666666667f
    )

    private val windowSize = 30
    private val nFeatures = min.size
    private val buffer = Array(windowSize) { FloatArray(nFeatures) }
    private var index = 0
    private var filled = false

    private val interpreter: Interpreter

    init {
        val modelBytes = context.assets.open("lstm_model.tflite").readBytes()
        val bb = ByteBuffer.allocateDirect(modelBytes.size)
        bb.order(ByteOrder.nativeOrder())
        bb.put(modelBytes)
        interpreter = Interpreter(bb)
    }

    fun addSample(rawFeatures: FloatArray): FloatArray? {
        val scaled = FloatArray(nFeatures)
        var i = 0
        while (i < nFeatures) {
            scaled[i] = rawFeatures[i] * scale[i] + min[i]
            i++
        }
        buffer[index] = scaled
        index++
        if (index >= windowSize) {
            index = 0
            filled = true
        }
        if (!filled) return null
        val input = Array(1) { Array(windowSize) { FloatArray(nFeatures) } }
        var t = 0
        var pos = if (index == 0) 0 else index
        while (t < windowSize) {
            val srcIdx = (pos + t) % windowSize
            input[0][t] = buffer[srcIdx]
            t++
        }
        val output = Array(1) { FloatArray(1) }
        interpreter.run(input, output)
        return output[0]
    }
}
