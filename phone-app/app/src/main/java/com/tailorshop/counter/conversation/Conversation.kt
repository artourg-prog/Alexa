package com.tailorshop.counter.conversation

/** Main-thread state machine. It emits requests; durable storage and network access are adapters. */
class Conversation(private val workers:()->List<String>) {
    enum class Phase { IDLE, COMMAND, WORKER, RECEIPT, NUMBER, SAVING, SMS, PROCESSING }
    sealed class Effect {
        data class Find(val numbers:List<String>,val topic:com.tailorshop.counter.Topic?):Effect()
        data class Report(val receipt:String,val worker:String?):Effect()
        data class Save(val worker:String,val receipt:String,val askSms:Boolean):Effect()
        data class Sms(val scanId:String):Effect()
        data class Say(val ru:String,val en:String):Effect()
    }
    var phase=Phase.IDLE; private set
    var worker:String?=null; private set
    var receipt:String?=null; private set
    var revision=0L; private set
    var lastActivity=0L; private set
    private var pendingFinish:Intent.Finish?=null
    private var smsId:String?=null
    private var smsDeadline=0L
    var askSmsAfterSave=false;private set
    val awake get()=phase!=Phase.IDLE
    fun context()=IntentContext(workers(),worker,when(phase) {
        Phase.SMS->"sms";Phase.WORKER->"worker";Phase.RECEIPT->"receipt";Phase.NUMBER->"number";else->"command"
    },receipt)
    fun wake(now:Long) {
        if(!awake) { worker=null;pendingFinish=null;phase=Phase.COMMAND;revision++ }
        lastActivity=now
    }
    fun progress(now:Long) { if(awake && phase!=Phase.SMS) lastActivity=now }
    fun cancel() { revision++;phase=Phase.IDLE;pendingFinish=null;smsId=null;worker=null }
    fun tick(now:Long) {
        if(phase==Phase.SMS && now>=smsDeadline) cancel()
        // Silence closes a conversation; continuous words extend it without a hard maximum.
        else if(phase in setOf(Phase.COMMAND,Phase.WORKER,Phase.RECEIPT,Phase.NUMBER) && now-lastActivity>30_000) cancel()
    }
    fun accept(raw:String,suggestion:Intent?=null,now:Long):List<Effect> {
        if(!awake || phase==Phase.SAVING) return emptyList()
        lastActivity=now;revision++
        val c=context()
        var intent=SafeIntent.validate(suggestion?:SafeIntent.local(raw,c),raw,c)
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
                    listOf(Effect.Say("Назови номер квитанции.","What's the receipt number?"))
                } else if(intent.receipts.size!=1 || intent.receipts.single().length !in 5..6) {
                    phase=Phase.RECEIPT;pendingFinish=intent.copy(receipts=emptyList())
                    listOf(Effect.Say("Назови один полный номер квитанции.","Please give one full receipt number."))
                } else if(intent.worker==null) {
                    pendingFinish=intent;phase=Phase.WORKER
                    listOf(Effect.Say("Под чьим именем записать работу?","Whose name should I record the work under?"))
                } else {
                    worker=intent.worker;receipt=intent.receipts.single();pendingFinish=null
                    askSmsAfterSave=intent.askSms;phase=Phase.SAVING
                    listOf(Effect.Save(intent.worker,receipt!!,intent.askSms))
                }
            }
            is Intent.Find -> {
                val nums=intent.numbers.ifEmpty { if(intent.topic!=null) listOfNotNull(receipt) else emptyList() }
                if(nums.isEmpty()) { phase=Phase.NUMBER;listOf(Effect.Say("Назови номер или последние четыре цифры телефона.","What's the receipt number or last four phone digits?")) }
                else { receipt=nums.singleOrNull()?.takeIf { it.length in 5..6 };phase=Phase.COMMAND;listOf(Effect.Find(nums,intent.topic)) }
            }
            is Intent.Report -> {
                val number=intent.receipt?:receipt
                if(number==null || number.length !in 5..6) {
                    phase=Phase.COMMAND
                    listOf(Effect.Say("Назови полный номер для проверки отчёта.","Please include the full receipt number to check the report."))
                } else { receipt=number;phase=Phase.PROCESSING;listOf(Effect.Report(number,intent.worker)) }
            }
            is Intent.Need -> {
                phase=when(intent.field) { Intent.Field.WORKER->Phase.WORKER;Intent.Field.RECEIPT->Phase.RECEIPT;Intent.Field.NUMBER->Phase.NUMBER;else->Phase.COMMAND }
                listOf(Effect.Say(when(intent.field) {
                    Intent.Field.WORKER->"Кто закончил работу?";Intent.Field.RECEIPT->"Назови полный номер квитанции.";Intent.Field.NUMBER->"Какой номер проверить?";else->"Проверить квитанцию или записать выполненную работу?"
                },when(intent.field) {
                    Intent.Field.WORKER->"Who finished the work?";Intent.Field.RECEIPT->"What's the full receipt number?";Intent.Field.NUMBER->"Which number should I check?";else->"Check a receipt, or record finished work?"
                }))
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
    fun reportFinished(now:Long) { if(phase==Phase.PROCESSING) { phase=Phase.COMMAND;lastActivity=now;revision++ } }
}
