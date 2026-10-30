package com.tailorshop.counter.conversation

import com.tailorshop.counter.Card

/** Presentation only. Expiry and browsing can never mark an order collected. */
class CardDeck {
    private data class Entry(val card:Card,val until:Long)
    private val entries=mutableListOf<Entry>()
    var index=0;private set
    val size get()=entries.size
    val current get()=entries.getOrNull(index)?.card
    fun add(cards:List<Card>,now:Long) {
        val unique=cards.distinctBy { it.receipt }
        entries.removeAll { entry->unique.any { it.receipt==entry.card.receipt } }
        entries.addAll(0,unique.map { Entry(it,now+if(it.ready)60_000 else 10_000) })
        index=0
    }
    fun expire(now:Long,heldReceipt:String?=null):Boolean {
        val selected=current?.receipt
        val changed=entries.removeAll { it.until<=now && it.card.receipt!=heldReceipt }
        if(changed)restore(selected)
        return changed
    }
    fun remove(receipt:String) {
        val selected=current?.receipt;entries.removeAll { it.card.receipt==receipt };restore(selected)
    }
    fun clear() { entries.clear();index=0 }
    fun move(step:Int) { if(entries.isNotEmpty())index=((index+step)%entries.size+entries.size)%entries.size }
    private fun restore(receipt:String?) {
        val position=entries.indexOfFirst { it.card.receipt==receipt }
        index=if(position>=0)position else index.coerceIn(0,(entries.size-1).coerceAtLeast(0))
    }
}
