package local.airuize.receiver
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.shilapi.xcertplay.airplay.AirPlayIcon
import java.io.ByteArrayOutputStream
object LegacyOem {
    fun icons(): List<AirPlayIcon> = listOf(120, 180).map { size ->
        val bitmap = Bitmap.createBitmap(size,size,Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap); canvas.drawColor(Color.rgb(30,40,55))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; strokeWidth = size/14f; style = Paint.Style.STROKE }
        val path = android.graphics.Path().apply { moveTo(size*.2f,size*.48f); lineTo(size*.5f,size*.22f); lineTo(size*.8f,size*.48f); moveTo(size*.3f,size*.43f); lineTo(size*.3f,size*.78f); lineTo(size*.7f,size*.78f); lineTo(size*.7f,size*.43f) }
        canvas.drawPath(path,paint)
        val bytes = ByteArrayOutputStream(); bitmap.compress(Bitmap.CompressFormat.PNG,100,bytes); bitmap.recycle()
        AirPlayIcon(size,size,bytes.toByteArray())
    }
}
