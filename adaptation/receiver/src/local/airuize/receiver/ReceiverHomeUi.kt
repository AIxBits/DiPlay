package local.airuize.receiver

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.View
import android.view.Gravity
import android.widget.*

/** API18 version of upstream's home / settings / projection navigation. */
class ReceiverHomeUi(private val activity: Activity, private val text: (String,String,String)->String,
    private val wireless:()->Unit, private val usb:()->Unit, private val choose:()->Unit,
    private val disconnect:()->Unit, private val projection:()->Unit, private val settings:()->Unit,
    private val diagnostics:()->Unit, private val headUnit:()->Unit,private val settingsPage:(LinearLayout)->Unit) {
    val view=ScrollView(activity).apply { setBackgroundColor(Color.rgb(12,17,27)); isFillViewport=true }
    val status=TextView(activity).apply { setTextColor(Color.rgb(168,182,202)); textSize=17f }
    var page="home"; private set
    private fun dp(n:Int)=(n*activity.resources.displayMetrics.density+0.5f).toInt()
    private fun label(s:String,size:Float)=TextView(activity).apply { setText(s); textSize=size; setTextColor(Color.WHITE); setPadding(0,dp(8),0,dp(12)) }
    private fun rounded(color:Int)=GradientDrawable().apply { setColor(color); setCornerRadius(dp(20).toFloat()); setStroke(dp(1),Color.rgb(42,56,75)) }
    private fun button(box:LinearLayout,s:String,primary:Boolean=false,action:()->Unit) {
        box.addView(Button(activity).apply {
            setText(s); textSize=18f; setAllCaps(false)
            setTextColor(if(primary) Color.rgb(12,17,27) else Color.rgb(241,245,252))
            typeface=Typeface.create("sans-serif",Typeface.NORMAL)
            background=StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed),rounded(Color.rgb(65,85,110)))
                addState(intArrayOf(),rounded(if(primary) Color.rgb(166,200,255) else Color.rgb(21,30,44)))
            }
            setPadding(dp(16),0,dp(16),0); setOnClickListener { action() }
        },LinearLayout.LayoutParams(-1,dp(60)).apply { bottomMargin=dp(10) })
    }
    private fun column()=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL }
    private fun logo(size:Int)=ImageView(activity).apply {
        val id=activity.resources.getIdentifier("ic_carplay","drawable",activity.packageName)
        setImageResource(id); contentDescription="CarPlay"; scaleType=ImageView.ScaleType.FIT_CENTER
        layoutParams=LinearLayout.LayoutParams(dp(size),dp(size))
    }
    fun render(next:String="home") {
        page=next; view.removeAllViews()
        val box=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(32),dp(20),dp(32),dp(24)) }
        view.addView(box)
        val header=LinearLayout(activity).apply { setGravity(Gravity.CENTER_VERTICAL) }
        header.addView(logo(36))
        header.addView(label("DiPlay",28f),LinearLayout.LayoutParams(0,-2,1f))
        header.addView(Button(activity).apply { setText(if(page=="home") text("返回车机","Head unit","السيارة") else text("返回","Back","رجوع")); setOnClickListener { if(page=="home") headUnit() else render() } })
        box.addView(header)
        if(page=="settings") {
            settingsPage(box)
        } else {
            val hero=column().apply {
                addView(label(text("你的手机，你的旅途","YOUR PHONE. YOUR DRIVE.","هاتفك ورحلتك"),12f).apply { setTextColor(Color.rgb(166,200,255)) })
                addView(label(text("熟悉的驾驶体验","A familiar drive.","تجربة قيادة مألوفة"),36f))
                addView(label(text("地图、音乐和通话，就在你的车机上。","Your maps, music and conversations. CarPlay, right here on your head unit.","الخرائط والموسيقى والمكالمات"),19f))
            }
            val branding=column().apply { setGravity(Gravity.CENTER); addView(logo(96)) }
            val card=column().apply { background=rounded(Color.rgb(21,30,44)); setPadding(dp(24),dp(24),dp(24),dp(24)) }
            card.addView(label(text("无线 CarPlay","WIRELESS CARPLAY","CarPlay لاسلكي"),12f).apply { setTextColor(Color.rgb(166,200,255)) })
            (status.parent as? LinearLayout)?.removeView(status); card.addView(status)
            button(card,text("连接手机／返回 CarPlay","Connect phone / return to CarPlay","اتصال الهاتف"),true,wireless)
            card.addView(label(text("保持车机热点可用，点击连接手机开始无线连接。","Keep the head-unit hotspot available. Tap Connect phone to start wireless CarPlay.","الاتصال التلقائي للهاتف المحفوظ"),14f))
            button(card,text("选择 iPhone","Choose iPhone","اختيار iPhone"),action=choose)
            button(card,text("断开连接","Disconnect","قطع الاتصال"),action=disconnect)
            val right=column()
            button(right,text("使用 USB 连接","Connect with USB","اتصال USB"),action=usb)
            right.addView(label(text("将 iPhone 插入车机 USB 数据接口；此车机通道仍需实测。","Plug your iPhone into a USB data port. This head unit still requires USB verification.","قم بتوصيل iPhone عبر USB"),14f))
            button(right,text("设置","Settings","الإعدادات")) { render("settings") }
            button(right,text("诊断与测试","Diagnostics and tests","التشخيص"),action=diagnostics)
            button(right,text("查看投屏画面","Show projection","عرض الشاشة"),action=projection)
            right.addView(label("Public preview · 0.2.36 · Android 4.3",12f))
            if(activity.resources.configuration.screenWidthDp>=850) {
                fun columns(left:View,rightView:View)=LinearLayout(activity).apply {
                    addView(left,LinearLayout.LayoutParams(0,-2,1.6f))
                    addView(View(activity),LinearLayout.LayoutParams(dp(40),1))
                    addView(rightView,LinearLayout.LayoutParams(0,-2,1f))
                }
                box.addView(columns(hero,branding))
                box.addView(View(activity),LinearLayout.LayoutParams(1,dp(26)))
                box.addView(columns(card,right))
            } else {
                box.addView(hero); box.addView(card); box.addView(branding); box.addView(right)
            }
        }
    }
}
