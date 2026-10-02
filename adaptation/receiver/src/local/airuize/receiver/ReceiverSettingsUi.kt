package local.airuize.receiver

import android.app.Activity
import android.app.AlertDialog
import android.widget.*
import android.text.InputType

/** API18 presentation of upstream display/audio/network preferences. */
class ReceiverSettingsUi(private val activity:Activity,private val log:(String)->Unit) {
    private val p=activity.getSharedPreferences("receiver-ui",0)
    private val s=ReceiverSettings.read(activity)
    private val strings=LegacyStrings(activity)
    private fun tr(zh:String,en:String,ar:String)=strings.translate(zh,en,ar)
    fun edit(index:Int) { when(index) { 0->display(); 1->audio(); 2->network(); 3->lifecycle(); 4->language(); 5->codecs() } }
    fun show() {
        val labels=arrayOf(tr("显示设置","Display","الشاشة"),tr("声音设置","Audio","الصوت"),tr("网络模式","Network","الشبكة"),tr("连接与启动","Connection and boot","الاتصال وبدء التشغيل"),tr("语言","Language","اللغة"),"HEVC / Opus")
        AlertDialog.Builder(activity).setTitle(tr("设置","Settings","الإعدادات")).setItems(labels) { _,i -> when(i) { 0->display(); 1->audio(); 2->network(); 3->lifecycle(); 4->language(); 5->codecs() } }.setNegativeButton(tr("关闭","Close","إغلاق"),null).show()
    }
    private fun form(title:String,build:(LinearLayout,MutableList<()->Unit>)->Unit) {
        val box=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL; setPadding(20,8,20,8) }
        val save=ArrayList<()->Unit>(); build(box,save)
        AlertDialog.Builder(activity).setTitle(title).setView(ScrollView(activity).apply { addView(box) })
            .setPositiveButton(tr("保存","Save","حفظ")) { _,_-> save.forEach { it() }; log(tr("设置已保存，重新连接生效","Saved; reconnect to apply","تم الحفظ؛ أعد الاتصال للتطبيق")) }
            .setNegativeButton(tr("取消","Cancel","إلغاء"),null).show()
    }
    private fun slider(box:LinearLayout,save:MutableList<()->Unit>,key:String,label:String,value:Int,min:Int,max:Int,step:Int=1) {
        val text=TextView(activity); box.addView(text)
        val bar=SeekBar(activity).apply { this.max=(max-min)/step; progress=(value-min)/step }
        text.text="$label: ${min+bar.progress*step}"
        bar.setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(view:SeekBar,progress:Int,fromUser:Boolean) { text.text="$label: ${min+progress*step}" }
            override fun onStartTrackingTouch(view:SeekBar) {}
            override fun onStopTrackingTouch(view:SeekBar) {}
        }); box.addView(bar); save.add { p.edit().putInt(key,min+bar.progress*step).commit() }
    }
    private fun check(box:LinearLayout,save:MutableList<()->Unit>,key:String,label:String,value:Boolean) {
        val check=CheckBox(activity).apply { text=label; isChecked=value }; box.addView(check); save.add { p.edit().putBoolean(key,check.isChecked).commit() }
    }
    private fun display()=form(strings.text("display_and_performance")) { box,save ->
        slider(box,save,"width",tr("宽度 px","Width px","العرض"),s.width,640,1920,16)
        slider(box,save,"height",tr("高度 px","Height px","الارتفاع"),s.height,360,1080,8)
        slider(box,save,"fps",strings.text("frame_rate"),s.fps,30,60,5)
        slider(box,save,"scale",tr("图标字号缩放（3=0.3x，10=1.0x）","Display scale (3=0.3x, 10=1.0x)","حجم العرض"),s.scale,3,10)
        slider(box,save,"physicalMm",strings.text("physical_length")+" mm",s.physicalMm,100,400,50)
        check(box,save,"physicalHeight",tr("参考高度（关闭时为宽度）","Reference height (otherwise width)","مرجع الارتفاع"),s.physicalHeight)
        for((key,label,value) in listOf(Triple("insetTop","marker_up",s.insetTop),Triple("insetBottom","marker_down",s.insetBottom),Triple("insetLeft","marker_left",s.insetLeft),Triple("insetRight","marker_right",s.insetRight)))
            slider(box,save,key,strings.text("safe_area")+" · "+strings.text(label)+" px",if(value<0) s.inset else value,0,120,4)
        check(box,save,"drawOutside",strings.text("allow_carplay_ui_outside_the_safe_area"),s.drawOutside)
        check(box,save,"rightHandDrive",strings.text("right_hand_drive"),s.rightHandDrive)
    }
    private fun audio()=form(tr("声音","Audio","الصوت")) { box,save ->
        slider(box,save,"volume",tr("应用音量 %","App volume %","مستوى الصوت"),s.volume,0,100,5)
        check(box,save,"audioFocus",tr("协调音频焦点","Coordinate audio focus","تنسيق تركيز الصوت"),s.audioFocus)
        check(box,save,"navigationDuck",tr("导航压低音乐（原版默认关闭）","Duck music for navigation (upstream default: off)","خفض الموسيقى أثناء التوجيه"),s.navigationDuck)
        val spinner=Spinner(activity); val presets=listOf(300,500,1000)
        spinner.adapter=ArrayAdapter(activity,android.R.layout.simple_spinner_dropdown_item,presets.map { strings.text("music_buffer")+": $it ms" }); spinner.setSelection(presets.indexOf(s.bufferMillis)); box.addView(spinner)
        save.add { p.edit().putInt("bufferMillis",presets[spinner.selectedItemPosition]).commit() }
    }
    private fun network() {
        val modes=listOf("saved","app","direct","router")
        val labels=arrayOf(tr("车机已有热点","Saved head-unit hotspot","نقطة اتصال السيارة"),tr("应用管理 WPA2 热点","App-managed WPA2 hotspot","نقطة اتصال التطبيق"),"Wi-Fi Direct (group owner)",tr("同一 WPA2 路由器","Shared WPA2 router","موجه WPA2 مشترك"))
        AlertDialog.Builder(activity).setTitle(tr("网络（重新连接生效）","Network (reconnect required)","الشبكة")).setSingleChoiceItems(labels,modes.indexOf(s.networkMode)) { dialog,i ->
            p.edit().putString("networkMode",modes[i]).commit(); dialog.dismiss()
            if(modes[i]=="router") {
                val input=EditText(activity).apply { inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD; setText(p.getString("routerPassword","")) }
                AlertDialog.Builder(activity).setTitle(tr("路由器 WPA2 密码","Router WPA2 password","كلمة مرور الموجه")).setView(input).setPositiveButton(tr("保存","Save","حفظ")) { _,_->
                    if(input.text.length in 8..63) p.edit().putString("routerPassword",input.text.toString()).commit() else log("WPA2 password must be 8–63 characters")
                }.setNegativeButton(tr("取消","Cancel","إلغاء"),null).show()
            }
        }.setNegativeButton(tr("取消","Cancel","إلغاء"),null).show()
    }
    private fun lifecycle()=form(tr("连接与启动","Connection and boot","الاتصال")) { box,save ->
        check(box,save,"autoOpenConnect",tr("打开软件自动无线连接（默认关闭）","Automatically connect wirelessly on open (default: off)","الاتصال التلقائي عند الفتح"),p.getBoolean("autoOpenConnect",false))
        check(box,save,"reconnect",tr("断线持续重连","Reconnect after disconnect","إعادة الاتصال"),s.reconnect)
        check(box,save,"background",tr("返回桌面后保持会话","Keep session in background","الاستمرار في الخلفية"),s.background)
        check(box,save,"bootConnect",strings.text("start_carplay_automatically_after_device_boot"),s.bootConnect)
    }
    private fun language() { val values=listOf("system","zh","en","ar","es","ru")
        AlertDialog.Builder(activity).setTitle("Language / 语言 / اللغة").setSingleChoiceItems(arrayOf(strings.text("language_system_default"),"简体中文","English","العربية","Español","Русский"),values.indexOf(s.language)) { dialog,i->
            p.edit().putString("language",values[i]).commit(); dialog.dismiss(); activity.recreate()
        }.setNegativeButton(tr("取消","Cancel","إلغاء"),null).show()
    }
    private fun codecs()=form(tr("编解码器能力","Codec capabilities","قدرات الترميز")) { box,save ->
        box.addView(TextView(activity).apply { text="HEVC decoder: ${CodecCapabilities.hevc()}\nOpus decoder + encoder: ${CodecCapabilities.opus()}\nH.264 / PCM / AAC remain available. Missing codecs are never advertised." })
        check(box,save,"hevc",tr("可用时优先 HEVC","Prefer HEVC when available","استخدام HEVC عند توفره"),s.hevc)
        check(box,save,"opus",tr("可用时允许 Opus","Allow Opus when available","استخدام Opus عند توفره"),s.opus)
    }
}
