package local.airuize.receiver

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.widget.*

/** API18 settings cards following DiPlayActivity.settings and connectionSetup. */
class ReceiverSettingsPage(private val activity:Activity,private val log:(String)->Unit,
    private val phone:()->Unit,private val usb:()->Unit,private val export:()->Unit,
    private val logs:()->Unit,private val diagnostics:()->Unit,private val mediaTest:()->Unit,
    private val audioTest:()->Unit,private val preview:(Int)->Unit,private val apply:()->Unit,
    private val upload:()->Unit,private val retryUpload:()->Unit,private val uploadDescription:()->Unit,private val clearUploads:()->Unit) {
    private val p=activity.getSharedPreferences("receiver-ui",0)
    private val s=ReceiverSettings.read(activity)
    private val strings=LegacyStrings(activity)
    private fun tr(zh:String,en:String)=strings.translate(zh,en,en)
    private fun dp(n:Int)=(n*activity.resources.displayMetrics.density+0.5f).toInt()
    private fun text(box:LinearLayout,value:String,size:Float=15f) {
        box.addView(TextView(activity).apply { text=value; textSize=size; setTextColor(Color.rgb(168,182,202)); setPadding(0,dp(6),0,dp(10)) })
    }
    private fun button(box:LinearLayout,title:String,action:()->Unit) {
        box.addView(Button(activity).apply { text=title; textSize=18f; setAllCaps(false); setTextColor(Color.WHITE)
            background=GradientDrawable().apply { setColor(Color.rgb(21,30,44)); setCornerRadius(dp(20).toFloat()); setStroke(dp(1),Color.rgb(42,56,75)) }
            setOnClickListener { action() }
        },LinearLayout.LayoutParams(-1,dp(58)).apply { topMargin=dp(8) })
    }
    private fun section(parent:LinearLayout,title:String,build:(LinearLayout)->Unit) {
        val card=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(24),dp(16),dp(24),dp(20)); background=GradientDrawable().apply { setColor(Color.rgb(21,30,44)); setCornerRadius(dp(20).toFloat()); setStroke(dp(1),Color.rgb(42,56,75)) } }
        parent.addView(card,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(20) })
        text(card,title,23f); build(card)
    }
    private fun toggle(box:LinearLayout,key:String,title:String,value:Boolean,enabled:Boolean=true) {
        box.addView(CheckBox(activity).apply { text=title; textSize=18f; setTextColor(Color.WHITE); isChecked=value; isEnabled=enabled
            setOnCheckedChangeListener { _,checked -> p.edit().putBoolean(key,checked).commit(); log("设置更新：$key=$checked；连接参数需点击应用并重新连接") }
        })
    }
    private fun choice(box:LinearLayout,key:String,title:String,values:List<Int>,labels:List<String>,current:Int) {
        lateinit var control:Button
        button(box,title+": "+labels[values.indexOf(current).coerceAtLeast(0)]) {
            AlertDialog.Builder(activity).setTitle(title).setSingleChoiceItems(labels.toTypedArray(),values.indexOf(p.getInt(key,current))) { dialog,index ->
                p.edit().putInt(key,values[index]).commit(); control.text=title+": "+labels[index]; dialog.dismiss()
                log("设置更新：$key=${values[index]}；重新连接生效")
            }.setNegativeButton(tr("取消","Cancel"),null).show()
        }
        control=box.getChildAt(box.childCount-1) as Button
    }
    fun render(parent:LinearLayout) {
        text(parent,tr("你的旅途，由你定义","Your drive, your way."),34f)
        text(parent,tr("更改连接参数后，点击底部“应用并重新连接”。诊断工具始终保留。","Apply and reconnect after changing connection parameters. Diagnostics remain available."))
        section(parent,tr("连接设置","Connection setup")) { box ->
            text(box,tr("选择连接方式、配对手机并配置无线网络。","Choose a connection method, pair your phone and configure the network."))
            button(box,tr("无线网络模式","Wireless connection setup")) { ReceiverSettingsUi(activity,log).edit(2) }
            button(box,tr("打开车机蓝牙设置","Open Bluetooth settings")) { open(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS) }
            button(box,tr("打开车机 Wi-Fi 设置","Open Wi-Fi settings")) { open(android.provider.Settings.ACTION_WIFI_SETTINGS) }
            button(box,tr("选择 iPhone 并无线连接","Choose iPhone and connect wirelessly"),phone)
            button(box,tr("使用 USB 连接","Connect with USB"),usb)
        }
        section(parent,tr("诊断","Diagnostics")) { box ->
            toggle(box,"usbDirectAudio",tr("有线音频直接投递（重连后生效）","Direct wired audio delivery (reconnect required)"),p.getBoolean("usbDirectAudio",true))
            choice(box,"usbInRequests",tr("USB 接收请求数量（重连后生效）","USB IN request depth (reconnect required)"),listOf(1,8,16),listOf("1 · reference","8 · default","16 · diagnostic"),UsbReceiveDiagnostics.sanitize(p.getInt("usbInRequests",8)))
            button(box,tr("暂停／恢复视频接收（30秒音频对照）","Pause/resume video reception (30s audio comparison)")) {
                val paused=VideoDiagnosticGate.toggle()
                log("NCM video diagnostic TCP-read pause=$paused durationLimitSeconds=30; audio unchanged")
                android.widget.Toast.makeText(activity,if(paused)tr("视频接收暂停30秒，保持音乐播放后保存日志","Video receive paused for 30s; keep music playing and save logs") else tr("视频接收已恢复","Video reception resumed"),android.widget.Toast.LENGTH_LONG).show()
            }
            button(box,tr("保存诊断报告","Save diagnostic report"),export)
            button(box,tr("上传日志","Upload log"),upload)
            button(box,tr("重试待上传日志","Retry pending uploads"),retryUpload)
            button(box,tr("填写上传的问题描述","Upload issue description"),uploadDescription)
            button(box,tr("清空待上传日志","Clear pending uploads"),clearUploads)
            text(box,tr("离线时保存脱敏日志，联网后手动重试；不会自动上传。","Sanitized logs stay queued offline; retry manually when online. No automatic uploads."))
            button(box,tr("选择报告保存文件夹","Choose report folder"),::reportFolder)
            button(box,tr("查看日志","View logs"),logs)
            button(box,tr("功能诊断与车辆检测","Diagnostics and vehicle tests"),diagnostics)
            button(box,tr("音视频自检","Media self-test"),mediaTest)
            button(box,tr("声音通道短音测试","Audio channel test"),audioTest)
            text(box,tr("报告只保存到车机，不会自动发送。","Reports are saved on the head unit and are not sent automatically."))
        }
        section(parent,tr("自动连接","Automatic connection")) { box ->
            toggle(box,"autoOpenConnect",tr("打开软件自动无线连接","Connect wirelessly when DiPlay opens"),p.getBoolean("autoOpenConnect",false))
            toggle(box,"bootConnect",tr("开机自动连接","Connect after the head unit starts"),s.bootConnect)
            toggle(box,"reconnect",tr("断线后持续重连","Reconnect after disconnect"),s.reconnect)
            toggle(box,"background",tr("返回车机桌面后保持连接","Keep session in background"),s.background)
            button(box,tr("选择 iPhone","Choose iPhone"),phone)
        }
        section(parent,tr("显示与性能","Display and performance")) { box ->
            choice(box,"scale",tr("分辨率比例","Resolution scale"),listOf(10,8,6),listOf("100%","80%","60%"),s.scale)
            choice(box,"fps",tr("帧率","Frame rate"),listOf(30,60),listOf("30 fps","60 fps"),s.fps)
            choice(box,"bufferMillis",tr("音乐缓冲","Music buffer"),listOf(300,500,1000),listOf("300 ms","500 ms","1000 ms"),s.bufferMillis)
            toggle(box,"hevc",tr("高效视频 HEVC（需解码器支持）","Efficient video HEVC (decoder required)"),s.hevc,CodecCapabilities.hevc())
            toggle(box,"opus",tr("Opus 音频（需编解码器支持）","Opus audio (codec required)"),s.opus,CodecCapabilities.opus())
            toggle(box,"rightHandDrive",tr("右舵驾驶布局","Right-hand drive"),s.rightHandDrive)
            toggle(box,"fullScreen",tr("全屏显示，隐藏系统栏","Full screen, hide system bars"),s.fullScreen)
            button(box,tr("物理尺寸、画布分辨率与安全区域","Physical size, canvas resolution and safe area")) { ReceiverSettingsUi(activity,log).edit(0) }
        }
        section(parent,tr("音频路由","Audio routing")) { box ->
            toggle(box,"audioFocus",tr("协调音频焦点","Coordinate audio focus"),s.audioFocus)
            toggle(box,"navigationDuck",tr("导航播报时压低音乐","Duck music during navigation"),s.navigationDuck)
            val values=(0..20).toList(); val labels=values.map { if(it==0) tr("自动","Automatic") else it.toString() }
            choice(box,"mediaChannel",tr("音乐输出通道","Media output channel"),values,labels,s.mediaChannel)
            choice(box,"navigationChannel",tr("导航输出通道","Navigation output channel"),values,labels,s.navigationChannel)
            button(box,tr("试听音乐输出通道","Preview media output")) { preview(AudioOverridePolicy.resolve("MEDIA",android.media.AudioManager.STREAM_MUSIC,p.getInt("mediaChannel",0),0)) }
            button(box,tr("试听导航输出通道","Preview navigation output")) { preview(AudioOverridePolicy.resolve("NAVIGATION",android.media.AudioManager.STREAM_MUSIC,0,p.getInt("navigationChannel",0))) }
            text(box,tr("0 保持自动选路。厂商通道需车机支持；通话与 Siri 输出独立处理。","0 keeps automatic routing. Vendor streams require head-unit support; calls and Siri remain separate."))
            button(box,tr("音量与音频高级设置","Volume and advanced audio settings")) { ReceiverSettingsUi(activity,log).edit(1) }
        }
        section(parent,tr("定位与车机输入","Location and vehicle input")) { box ->
            toggle(box,"vehicleGps",tr("向 iPhone 提供车机 GPS","Report Android location to iPhone"),s.gps)
            toggle(box,"microphonePcm",tr("启用车机麦克风上行","Enable head-unit microphone uplink"),s.microphone)
            toggle(box,"steeringVoice",tr("方向盘语音键监听","Steering voice key listener"),p.getBoolean("steeringVoice",true))
            text(box,tr("GPS、麦克风和厂商按键需设备支持；可在诊断中检查。","Location, microphone and OEM keys require device support; verify them in Diagnostics."))
        }
        section(parent,tr("语言","Language")) { box -> button(box,tr("选择界面语言","Choose interface language")) { ReceiverSettingsUi(activity,log).edit(4) } }
        section(parent,tr("权限与连接帮助","Permissions and connection help")) { box ->
            text(box,tr("蓝牙用于无线启动，Wi-Fi 用于投屏。Android 4.3／5.0 权限在安装时授予。USB 可能请求系统 VPN 授权。","Bluetooth bootstraps wireless CarPlay; Wi-Fi carries projection. Android4.3/5.0 permissions are granted at install. USB may request system VPN consent."))
            button(box,tr("打开应用系统设置","Open app system settings")) { open(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,true) }
        }
        section(parent,tr("关于与适用范围","About and compatibility")) { box ->
            button(box,tr("检查更新","Check updates")) { ReceiverUpdate(activity,log).check() }
            button(box,tr("更新服务地址","Update service address")) { ReceiverUpdate(activity,log).configure() }
            text(box,tr("手动检查；不会自动下载或安装。","Manual checks; no automatic download or installation."))
            text(box,"DiPlay adaptation 0.2.36 · Android ${android.os.Build.VERSION.RELEASE}")
            text(box,tr("协议基于原 DiPlay。比亚迪仪表、Android 11 地图嵌入和原版 Home 示例尚不适用于此适配版。","Protocol based on DiPlay. BYD cluster functions, Android11 map embedding and the upstream Home sample are not implemented in this adaptation."))
        }
        button(parent,tr("应用设置并重新连接","Apply settings and reconnect"),apply)
    }
    private fun open(action:String,details:Boolean=false) {
        try { activity.startActivity(android.content.Intent(action).apply { if(details) data=android.net.Uri.parse("package:"+activity.packageName) }) }
        catch(error:Exception) { Toast.makeText(activity,tr("车机未提供该设置入口","This settings entry is unavailable"),Toast.LENGTH_SHORT).show() }
    }
    private fun reportFolder() {
        val input=EditText(activity).apply { inputType=InputType.TYPE_CLASS_TEXT; setText(p.getString("reportDirectory","DiPlayLegacy")) }
        AlertDialog.Builder(activity).setTitle(tr("存储根目录下的文件夹名称","Folder below shared-storage root")).setView(input).setPositiveButton(tr("保存","Save")) { _,_ ->
            val folder=input.text.toString().trim()
            if(ReportFolderPolicy.valid(folder)) { p.edit().putString("reportDirectory",folder).commit(); log("报告保存文件夹已更新") }
            else Toast.makeText(activity,tr("请输入有效的相对文件夹名称","Enter a valid relative folder name"),Toast.LENGTH_SHORT).show()
        }.setNegativeButton(tr("取消","Cancel"),null).show()
    }
}
