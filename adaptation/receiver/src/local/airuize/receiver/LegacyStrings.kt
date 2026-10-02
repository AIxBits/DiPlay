package local.airuize.receiver
import android.content.Context
import org.json.JSONObject

/** Source translations from DiPlay/common; API18 has no modern AppLocale/LocaleList. */
class LegacyStrings(private val context:Context) {
    private val languages by lazy { JSONObject(context.assets.open("upstream-ui-strings.json").bufferedReader(Charsets.UTF_8).use { it.readText() }) }
    private val extra by lazy { JSONObject(context.assets.open("legacy-ui-strings.json").bufferedReader(Charsets.UTF_8).use { it.readText() }) }
    fun language():String = ReceiverSettings.read(context).language.let { if(it=="system") java.util.Locale.getDefault().language else it }
    fun text(key:String,fallback:String=key):String = languages.optJSONObject(language())?.optString(key)?.takeIf { it.isNotEmpty() }
        ?: languages.optJSONObject("en")?.optString(key)?.takeIf { it.isNotEmpty() } ?: fallback
    fun translate(zh:String,en:String,ar:String):String {
        val aliases=mapOf("Settings" to "app_settings","Audio" to "audio","Display" to "display_and_performance",
            "Network" to "connection_setup","Connection and boot" to "startup","Language" to "language_section_title",
            "Save" to "save","Close" to "close","Cancel" to "cancel","Diagnostics" to "diagnostics",
            "Connect iPhone" to "connect_phone","Stop" to "disconnect","Log" to "debug_logs","Export" to "save_diagnostic_report")
        aliases[en]?.let { return text(it,en) }
        val english=languages.getJSONObject("en")
        val keys=english.keys()
        while(keys.hasNext()) { val key=keys.next() as String; if(english.optString(key).equals(en,true)) return text(key,en) }
        extra.optJSONObject(language())?.optString(en)?.takeIf { it.isNotEmpty() }?.let { return it }
        return when(language()) { "zh"->zh; "ar"->ar; else->en }
    }
}
