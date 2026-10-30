package com.tailorshop.counter

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import java.nio.file.Files
import java.io.File

class FinishDurabilityTest {
    @Test fun failedSmsFlagSaveCannotSendBeforeRetryIsDurable() {
        val dir=Files.createTempDirectory("sofia-sms-durable").toFile()
        try {
            File(dir,"finish-config.json").writeText("{url:'https://example.invalid',secret:'test-only',workers:[Tester]}")
            var fail=false;var smsCalls=0
            val f=Finishes(dir,{a,b->if(fail)false else a.renameTo(b)},{_,body->
                when(body.optString("mode")) {
                    "pickup"->{smsCalls++;JSONObject("{ok:true,state:SENT}")}
                    else->JSONObject("{ok:true,status:ADDED}")
                }
            })
            val id=f.add("Tester","12345",false)!!.scanId
            f.sync();fail=true
            assertFalse(f.requestSms(id));assertFalse(f.get(id)!!.durable)
            val smsId=f.get(id)!!.smsId
            f.sync();assertEquals(0,smsCalls)
            assertFalse(Finishes(dir).get(id)!!.sendSms)
            fail=false;f.sync();assertEquals(1,smsCalls)
            assertEquals(smsId,Finishes(dir).get(id)!!.smsId)
        } finally { dir.deleteRecursively() }
    }
    @Test fun unreadableWorkQueueProducesPersistentWarningAndRetainedBackup() {
        val dir=Files.createTempDirectory("sofia-recovery").toFile()
        try {
            File(dir,"finish-queue.json").writeText("not-valid-json")
            val f=Finishes(dir)
            assertNotNull(f.warning());assertEquals(1,dir.listFiles()!!.count { it.name.startsWith("finish-queue.unreadable-") })
            assertNotNull(Finishes(dir).warning())
            File(dir,"finish-queue.warning").delete() // simulate a failed sidecar write / missing marker
            assertNotNull(Finishes(dir).warning())
            f.acknowledgeRecovery();assertNull(f.warning())
            assertEquals(1,dir.listFiles()!!.count { it.name.startsWith("finish-queue.unreadable-") })
        } finally { dir.deleteRecursively() }
    }
}
