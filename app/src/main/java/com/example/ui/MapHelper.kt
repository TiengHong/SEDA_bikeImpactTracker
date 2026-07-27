package com.example.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import com.example.data.ImpactRecord

object MapHelper {

    /**
     * Generates a circular Drawable icon containing the numerical G-force of the impact points.
     * The circle matches the severity category colors: Red for Severe, Amber for Moderate, Teal for Mild.
     */
    fun createCircleMarkerIcon(context: Context, record: ImpactRecord): Drawable {
        val size = 96 // Width and height in pixels
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Determine colors based on impact magnitude
        val (fillColor, strokeColor) = when {
            record.impactG >= 4.0f -> {
                // Severe: Red
                Pair(Color.argb(180, 244, 43, 80), Color.rgb(255, 30, 80))
            }
            record.impactG >= 2.5f -> {
                // Moderate: Amber / Orange
                Pair(Color.argb(190, 255, 140, 0), Color.rgb(255, 120, 0))
            }
            else -> {
                // Mild: Cozy Teal
                Pair(Color.argb(180, 0, 150, 136), Color.rgb(0, 130, 120))
            }
        }

        // Draw outer translucent outer aura ring
        val glowPaint = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.STROKE
            color = strokeColor
            alpha = 80
            strokeWidth = 4f
        }
        canvas.drawCircle(size / 2f, size / 2f, size / 2f - 4f, glowPaint)

        // Draw solid content circle
        val fillPaint = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.FILL
            color = fillColor
        }
        canvas.drawCircle(size / 2f, size / 2f, size / 2f - 10f, fillPaint)

        // Draw contrasting border
        val borderPaint = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.STROKE
            color = Color.WHITE
            strokeWidth = 3f
        }
        canvas.drawCircle(size / 2f, size / 2f, size / 2f - 10f, borderPaint)

        // Draw numerical string (e.g. "4.1G" or "1.5G")
        val textString = String.format("%.1fG", record.impactG)
        val textPaint = Paint().apply {
            isAntiAlias = true
            color = Color.WHITE
            textSize = 24f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }

        // Calculate text vertical center bounds alignment
        val textBounds = Rect()
        textPaint.getTextBounds(textString, 0, textString.length, textBounds)
        val yOffset = (textBounds.height() / 2) - 2f

        canvas.drawText(textString, size / 2f, size / 2f + yOffset, textPaint)

        return BitmapDrawable(context.resources, bitmap)
    }

    /**
     * Generates a sleek, modern, blue location dot with a translucent pulse halo for the user's android device GPS position.
     */
    fun createUserLocationIcon(context: Context): Drawable {
        val size = 72 // Width and height in pixels
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Draw translucent outer pulsing halo (semi-transparent blue)
        val haloPaint = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.FILL
            color = Color.argb(60, 59, 130, 246) // Blue 500 equivalent translucent
        }
        canvas.drawCircle(size / 2f, size / 2f, size / 2f - 2f, haloPaint)

        // Draw solid blue interior circle
        val dotPaint = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.FILL
            color = Color.rgb(37, 99, 235) // Deep blue
        }
        canvas.drawCircle(size / 2f, size / 2f, size / 2f - 14f, dotPaint)

        // Draw a neat white border around the inner solid dot
        val borderPaint = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.STROKE
            color = Color.WHITE
            strokeWidth = 4f
        }
        canvas.drawCircle(size / 2f, size / 2f, size / 2f - 14f, borderPaint)

        return BitmapDrawable(context.resources, bitmap)
    }
}
