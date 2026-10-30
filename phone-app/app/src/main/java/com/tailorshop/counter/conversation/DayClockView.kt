package com.tailorshop.counter.conversation

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.View
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.min

/** Dim black clock face. No animation timer or audio capture is owned by this view. */
class DayClockView(context:Context,onTap:()->Unit):View(context) {
    private val timePaint=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.rgb(166,166,166);typeface=Typeface.create("sans-serif-condensed",Typeface.BOLD);textAlign=Paint.Align.CENTER }
    private val detailPaint=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.rgb(140,140,140);typeface=Typeface.create("sans-serif",Typeface.NORMAL);textAlign=Paint.Align.CENTER }
    private var time:ZonedDateTime=ZonedDateTime.now()
    private var weather="Las Vegas · weather unavailable"
    private var battery=""
    init { setBackgroundColor(Color.BLACK);isClickable=true;setOnClickListener { onTap() };contentDescription="Daytime clock. Tap to talk to Sofia." }
    fun show(at:ZonedDateTime,weatherText:String,batteryPercent:Int) {
        val changed=at.minute!=time.minute || at.hour!=time.hour || at.toLocalDate()!=time.toLocalDate() || weather!=weatherText || battery!="$batteryPercent%"
        time=at;weather=weatherText;battery=if(batteryPercent>=0)"$batteryPercent%" else ""
        if(changed)invalidate()
    }
    override fun onDraw(canvas:Canvas) {
        super.onDraw(canvas)
        val w=width.toFloat();val h=height.toFloat();if(w<=0 || h<=0)return
        // Moving the whole composition retains its layout and spreads persistent edges.
        val shift=DayClock.drift(time.minute)*resources.displayMetrics.density
        canvas.save();canvas.translate(shift,-shift)
        val digits=time.format(DateTimeFormatter.ofPattern("h:mm",Locale.US))
        timePaint.textScaleX=1f;timePaint.textSize=100f
        val metric=timePaint.fontMetrics
        timePaint.textSize=h*0.70f/(metric.descent-metric.ascent)*100f
        // Fill the width as requested even for narrow digits such as 11:11.
        timePaint.textScaleX=(w-32*resources.displayMetrics.density)/timePaint.measureText(digits)
        val fm=timePaint.fontMetrics
        canvas.drawText(digits,w/2,h*0.40f-(fm.ascent+fm.descent)/2,timePaint)
        fun line(text:String,y:Float,scale:Float) {
            detailPaint.textSize=min(h*scale,w*0.045f)
            if(detailPaint.measureText(text)>w*0.94f)detailPaint.textSize*=w*0.94f/detailPaint.measureText(text)
            canvas.drawText(text,w/2,y,detailPaint)
        }
        line(time.format(DateTimeFormatter.ofPattern("EEEE, MMMM d",Locale.US)),h*0.80f,0.06f)
        line(weather,h*0.90f,0.05f)
        line(battery,h*0.97f,0.025f)
        canvas.restore()
    }
}
