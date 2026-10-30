package com.tailorshop.counter.conversation

/** Main-thread state machine. It emits requests; durable storage and network access are adapters. */
class Conversation(private val workers:()->List<String>) {
    enum class Phase { IDLE, FOLLOWUP, COMMAND, WORKER, RECEIPT, NUMBER, REPORT_RECEIPT, SAVING, SMS, PROCESSING }
    sealed class Effect {
        data class Find(val numbers:List<String>,val topic:com.tailorshop.counter.Topic?):Effect()
        data class Report(val receipt:String,val worker:String?):Effect()
        data class Save(val worker:String,val receipt:String,val askSms:Boolean):Effect()
        data class Sms(val scanId:String):Effect()
        data class Say(val ru:String,val en:String,val prompt:Boolean=false):Effect()
    }
    var phase=Phase.IDLE; private set
    var worker:String?=null; private set
    var receipt:String?=null; private set
    var revision=0L; private set
    var lastActivity=0L; private set
    private var pendingFinish:Intent.Finish?=null
    private var pendingReport:Intent.Report?=null
    private var numberBuffer=""
    private var smsId:String?=null
    private var smsDeadline=0L
    var askSmsAfterSave=false;private set
    val awake get()=phase!=Phase.IDLE
    val engaged get()=phase!=Phase.IDLE && phase!=Phase.FOLLOWUP
    fun context()=IntentContext(workers(),worker,when(phase) {
        Phase.SMS->"sms";Phase.WORKER->"worker";Phase.RECEIPT->"receipt";Phase.NUMBER,Phase.REPORT_RECEIPT->"number";else->"command"
    },receipt)
    fun wake(now:Long) {
        if(!engaged || phase==Phase.PROCESSING) { worker=null;pendingFinish=null;pendingReport=null;numberBuffer="";phase=Phase.COMMAND;revision++ }
        lastActivity=now
    }
    fun progress(now:Long) { if(awake && phase!=Phase.SMS) lastActivity=now }
    fun cancel() { revision++;phase=Phase.IDLE;pendingFinish=null;pendingReport=null;smsId=null;worker=null;numberBuffer="" }
    fun tick(now:Long) {
        if(phase==Phase.SMS && now>=smsDeadline) cancel()
        // Silence closes a conversation; continuous words extend it without a hard maximum.
        else if(phase==Phase.FOLLOWUP && now-lastActivity>15_000) cancel()
        else if(phase in setOf(Phase.COMMAND,Phase.WORKER,Phase.RECEIPT,Phase.NUMBER,Phase.REPORT_RECEIPT) && now-lastActivity>30_000) cancel()
    }
    fun accept(raw:String,suggestion:Intent?=null,now:Long):List<Effect> {
        if(!awake)return emptyList()
        if(SafeIntent.endsConversation(raw) || SafeIntent.local(raw,context())==Intent.Cancel) { cancel();return emptyList() }
        if(phase==Phase.SAVING)return emptyList()
        val c=context()
        val fragment=Regex("\\d+").findAll(SafeIntent.numericText(raw)).joinToString("") { it.value }
        val waitingNumber=phase in setOf(Phase.RECEIPT,Phase.NUMBER,Phase.REPORT_RECEIPT)
        val reset=SafeIntent.question(raw) || SafeIntent.positiveFinish(raw) || Regex("^(?:no|net|wrong|sorry)\\b").containsMatchIn(com.tailorshop.counter.Lookup.normalize(raw))
        if(reset)numberBuffer=""
        val minimum=if(phase in setOf(Phase.RECEIPT,Phase.REPORT_RECEIPT))5 else 4
        val prepared=if(waitingNumber && numberBuffer.isNotBlank() && fragment.isNotBlank() && fragment.length<minimum && !reset) numberBuffer+fragment else raw
        val combined=Regex("\\d+").findAll(SafeIntent.numericText(prepared)).joinToString("") { it.value }
        var intent=SafeIntent.validate(if(prepared!=raw)SafeIntent.local(prepared,c) else suggestion?:SafeIntent.local(raw,c),prepared,c)
        if(phase==Phase.REPORT_RECEIPT && intent is Intent.Find && !SafeIntent.question(raw)) {
            intent=Intent.Report(intent.numbers.singleOrNull(),pendingReport?.worker)
        }
        // After answering a query, unaddressed shop conversation may only ask another
        // read-only question. A new recording requires a new wake name / Listen tap.
        if(phase in setOf(Phase.FOLLOWUP,Phase.PROCESSING) && intent !is Intent.Find && intent !is Intent.Report && intent!=Intent.Cancel)return emptyList()
        if(phase==Phase.SMS && intent is Intent.Finish && intent.receipts==listOfNotNull(receipt) && (intent.worker==null || intent.worker==worker))return emptyList()
        if(phase==Phase.SMS && intent !is Intent.Answer && intent!=Intent.Cancel)return emptyList()
        lastActivity=now;revision++
        if(phase==Phase.WORKER && intent is Intent.Introduce && pendingFinish!=null) {
            worker=intent.worker
            intent=pendingFinish!!.copy(worker=worker)
        }
        if(phase==Phase.RECEIPT && intent is Intent.Finish && pendingFinish!=null && !SafeIntent.positiveFinish(raw)) {
            intent=intent.copy(worker=intent.worker?:pendingFinish!!.worker,askSms=pendingFinish!!.askSms)
        }
        return when(intent) {
            is Intent.Cancel -> { cancel();emptyList() }
            is Intent.Introduce -> { worker=intent.worker;phase=Phase.COMMAND;emptyList() }
            is Intent.Answer -> {
                if(phase!=Phase.SMS) emptyList() else {
                    val id=smsId;cancel()
                    if(intent.yes && id!=null) listOf(Effect.Sms(id)) else listOf(Effect.Say("Без SMS.","No SMS."))
                }
            }
            is Intent.Finish -> {
                if(intent.receipts.isEmpty()) {
                    pendingFinish=intent;worker=intent.worker?:worker;phase=Phase.RECEIPT
                    numberBuffer=combined.takeIf { it.length in 1..4 }.orEmpty()
                    listOf(Effect.Say("Назови номер квитанции.","What's the receipt number?",true))
                } else if(intent.receipts.size!=1 || intent.receipts.single().length !in 5..6) {
                    phase=Phase.RECEIPT;pendingFinish=intent.copy(receipts=emptyList());worker=intent.worker?:worker
                    numberBuffer=combined.takeIf { it.length in 1..4 }.orEmpty()
                    listOf(Effect.Say("Назови один полный номер квитанции.","Please give one full receipt number.",true))
                } else if(intent.worker==null) {
                    pendingFinish=intent;phase=Phase.WORKER
                    listOf(Effect.Say("Под чьим именем записать работу?","Whose name should I record the work under?",true))
                } else {
                    worker=intent.worker;receipt=intent.receipts.single();pendingFinish=null;numberBuffer=""
                    askSmsAfterSave=intent.askSms;phase=Phase.SAVING
                    listOf(Effect.Save(intent.worker,receipt!!,intent.askSms))
                }
            }
            is Intent.Find -> {
                val nums=intent.numbers.ifEmpty { if(intent.topic!=null) listOfNotNull(receipt) else emptyList() }
                if(nums.isEmpty()) { phase=Phase.NUMBER;numberBuffer=combined.takeIf { it.length in 1..3 }.orEmpty();listOf(Effect.Say("Назови номер или последние четыре цифры телефона.","What's the receipt number or last four phone digits?",true)) }
                else { numberBuffer="";receipt=nums.singleOrNull()?.takeIf { it.length in 5..6 };phase=Phase.PROCESSING;listOf(Effect.Find(nums,intent.topic)) }
            }
            is Intent.Report -> {
                val number=intent.receipt?:receipt
                if(number==null || number.length !in 5..6) {
                    phase=Phase.REPORT_RECEIPT;pendingReport=intent
                    numberBuffer=combined.takeIf { it.length in 1..4 }.orEmpty()
                    listOf(Effect.Say("Назови полный номер для проверки отчёта.","Please include the full receipt number to check the report.",true))
                } else { receipt=number;pendingReport=null;numberBuffer="";phase=Phase.PROCESSING;listOf(Effect.Report(number,intent.worker)) }
            }
            is Intent.Need -> {
                phase=when(intent.field) { Intent.Field.WORKER->Phase.WORKER;Intent.Field.RECEIPT->Phase.RECEIPT;Intent.Field.NUMBER->Phase.NUMBER;else->Phase.COMMAND }
                listOf(Effect.Say(when(intent.field) {
                    Intent.Field.WORKER->"Кто закончил работу?";Intent.Field.RECEIPT->"Назови полный номер квитанции.";Intent.Field.NUMBER->"Какой номер проверить?";else->"Проверить квитанцию или записать выполненную работу?"
                },when(intent.field) {
                    Intent.Field.WORKER->"Who finished the work?";Intent.Field.RECEIPT->"What's the full receipt number?";Intent.Field.NUMBER->"Which number should I check?";else->"Check a receipt, or record finished work?"
                },true))
            }
            Intent.Ignore -> emptyList()
        }
    }
    fun saved(id:String?,now:Long):List<Effect> {
        if(phase!=Phase.SAVING) return emptyList()
        revision++;lastActivity=now
        if(id==null) { phase=Phase.COMMAND;return listOf(Effect.Say("Не удалось надёжно сохранить. Ничего не отправлено.","Couldn't save safely. Nothing was sent.")) }
        if(!askSmsAfterSave) { cancel();return listOf(Effect.Say("Запись в очереди. Без SMS.","Record queued. No SMS.")) }
        smsId=id;phase=Phase.SMS;smsDeadline=now+30_000
        return listOf(Effect.Say("Запись в очереди. SMS отправить?","Record queued. Send SMS?"))
    }
    fun queryFinished() { if(phase==Phase.PROCESSING || phase==Phase.FOLLOWUP)cancel() }
    fun reportFinished(now:Long) { queryFinished() }
}
