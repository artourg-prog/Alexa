package com.tailorshop.counter.conversation

import com.tailorshop.counter.Lookup

/** Selects cheap-to-check phonetic/orthographic suspects, never activates the assistant itself. */
object WakeCandidate {
    private val names=listOf("sofia","sofiya","sofya","sophia","sophie")
    fun needsVerification(vararg transcripts:String):Boolean = transcripts.any { text ->
        Lookup.normalize(text).split(' ').take(4).any { word ->
            word.length in 4..9 && word.firstOrNull() in setOf('s','z') && names.any { distance(word,it)<=3 }
        }
    }
    private fun distance(a:String,b:String):Int {
        var row=IntArray(b.length+1) { it }
        for(i in a.indices) {
            val next=IntArray(b.length+1);next[0]=i+1
            for(j in b.indices)next[j+1]=minOf(next[j]+1,row[j+1]+1,row[j]+if(a[i]==b[j])0 else 1)
            row=next
        }
        return row[b.length]
    }
}
