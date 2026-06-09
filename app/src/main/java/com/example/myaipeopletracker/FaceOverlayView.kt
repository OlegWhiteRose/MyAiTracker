package com.example.myaipeopletracker

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View

// Новый класс для передачи готовых данных на отрисовку
data class RecognizedFace(val boundingBox: Rect, val id: String)

class FaceOverlayView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    private var faces: List<RecognizedFace> = emptyList()
    private var imageWidth: Int = 1
    private var imageHeight: Int = 1
    private var isFrontMode: Boolean = true

    private val boxPaint = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        strokeWidth = 8.0f
    }

    private val textPaint = Paint().apply {
        color = Color.RED
        textSize = 60.0f
        style = Paint.Style.FILL
        isFakeBoldText = true
    }

    fun setFaces(faces: List<RecognizedFace>, imageWidth: Int, imageHeight: Int, isFrontMode: Boolean) {
        this.faces = faces
        this.imageWidth = imageWidth
        this.imageHeight = imageHeight
        this.isFrontMode = isFrontMode
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (faces.isEmpty()) return

        val scaleX = width.toFloat() / imageHeight.toFloat()
        val scaleY = height.toFloat() / imageWidth.toFloat()

        for (face in faces) {
            val boundingBox = face.boundingBox

            val rawLeft = boundingBox.left * scaleX
            val rawRight = boundingBox.right * scaleX
            val top = boundingBox.top * scaleY
            val bottom = boundingBox.bottom * scaleY

            val left = if (isFrontMode) width - rawRight else rawLeft
            val right = if (isFrontMode) width - rawLeft else rawRight

            val rect = Rect(left.toInt(), top.toInt(), right.toInt(), bottom.toInt())
            canvas.drawRect(rect, boxPaint)

            val label = "id: ${face.id}"
            canvas.drawText(label, left, top - 15, textPaint)
        }
    }
}