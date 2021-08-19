package com.dji.droneparking.model

import android.content.Context
import android.graphics.RectF
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import java.util.*

object Util {
    private const val DOWNSAMPLE_FACTOR = 2
    fun upscaleRect(downsampledFrameRect: RectF): RectF {
        return RectF(
            downsampledFrameRect.left * DOWNSAMPLE_FACTOR,
            downsampledFrameRect.top * DOWNSAMPLE_FACTOR,
            downsampledFrameRect.right * DOWNSAMPLE_FACTOR,
            downsampledFrameRect.bottom * DOWNSAMPLE_FACTOR
        )
    }

    fun downscaleRect(fullFrameRect: RectF): RectF {
        return RectF(
            fullFrameRect.left / DOWNSAMPLE_FACTOR,
            fullFrameRect.top / DOWNSAMPLE_FACTOR,
            fullFrameRect.right / DOWNSAMPLE_FACTOR,
            fullFrameRect.bottom / DOWNSAMPLE_FACTOR
        )
    }

    /// Return a COPY of the given list in reverse order.
    fun <T> reverseList(toReverse: List<T?>?): List<T?> {
        val copiedList: List<T?> = ArrayList(toReverse)
        Collections.reverse(copiedList)
        return copiedList
    }

    fun <T> getListClone(from: List<T>?): List<T> {
        return ArrayList(from)
    }

    fun bitmapFromVectorDrawable(context: Context, resid: Int): Bitmap? {
        val vectorDrawable = context.getDrawable(resid) ?: return null
        val w = vectorDrawable.intrinsicWidth
        val h = vectorDrawable.intrinsicHeight
        vectorDrawable.setBounds(0, 0, w, h)
        val map = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(map)
        vectorDrawable.draw(canvas)
        return map
    }
}