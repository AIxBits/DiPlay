package local.airuize.receiver
object AudioOverridePolicy {
    fun sanitize(value:Int)=if(value in 0..20) value else 0
    fun resolve(kind:String,automatic:Int,media:Int,navigation:Int):Int {
        val selected=when(kind) { "MEDIA"->sanitize(media); "NAVIGATION"->sanitize(navigation); else->0 }
        return if(selected==0) automatic else selected
    }
}
object ReportFolderPolicy {
    fun valid(value:String)=value.isNotBlank() && value.length<=120 && !value.startsWith("/") && '\\' !in value && value.split('/').all { it.isNotBlank() && it!="." && it!=".." && it.none { c->c<' ' } }
}
