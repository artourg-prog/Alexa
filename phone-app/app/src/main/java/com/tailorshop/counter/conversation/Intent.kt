package com.tailorshop.counter.conversation

import com.tailorshop.counter.Lookup
import com.tailorshop.counter.Query
import com.tailorshop.counter.Topic
import com.tailorshop.counter.SpokenNumbers

sealed class Intent {
    data class Find(val numbers: List<String>, val topic: Topic? = null):Intent()
    data class Finish(val worker:String?,val receipts:List<String>,val askSms:Boolean=true):Intent()
    data class Report(val receipt:String?,val worker:String?=null):Intent()
    data class Introduce(val worker:String):Intent()
    data class Answer(val yes:Boolean):Intent()
    data class Need(val field:Field):Intent()
    data object Cancel:Intent()
    data object Ignore:Intent()
    enum class Field { ACTION, WORKER, RECEIPT, NUMBER }
}

data class IntentContext(val workers:List<String>,val worker:String?=null,val waiting:String="command",val receipt:String?=null)

/** On-device validation is authoritative. A semantic model can suggest actions, never execute them. */
object SafeIntent {
    fun numericText(text:String):String {
        val normalized=Lookup.normalize(text)
            .replace(Regex("\\blast (?:four|for|fore|fo|4)\\b"),"last")
            .replace(Regex("\\bposledni[a-z]* (?:chetyre|4)\\b"),"poslednie")
            .replace(Regex("\\b(this|that) one\\b"),"$1")
        return SpokenNumbers.toDigits(normalized)
    }
    fun numbers(text:String):List<String> {
        val numeric=numericText(text)
        val groups=Regex("\\d+").findAll(numeric).map { it.value }.toList()
        // Never silently turn a six-digit receipt into the last four digits.
        if(groups.joinToString("").length==6) return listOf(groups.joinToString(""))
        return Lookup.parseQueries(numeric).mapNotNull {
            when(it) { is Query.Receipt->it.number;is Query.Last4->it.digits;is Query.FullPhone->it.digits;else->null }
        }.distinct()
    }
    fun worker(text:String,workers:List<String>):String? {
        val tokens=Lookup.normalize(text).split(' ')
        val hits=tokens.mapNotNull { Lookup.matchWorker(it,workers) }.distinct()
        return hits.singleOrNull()
    }
    fun question(text:String):Boolean {
        val n=" "+Lookup.normalize(text)+" "
        val withoutWake=n.trim().replace(Regex("^(?:(?:sofia|sophia|sofiya|sofya|sofi) +)+"),"")
        if(Regex("^(?:is|was|are|were|has|have|did|does)\\b").containsMatchIn(withoutWake))return true
        if(" did " in n && " i did " !in n && " we did " !in n)return true
        return listOf(" who "," whose "," is it "," was it "," is receipt "," was receipt "," has receipt "," kto "," kem "," zapisano "," zapisan "," recorded "," saved "," sokhranen "," sokhranena "," sohranen "," sohranena "," prover "," proveri "," posmotri "," pokazhi "," search "," look up "," check "," find "," naydi "," najdi ").any { it in n }
    }
    fun negatedFinish(text:String):Boolean = Regex("(?:\\b(?:not|never|don t|dont|ne|ni)\\s+(?:yet\\s+)?(?:ready|done|finished|finish|complete|completed|save|record|send|gotov[a-z]*|zakonch[a-z]*|sdel[a-z]*|zapishi|sohrani|sokhrani|otprav[a-z]*))").containsMatchIn(Lookup.normalize(text))
    fun positiveFinish(text:String):Boolean {
        val n=" "+Lookup.normalize(text)+" "
        return !negatedFinish(text) && listOf(" zapishi "," zapisat "," sohrani "," sokhrani "," zakonchil "," zakonchila "," sdelal "," sdelala "," gotovo "," save "," record "," write down "," log "," finished "," finish "," done "," completed "," complete "," i did "," we did "," send sms "," send the message "," send a message "," send message "," otprav "," otpravi ").any { it in n }
    }
    private fun confirmation(text:String,c:IntentContext):Boolean? {
        if(question(text))return null
        val n=" "+Lookup.normalize(text)+" "
        if(numbers(text).any { it!=c.receipt })return null
        if(listOf(" bez sms "," without sms "," no sms "," just save "," prosto zapishi "," don t send "," dont send "," ne otprav "," ne otpravi ").any { it in n })return false
        val words=Lookup.normalize(text).split(' ').filter { it.isNotBlank() && !it.all(Char::isDigit) && Lookup.matchWorker(it,c.workers)==null }
        val allowed=setOf("sofia","sophia","sofiya","sofya","sofi","this","is","eto","ya","please","pozhaluysta","yes","yeah","yep","correct","right","sure","da","verno","aga","tochno","pravilno","konechno","no","nope","cancel","wrong","net","otmena","ne","neverno","otmenit","stop","send","it","sms","message","the","a","text","go","ahead","otprav","otpravi","otpravlyay","soobschenie","pozhalusta","for","receipt","resit","number","nomer")
        if(words.any { it !in allowed })return null
        Lookup.yesNo(text)?.let { return it }
        return if(words.any { it in setOf("send","otprav","otpravi","otpravlyay") } || " go ahead " in n)true else null
    }
    fun endsConversation(text:String):Boolean {
        val words=Lookup.normalize(text).split(' ').filter(String::isNotBlank)
        val thanks=setOf("thanks","thank","spasibo")
        val polite=setOf("you","very","much","so","bolshoe","ochen","vse","all","that","s","it","sofia","sophia","sofiya","sofya","please","pozhaluysta")
        return words.any { it in thanks } && words.all { it in thanks || it in polite }
    }
    fun local(text:String,c:IntentContext):Intent {
        val n=" "+Lookup.normalize(text)+" "
        val nums=numbers(text)
        val name=worker(text,c.workers)
        if(endsConversation(text))return Intent.Cancel
        if(c.waiting=="sms" || c.waiting=="pickup") confirmation(text,c)?.let { return Intent.Answer(it) }
        if(listOf(" cancel "," stop "," otmena "," otmeni "," ne nado ").any { it in n }) return Intent.Cancel
        if(c.waiting=="worker" && name!=null && nums.isEmpty()) return Intent.Introduce(name)
        val report=listOf(" report "," otchet", " pod kem "," who recorded "," kto sdelal "," who did "," who finished "," who made "," recorded "," saved "," zapisan", " sokhranen", " sokhranena", " sohranen", " sohranena").any { it in n }
        if(report && (question(text) || listOf("prover","sokhranen","pod kem").any { it in n }))
            return Intent.Report(nums.singleOrNull()?.takeIf { it.length>=5 },name.takeIf { " pod " in n || " under " in n })
        val topic=Lookup.followUp(text)
        if(question(text) || topic!=null || c.waiting=="number") return Intent.Find(nums,topic)
        if(negatedFinish(text)) return Intent.Need(Intent.Field.ACTION)
        if(positiveFinish(text) || c.waiting=="receipt") {
            val noSms=listOf(" bez sms "," without sms "," no sms "," prosto zapishi "," just save ").any { it in n }
            return Intent.Finish(name?:c.worker,nums,!noSms)
        }
        if(name!=null && nums.isEmpty()) return Intent.Introduce(name)
        // A number after the wake name can safely be looked up, never recorded implicitly.
        if(nums.isNotEmpty() && (name==null || listOf(" receipt "," resit "," kvitants", " phone "," telefon", " last "," posledni").any { it in n })) return Intent.Find(nums)
        return Intent.Ignore
    }
    fun validate(candidate:Intent,text:String,c:IntentContext):Intent {
        val spoken=numbers(text)
        return when(candidate) {
            is Intent.Find -> if(candidate.numbers.all { it in spoken || it==c.receipt }) candidate else Intent.Need(Intent.Field.NUMBER)
            is Intent.Report -> if(candidate.receipt==null || candidate.receipt in spoken || candidate.receipt==c.receipt) candidate else Intent.Need(Intent.Field.RECEIPT)
            is Intent.Answer -> if(candidate.yes==confirmation(text,c))candidate else Intent.Ignore
            is Intent.Introduce -> if(candidate.worker in c.workers && candidate.worker==worker(text,c.workers))candidate else Intent.Need(Intent.Field.WORKER)
            is Intent.Finish -> {
                if(question(text) || negatedFinish(text) || (!positiveFinish(text) && c.waiting!="receipt")) local(text,c).let { if(it is Intent.Finish) Intent.Need(Intent.Field.ACTION) else it }
                else if(candidate.worker!=null && (candidate.worker !in c.workers || candidate.worker!=c.worker && candidate.worker!=worker(text,c.workers))) Intent.Need(Intent.Field.WORKER)
                else if(candidate.receipts.any { it !in spoken }) Intent.Need(Intent.Field.RECEIPT)
                else candidate
            }
            else -> candidate
        }
    }
}
