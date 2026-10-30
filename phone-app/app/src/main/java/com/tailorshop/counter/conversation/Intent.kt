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
    fun numbers(text:String):List<String> {
        val groups=Regex("\\d+").findAll(SpokenNumbers.toDigits(Lookup.normalize(text))).map { it.value }.toList()
        // Never silently turn a six-digit receipt into the last four digits.
        if(groups.joinToString("").length==6) return listOf(groups.joinToString(""))
        return Lookup.parseQueries(text).mapNotNull {
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
        return listOf(" who "," whose "," is it "," was it "," is receipt "," was receipt "," has receipt "," did "," kto "," kem "," zapisano "," zapisan "," recorded "," saved "," prover "," check "," find "," naydi "," najdi ").any { it in n }
    }
    fun negatedFinish(text:String):Boolean = Regex("(?:\\b(?:not|never|don t|dont|ne|ni)\\s+(?:yet\\s+)?(?:ready|done|finished|finish|complete|completed|save|record|send|gotov[a-z]*|zakonch[a-z]*|sdel[a-z]*|zapishi|sohrani|sokhrani|otprav[a-z]*))").containsMatchIn(Lookup.normalize(text))
    fun positiveFinish(text:String):Boolean {
        val n=" "+Lookup.normalize(text)+" "
        return !negatedFinish(text) && listOf(" zapishi "," zapisat "," sohrani "," sokhrani "," zakonchil "," zakonchila "," sdelal "," sdelala "," gotovo "," save "," record "," finished "," finish "," done "," completed "," complete "," send sms "," send the message "," send a message "," send message "," otprav "," otpravi ").any { it in n }
    }
    fun local(text:String,c:IntentContext):Intent {
        val n=" "+Lookup.normalize(text)+" "
        val nums=numbers(text)
        val name=worker(text,c.workers)
        if(c.waiting=="sms" || c.waiting=="pickup") Lookup.yesNo(text)?.let { return Intent.Answer(it) }
        if(listOf(" cancel "," stop "," otmena "," otmeni "," ne nado ").any { it in n }) return Intent.Cancel
        if(c.waiting=="worker" && name!=null && nums.isEmpty()) return Intent.Introduce(name)
        val report=listOf(" report "," otchet", " pod kem "," who recorded "," kto sdelal "," who did "," who finished "," who made ").any { it in n }
        if(report && (question(text) || listOf("prover","sokhranen","pod kem","under").any { it in n }))
            return Intent.Report(nums.singleOrNull()?.takeIf { it.length>=5 },name.takeIf { " pod " in n || " under " in n })
        val topic=Lookup.followUp(text)
        if(question(text) || topic!=null || c.waiting=="number") return Intent.Find(nums,topic)
        if(negatedFinish(text)) return Intent.Need(Intent.Field.ACTION)
        if(positiveFinish(text) || c.waiting=="receipt") {
            if(nums.any { it.length!=5 && it.length!=6 }) return Intent.Need(Intent.Field.RECEIPT)
            val noSms=listOf(" bez sms "," without sms "," no sms "," prosto zapishi "," just save ").any { it in n }
            return Intent.Finish(name?:c.worker,nums,!noSms)
        }
        if(name!=null && nums.isEmpty()) return Intent.Introduce(name)
        return Intent.Ignore
    }
    fun validate(candidate:Intent,text:String,c:IntentContext):Intent {
        val spoken=numbers(text)
        return when(candidate) {
            is Intent.Find -> if(candidate.numbers.all { it in spoken || it==c.receipt }) candidate else Intent.Need(Intent.Field.NUMBER)
            is Intent.Report -> if(candidate.receipt==null || candidate.receipt in spoken || candidate.receipt==c.receipt) candidate else Intent.Need(Intent.Field.RECEIPT)
            is Intent.Finish -> {
                if(question(text) || negatedFinish(text) || (!positiveFinish(text) && c.waiting!="receipt")) local(text,c).let { if(it is Intent.Finish) Intent.Need(Intent.Field.ACTION) else it }
                else if(candidate.worker!=null && candidate.worker !in c.workers) Intent.Need(Intent.Field.WORKER)
                else if(candidate.receipts.any { it !in spoken || (it.length!=5 && it.length!=6) }) Intent.Need(Intent.Field.RECEIPT)
                else candidate
            }
            else -> candidate
        }
    }
}
