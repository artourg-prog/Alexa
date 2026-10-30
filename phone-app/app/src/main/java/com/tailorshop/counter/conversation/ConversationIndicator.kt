package com.tailorshop.counter.conversation

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.widget.TextView
import com.tailorshop.counter.R

/** Foreground-only conversation feedback; never a screen-on or microphone owner. */
class ConversationIndicator(context:Context):TextView(context) {
    enum class Mode { REST, LISTENING, THINKING, SPEAKING, PROBLEM }
    var mode=Mode.REST;private set
    private var pulse:ValueAnimator?=null
    private var energy=0f
    private var settingsHold:HoldSettingsGesture?=null
    val animating get()=pulse?.isRunning==true
    init {
        text="Sofia";textSize=52f;gravity=Gravity.CENTER;setPadding(12,4,12,6)
        typeface=try { resources.getFont(R.font.great_vibes) } catch(_:Exception) { android.graphics.Typeface.SERIF }
        setTextColor(Color.WHITE)
    }
    fun settingsEntry(open:()->Unit) { settingsHold=HoldSettingsGesture(this,open);isClickable=true;contentDescription="Sofia. Hold for three seconds to open settings." }
    fun cancelSettingsHold() { settingsHold?.cancel() }
    override fun onTouchEvent(event:android.view.MotionEvent):Boolean=settingsHold?.touch(event)?:super.onTouchEvent(event)
    override fun performClick():Boolean { super.performClick();return true }
    fun energy(level:Float) { energy=energy*0.65f+level.coerceIn(0f,1f)*0.35f }
    fun show(next:Mode) {
        if(mode==next)return
        stopPulse();mode=next
        setTextColor(when(next) {
            Mode.LISTENING->Color.rgb(100,220,255)
            Mode.THINKING->Color.rgb(190,165,255)
            Mode.SPEAKING->Color.rgb(215,240,255)
            Mode.PROBLEM->Color.rgb(255,190,100)
            Mode.REST->Color.WHITE
        })
        if(isAttachedToWindow)startPulse()
    }
    private fun startPulse() {
        if(mode !in setOf(Mode.LISTENING,Mode.THINKING,Mode.SPEAKING) || !ValueAnimator.areAnimatorsEnabled())return
        pulse=ValueAnimator.ofFloat(0f,1f).apply {
            duration=when(mode) { Mode.THINKING->1000L;Mode.SPEAKING->650L;else->1300L }
            repeatCount=ValueAnimator.INFINITE;repeatMode=ValueAnimator.REVERSE
            addUpdateListener {
                val v=it.animatedValue as Float
                alpha=0.72f+0.28f*v
                val response=if(mode==Mode.LISTENING)energy*0.02f else 0f
                scaleX=1f+0.025f*v+response;scaleY=scaleX
            }
            start()
        }
    }
    private fun stopPulse() { pulse?.cancel();pulse=null;alpha=1f;scaleX=1f;scaleY=1f;energy=0f }
    override fun onAttachedToWindow() { super.onAttachedToWindow();startPulse() }
    override fun onDetachedFromWindow() { cancelSettingsHold();stopPulse();super.onDetachedFromWindow() }
}
