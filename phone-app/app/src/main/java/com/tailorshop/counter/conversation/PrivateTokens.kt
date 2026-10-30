package com.tailorshop.counter.conversation

import com.tailorshop.counter.Lookup
import com.tailorshop.counter.SpokenNumbers
import org.json.JSONArray
import org.json.JSONObject

/** A finite operational vocabulary, not a raw transcript. Unknown words and all real entities stay on-device. */
object PrivateTokens {
    val allowed = ("i me my mine you your we our us this that these those it is are was were has have had been be am " +
        "the a an and or but not no yes do dont don t please can could would will want need let lets " +
        "find search look looking up check show see tell get locate where who whose did which " +
        "save record write add put enter register finished finish done complete completed ready work job " +
        "receipt receipts order orders ticket tickets phone telephone last four digits number numbers " +
        "daily report reports under for in to from on with without send sending text message sms customer client " +
        "paid unpaid balance due amount total price worker tailor today yesterday already again " +
        "assistant unknown " +
        "ya eto menya mne moy moya ty tebe nash my on ona ono etot eta eti etu est bylo byl byla uzhe " +
        "pozhaluysta nado nuzhno hochu mozhet mozhesh davai da davay net ne ni i ili a no " +
        "naydi najdi poiski poischi prover proveri pokazhi skazhi gde kto kem komu kakoy kakaya " +
        "zapishi zapisat zapisano zapisan zapisana sokhrani sohrani sokhranen sokhranena dobav vnesi " +
        "zakonchil zakonchila zakonchen sdelal sdelala gotovo gotov gotova rabota " +
        "resit resita resitu kvitantsiya kvitantsiyu kvitantsii zakaz zakaza nomer nomera " +
        "telefon telefona poslednie tsifry report otchet otchete deili deyli v na pod za dlya " +
        "bez soobscheniya otprav otpravi otpravit otpravlyat soobschenie klient klientu sms oplacheno neoplacheno summa dolg master segodnya vchera").split(' ').filter { it.isNotBlank() }.toSet()
    data class Payload(val tokens:List<String>,val numbers:Map<String,String>,val workers:Map<String,String>) {
        fun publicJson(c:IntentContext):JSONObject = JSONObject().put("tokens",JSONArray(tokens)).put("waiting",c.waiting)
            .put("numberKinds",JSONObject().apply { numbers.forEach { (ref,n)->put(ref,when { n.length==4->"last4";n.length in 5..6->"receipt";else->"phone" }) } })
            .put("hasCurrentWorker",c.worker!=null)
    }
    fun encode(text:String,c:IntentContext):Payload {
        val normalized=SafeIntent.numericText(text)
        val ns=linkedMapOf<String,String>();val ws=linkedMapOf<String,String>()
        val tokens=normalized.split(' ').filter { it.isNotBlank() }.take(96).map { word ->
            when {
                word.all(Char::isDigit) -> ns.entries.firstOrNull { it.value==word }?.key ?: "N${ns.size+1}".also { ns[it]=word }
                Lookup.matchWorker(word,c.workers)!=null -> {
                    val w=Lookup.matchWorker(word,c.workers)!!
                    ws.entries.firstOrNull { it.value==w }?.key ?: "W${ws.size+1}".also { ws[it]=w }
                }
                word in setOf("sofia","sophia","sofiya","sofya","sofi") -> "assistant"
                word in allowed -> word
                else -> "unknown"
            }
        }
        return Payload(tokens,ns,ws)
    }
    fun decode(result:JSONObject,p:Payload,c:IntentContext):Intent {
        fun nums():List<String> = result.optJSONArray("numbers")?.let { a -> (0 until a.length()).mapNotNull { p.numbers[a.optString(it)] } }.orEmpty()
        val refs=result.optJSONArray("numbers")
        if(refs!=null && (0 until refs.length()).any { refs.optString(it) !in p.numbers }) return Intent.Need(Intent.Field.NUMBER)
        val rawWorker=result.optString("worker")
        val w=if(rawWorker=="current") c.worker else p.workers[rawWorker]
        return when(result.optString("action")) {
            "lookup" -> Intent.Find(nums())
            "finish" -> Intent.Finish(w,nums(),result.optBoolean("askSms",true))
            "report" -> Intent.Report(nums().singleOrNull(),w)
            "introduce" -> w?.let { Intent.Introduce(it) } ?: Intent.Need(Intent.Field.WORKER)
            "yes" -> if(c.waiting in setOf("sms","pickup")) Intent.Answer(true) else Intent.Ignore
            "no" -> if(c.waiting in setOf("sms","pickup")) Intent.Answer(false) else Intent.Ignore
            "cancel" -> Intent.Cancel
            else -> Intent.Need(when(result.optString("need")) { "worker"->Intent.Field.WORKER;"receipt"->Intent.Field.RECEIPT;"number"->Intent.Field.NUMBER;else->Intent.Field.ACTION })
        }
    }
}
