package sh.hnet.gallery.data

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.HardwareBufferRenderer
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.hardware.HardwareBuffer
import androidx.core.graphics.withMatrix
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import sh.hnet.gallery.model.Adjustments

/**
 * Adjust's adjustments drawn (S10a, LOGIKA §21.5): one AGSL shader for the editor's preview and the saved result, so
 * what is seen is what is saved – the formulas of `model/Tone`, line for line, plus sharpness from the neighbours.
 * Colours go to linear light and back in the canvas's own colour space (a Display P3 photo stays P3).
 */
class AdjustShader {
    private val shader = RuntimeShader(SOURCE)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    /** A paint drawing [image] (in its own pixels) with [adjust]; [radius] is sharpness's reach in those pixels. */
    fun paint(image: Bitmap, adjust: Adjustments, radius: Float): Paint {
        shader.setInputShader("image", BitmapShader(image, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { filterMode = BitmapShader.FILTER_MODE_LINEAR })
        shader.setFloatUniform("exposure", adjust.ev.toFloat())
        shader.setFloatUniform("contrast", adjust.contrast / 100f)
        shader.setFloatUniform("highlights", adjust.highlights / 100f)
        shader.setFloatUniform("shadows", adjust.shadows / 100f)
        shader.setFloatUniform("saturation", adjust.saturation / 100f)
        shader.setFloatUniform("warmth", adjust.warmth / 100f)
        shader.setFloatUniform("sharpness", adjust.sharpness / 100f)
        shader.setFloatUniform("radius", radius)
        paint.shader = shader
        return paint
    }

    /** [image] drawn through [matrix] with [adjust] – as it is when there's nothing to adjust. */
    fun draw(canvas: Canvas, image: Bitmap, matrix: Matrix, adjust: Adjustments, plain: Paint) {
        if (adjust.neutral) {
            canvas.drawBitmap(image, matrix, plain)
            return
        }
        val p = paint(image, adjust, radiusFor(min(image.width, image.height)))
        canvas.withMatrix(matrix) { drawRect(0f, 0f, image.width.toFloat(), image.height.toFloat(), p) }
    }

    companion object {
        /**
         * Sharpness's reach for a photo whose shorter side is [shorter] pixels: a share of it, so 2 MP and 50 MP look
         * alike (D39), and the preview – the photo made smaller – the same as the save.
         */
        fun radiusFor(shorter: Int): Float = max(1f, shorter / 800f)

        /**
         * [out] (a software bitmap) adjusted in place, tile by tile on the GPU – a RuntimeShader draws only on a
         * hardware canvas – each tile with a margin for sharpness's neighbours, only its middle written back.
         */
        fun applyInPlace(out: Bitmap, adjust: Adjustments) {
            if (adjust.neutral) return
            val radius = radiusFor(min(out.width, out.height))
            val margin = ceil(radius * 2).toInt() + 2
            val inner = TILE - 2 * margin
            val f16 = out.config == Bitmap.Config.RGBA_F16
            val buffer = HardwareBuffer.create(TILE, TILE, if (f16) HardwareBuffer.RGBA_FP16 else HardwareBuffer.RGBA_8888, 1, HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT)
            val renderer = HardwareBufferRenderer(buffer)
            val node = RenderNode("adjust").apply { setPosition(0, 0, TILE, TILE) }
            val adjuster = AdjustShader()
            val target = Canvas(out)
            val copy = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) }
            val space = out.colorSpace ?: android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB)
            try {
                var top = 0
                while (top < out.height) {
                    val rows = min(inner, out.height - top)
                    var left = 0
                    while (left < out.width) {
                        val cols = min(inner, out.width - left)
                        // The tile with its margin, cut at the photo's edges
                        val x0 = max(0, left - margin)
                        val y0 = max(0, top - margin)
                        val x1 = min(out.width, left + cols + margin)
                        val y1 = min(out.height, top + rows + margin)
                        val source = Bitmap.createBitmap(out, x0, y0, x1 - x0, y1 - y0)
                        val canvas = node.beginRecording()
                        canvas.drawColor(0, PorterDuff.Mode.CLEAR)
                        canvas.drawRect(0f, 0f, source.width.toFloat(), source.height.toFloat(), adjuster.paint(source, adjust, radius))
                        node.endRecording()
                        renderer.setContentRoot(node)
                        val done = CountDownLatch(1)
                        renderer.obtainRenderRequest().setColorSpace(space).draw({ it.run() }) { result ->
                            result.fence.await(Duration.ofSeconds(10))
                            done.countDown()
                        }
                        if (!done.await(15, TimeUnit.SECONDS)) error("Adjusting timed out")
                        val drawn = Bitmap.wrapHardwareBuffer(buffer, space) ?: error("No tile")
                        val soft = drawn.copy(if (f16) Bitmap.Config.RGBA_F16 else Bitmap.Config.ARGB_8888, false)
                        drawn.recycle()
                        source.recycle()
                        // Only the tile's middle back: its margin was only for the neighbours
                        val from = Rect(left - x0, top - y0, left - x0 + cols, top - y0 + rows)
                        target.drawBitmap(soft, from, Rect(left, top, left + cols, top + rows), copy)
                        soft.recycle()
                        left += cols
                    }
                    top += rows
                }
            } finally {
                renderer.close()
                node.discardDisplayList()
                buffer.close()
            }
        }

        /** A tile's side: well under every GPU's texture limit. */
        private const val TILE = 2048

        // The formulas of model/Tone (keep them the same): exposure, warmth, contrast, highlights and shadows on the
        // luminance, saturation, and the soft knee only for what was brightened; sharpness first, on the encoded colour
        private const val SOURCE = """
uniform shader image;
uniform float exposure;
uniform float contrast;
uniform float highlights;
uniform float shadows;
uniform float saturation;
uniform float warmth;
uniform float sharpness;
uniform float radius;

const float3 LUMA = float3(0.2126, 0.7152, 0.0722);
const float KNEE = 0.8;

float knee(float m) {
    if (m <= KNEE) return m;
    float x = (m - KNEE) / (1.0 - KNEE);
    return KNEE + (1.0 - KNEE) * x / (1.0 + x);
}

half4 main(float2 p) {
    half4 c = image.eval(p);
    if (c.a <= 0.0) return c;
    float3 rgb = float3(c.rgb) / float(c.a);
    if (sharpness != 0.0) {
        float d = radius * 0.7071;
        half4 n = image.eval(p + float2(radius, 0.0)) + image.eval(p - float2(radius, 0.0))
            + image.eval(p + float2(0.0, radius)) + image.eval(p - float2(0.0, radius))
            + image.eval(p + float2(d, d)) + image.eval(p - float2(d, d))
            + image.eval(p + float2(d, -d)) + image.eval(p - float2(d, -d));
        float3 blur = float3(n.rgb) / max(float(n.a), 0.0001);
        rgb = sharpness > 0.0 ? rgb + (rgb - blur) * sharpness * 1.5 : mix(rgb, blur, -sharpness);
    }
    float3 lin = toLinearSrgb(half3(rgb));
    float m0 = max(lin.r, max(lin.g, lin.b));
    lin *= exp2(exposure);
    lin.r *= 1.0 + 0.2 * warmth;
    lin.b *= 1.0 - 0.2 * warmth;
    float l = max(dot(lin, LUMA), 0.000001);
    float lc = 0.18 * pow(l / 0.18, 1.0 + 0.5 * contrast);
    float pl = sqrt(clamp(l, 0.0, 1.0));
    float wh = smoothstep(0.5, 1.0, pl);
    float ws = 1.0 - smoothstep(0.0, 0.5, pl);
    float lt = lc * exp2(highlights * wh + 1.2 * shadows * ws);
    lin *= lt / l;
    float l2 = dot(lin, LUMA);
    lin = l2 + (lin - l2) * (1.0 + saturation);
    float m = max(lin.r, max(lin.g, lin.b));
    if (m > m0 && m > KNEE) {
        float from = clamp(m0, 0.0, 1.0);
        lin *= (from + knee(m) - knee(from)) / m;
    }
    half3 outRgb = fromLinearSrgb(half3(lin));
    return half4(outRgb * c.a, c.a);
}
"""
    }
}
