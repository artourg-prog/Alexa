package com.tailorshop.counter.conversation

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.TextView

/** One deliberate gesture shared by the gear and the Sofia name. */
internal class HoldSettingsGesture(private val view:View,private val open:()->Unit) {
    private var downX=0f;private var downY=0f
    private val slop=ViewConfiguration.get(view.context).scaledTouchSlop
    private val reveal=Runnable { if(view.isPressed && view.isAttachedToWindow) { cancel();open() } }
    fun cancel() { view.removeCallbacks(reveal);view.isPressed=false }
    fun touch(event:MotionEvent):Boolean {
        when(event.actionMasked) {
            MotionEvent.ACTION_DOWN->{cancel();downX=event.x;downY=event.y;view.isPressed=true;view.postDelayed(reveal,HoldSettingsControl.HOLD_MS)}
            MotionEvent.ACTION_MOVE->if(kotlin.math.abs(event.x-downX)>slop || kotlin.math.abs(event.y-downY)>slop)cancel()
            MotionEvent.ACTION_UP->{cancel();view.performClick()}
            MotionEvent.ACTION_CANCEL,MotionEvent.ACTION_POINTER_DOWN->cancel()
        }
        return true
    }
}

/** A short tap never opens settings. No security or lock-screen credentials are changed. */
class HoldSettingsControl(context:Context,open:()->Unit):TextView(context) {
    companion object { const val HOLD_MS=3000L }
    private val hold=HoldSettingsGesture(this,open)
    init {
        text="⚙";textSize=28f;setTextColor(Color.LTGRAY);gravity=Gravity.CENTER
        setPadding(16,6,16,6);contentDescription="Settings. Hold for three seconds.";isClickable=true
    }
    fun cancelHold()=hold.cancel()
    override fun onTouchEvent(event:MotionEvent)=hold.touch(event)
    override fun performClick():Boolean { super.performClick();return true }
    override fun onDetachedFromWindow() { cancelHold();super.onDetachedFromWindow() }
}
