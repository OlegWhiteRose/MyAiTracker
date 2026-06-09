package com.example.myaipeopletracker

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

class FaceRecognizer(context: Context) {

    private var interpreter: Interpreter
    private val imageSize = 112 // Стандартный размер входа для MobileFaceNet

    init {
        val options = Interpreter.Options().apply {
            numThreads = 4
        }
        interpreter = Interpreter(loadModelFile(context, "mobile_face_net.tflite"), options)
    }

    private fun loadModelFile(context: Context, modelName: String): ByteBuffer {
        val assetFileDescriptor = context.assets.openFd(modelName)
        val fileInputStream = FileInputStream(assetFileDescriptor.fileDescriptor)
        val fileChannel = fileInputStream.channel
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, assetFileDescriptor.startOffset, assetFileDescriptor.declaredLength)
    }

    fun getFaceEmbedding(bitmap: Bitmap): FloatArray {
        // Масштабируем вырезанное лицо под 112x112
        val scaledBitmap = Bitmap.createScaledBitmap(bitmap, imageSize, imageSize, false)
        val byteBuffer = convertBitmapToByteBuffer(scaledBitmap)

        // Массив для вывода (192 числа)
        val output = Array(1) { FloatArray(192) }

        interpreter.run(byteBuffer, output)
        return output[0]
    }

    private fun convertBitmapToByteBuffer(bitmap: Bitmap): ByteBuffer {
        val byteBuffer = ByteBuffer.allocateDirect(4 * imageSize * imageSize * 3)
        byteBuffer.order(ByteOrder.nativeOrder())

        val intValues = IntArray(imageSize * imageSize)
        bitmap.getPixels(intValues, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

        var pixel = 0
        for (i in 0 until imageSize) {
            for (j in 0 until imageSize) {
                val valPixel = intValues[pixel++]
                // Нормализация (pixel - 127.5) / 127.5
                byteBuffer.putFloat(((valPixel shr 16 and 0xFF) - 127.5f) / 127.5f)
                byteBuffer.putFloat(((valPixel shr 8 and 0xFF) - 127.5f) / 127.5f)
                byteBuffer.putFloat(((valPixel and 0xFF) - 127.5f) / 127.5f)
            }
        }
        return byteBuffer
    }
}