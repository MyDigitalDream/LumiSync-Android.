package com.lumisync.app

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.view.*
import android.widget.*
import kotlin.math.*

internal object Palette {
 val bg=Color.rgb(10,16,28)
 val card=Color.rgb(22,32,49)
 val raised=Color.rgb(32,46,66)
 val accent=Color.rgb(112,237,213)
 val text=Color.rgb(242,247,255)
 val muted=Color.rgb(170,187,210)
 val border=Color.rgb(48,65,87)
}
internal class LumiUi(val activity:Activity) {
 fun dp(n:Int)=(n*activity.resources.displayMetrics.density).roundToInt()
 fun shape(color:Int,radius:Int=20)=GradientDrawable().apply { setColor(color); cornerRadius=dp(radius).toFloat() }
 fun text(value:String,size:Float=15f,color:Int=Palette.text,bold:Boolean=false)=TextView(activity).apply {
  text=value; textSize=size; setTextColor(color); setLineSpacing(dp(3).toFloat(),1f)
  if(bold) typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)
 }
 fun space(parent:LinearLayout,n:Int=12) { parent.addView(Space(activity),LinearLayout.LayoutParams(1,dp(n))) }
 fun column()=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL }
 fun row()=LinearLayout(activity).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL }
 fun card(parent:LinearLayout):LinearLayout=column().apply {
  background=shape(Palette.card); setPadding(dp(20),dp(20),dp(20),dp(20))
  parent.addView(this,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(16) })
 }
 fun button(value:String,primary:Boolean=false,action:()->Unit):Button=Button(activity).apply {
  text=value; isAllCaps=false; textSize=15f; typeface=Typeface.create("sans-serif-medium",0)
  setTextColor(if(primary) Palette.bg else Palette.text)
  background=RippleDrawable(ColorStateList.valueOf(0x30FFFFFF),shape(if(primary) Palette.accent else Palette.raised,14),null)
  minHeight=dp(52); minimumHeight=dp(52); setPadding(dp(14),dp(10),dp(14),dp(10))
  stateListAnimator=null; setOnClickListener { action() }
 }
 fun full(parent:LinearLayout,button:Button) { parent.addView(button,LinearLayout.LayoutParams(-1,-2)) }
 fun pair(parent:LinearLayout,a:Button,b:Button) {
  val row=row(); parent.addView(row,LinearLayout.LayoutParams(-1,-2))
  row.addView(a,LinearLayout.LayoutParams(0,-2,1f).apply { rightMargin=dp(6) })
  row.addView(b,LinearLayout.LayoutParams(0,-2,1f).apply { leftMargin=dp(6) })
 }
 fun enable(view:View,enabled:Boolean) { view.isEnabled=enabled; view.alpha=if(enabled) 1f else .38f }
 fun screen(subtitle:String,cync:Boolean,onBluetooth:()->Unit,onCync:()->Unit):LinearLayout {
  activity.window.statusBarColor=Color.TRANSPARENT; activity.window.navigationBarColor=Color.TRANSPARENT
  activity.window.decorView.systemUiVisibility=View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
  if(Build.VERSION.SDK_INT>=29) activity.window.isNavigationBarContrastEnforced=false
  val outer=column().apply { setBackgroundColor(Palette.bg) }
  outer.setOnApplyWindowInsetsListener { v,i -> v.setPadding(i.systemWindowInsetLeft,i.systemWindowInsetTop,i.systemWindowInsetRight,i.systemWindowInsetBottom); i }
  activity.setContentView(outer)
  val header=column().apply { setPadding(dp(24),dp(18),dp(24),dp(16)) }; outer.addView(header)
  val brand=row(); header.addView(brand)
  brand.addView(text("Lumi",28f,bold=true)); brand.addView(text("Sync",28f,Palette.accent,true))
  space(header,2); header.addView(text(subtitle,14f,Palette.muted))
  space(header,18)
  pair(header,button("Bluetooth",!cync,onBluetooth),button("GE Cync",cync,onCync))
  val scroll=ScrollView(activity).apply { isFillViewport=true; clipToPadding=false; setPadding(dp(20),dp(4),dp(20),dp(20)); isVerticalScrollBarEnabled=false }
  outer.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
  return column().also { scroll.addView(it,ViewGroup.LayoutParams(-1,-2)) }
 }
 fun field(parent:LinearLayout,label:String,hintText:String,password:Boolean=false):EditText {
  parent.addView(text(label,14f,Palette.muted,true)); space(parent,8)
  val field=EditText(activity).apply {
   hint=hintText; textSize=16f; setSingleLine(); setTextColor(Palette.text); setHintTextColor(Palette.muted)
   inputType=android.text.InputType.TYPE_CLASS_TEXT or (if(password) android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD else android.text.InputType.TYPE_TEXT_VARIATION_URI)
   background=shape(Palette.bg,12); setPadding(dp(14),dp(16),dp(14),dp(16)); isSaveEnabled=!password
   if(password) importantForAutofill=View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
  }
  parent.addView(field,LinearLayout.LayoutParams(-1,-2)); space(parent,16); return field
 }
}

/** A real HSV picker; buttons below provide a non-gesture alternative. */
internal class ColorWheel(context:Context):View(context) {
 var selectedColor=Color.rgb(72,146,255); private set
 var onColor:((Int)->Unit)?=null
 private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
 private var hue=215f
 private var saturation=.72f
 private var radius=1f
 private var startX=0f
 private var startY=0f
 init { contentDescription="Color wheel. Use the named color buttons below as an alternative."; isFocusable=true }
 override fun onDraw(canvas:Canvas) {
  val cx=width/2f; val cy=height/2f
  radius=min(width,height)/2f-18f*resources.displayMetrics.density
  val colors=intArrayOf(Color.RED,Color.YELLOW,Color.GREEN,Color.CYAN,Color.BLUE,Color.MAGENTA,Color.RED)
  paint.shader=SweepGradient(cx,cy,colors,null); canvas.drawCircle(cx,cy,radius,paint)
  paint.shader=RadialGradient(cx,cy,radius,Color.WHITE,Color.TRANSPARENT,Shader.TileMode.CLAMP); canvas.drawCircle(cx,cy,radius,paint)
  paint.shader=null
  val x=cx+cos(Math.toRadians(hue.toDouble())).toFloat()*radius*saturation
  val y=cy+sin(Math.toRadians(hue.toDouble())).toFloat()*radius*saturation
  paint.color=Palette.bg; canvas.drawCircle(x,y,13f*resources.displayMetrics.density,paint)
  paint.color=Color.WHITE; canvas.drawCircle(x,y,10f*resources.displayMetrics.density,paint)
  paint.color=selectedColor; canvas.drawCircle(x,y,7f*resources.displayMetrics.density,paint)
 }
 fun select(color:Int) { val hsv=FloatArray(3); Color.colorToHSV(color,hsv); hue=hsv[0]; saturation=hsv[1]; selectedColor=Color.HSVToColor(floatArrayOf(hue,saturation,1f)); invalidate(); onColor?.invoke(selectedColor) }
 override fun onTouchEvent(event:MotionEvent):Boolean {
  if(!isEnabled) return false
  if(event.action==MotionEvent.ACTION_DOWN) {
   startX=event.x-width/2f; startY=event.y-height/2f
   if(hypot(startX,startY)>radius+16*resources.displayMetrics.density) return false
   parent.requestDisallowInterceptTouchEvent(true)
  }
  when(event.action) {
   MotionEvent.ACTION_DOWN,MotionEvent.ACTION_MOVE,MotionEvent.ACTION_UP -> {
    val x=event.x-width/2f; val y=event.y-height/2f
    hue=((Math.toDegrees(atan2(y,x).toDouble())+360)%360).toFloat(); saturation=(hypot(x,y)/radius).coerceIn(0f,1f)
    selectedColor=Color.HSVToColor(floatArrayOf(hue,saturation,1f)); invalidate(); onColor?.invoke(selectedColor)
    if(event.action==MotionEvent.ACTION_UP) { parent.requestDisallowInterceptTouchEvent(false); performClick() }
    return true
   }
   MotionEvent.ACTION_CANCEL -> { parent.requestDisallowInterceptTouchEvent(false); return true }
  }
  return false
 }
 override fun performClick():Boolean { super.performClick(); return true }
}

internal class LightControls(val ui:LumiUi,parent:LinearLayout,onPower:(Boolean)->Unit,onColor:(Int,Int)->Unit) {
 val powerCard=ui.card(parent)
 private val on=ui.button("Turn on",true) { onPower(true) }
 private val off=ui.button("Turn off") { onPower(false) }
 val colorCard=ui.card(parent)
 val wheel=ColorWheel(ui.activity)
 private val preview=View(ui.activity)
 private val colorName=ui.text("Sky blue",18f,bold=true)
 private val intensityLabel=ui.text("Color intensity · 100%",14f,Palette.muted)
 private val slider=SeekBar(ui.activity).apply { max=99; progress=99; progressTintList=ColorStateList.valueOf(Palette.accent); thumbTintList=ColorStateList.valueOf(Palette.accent); contentDescription="Color intensity" }
 private val apply=ui.button("Apply color",true) { onColor(wheel.selectedColor,slider.progress+1) }
 private val swatches=mutableListOf<Button>()
 init {
  powerCard.addView(ui.text("Power",18f,bold=true)); ui.space(powerCard,12); ui.pair(powerCard,on,off)
  val title=ui.row(); colorCard.addView(title)
  title.addView(ui.text("Make it yours",20f,bold=true),LinearLayout.LayoutParams(0,-2,1f))
  preview.background=ui.shape(wheel.selectedColor,14); title.addView(preview,LinearLayout.LayoutParams(ui.dp(32),ui.dp(32)))
  ui.space(colorCard,6); colorCard.addView(ui.text("Choose a color to set the mood.",14f,Palette.muted))
  colorCard.addView(wheel,LinearLayout.LayoutParams(-1,ui.dp(230)))
  colorName.gravity=Gravity.CENTER; colorCard.addView(colorName); ui.space(colorCard,16)
  val colors=listOf("Coral" to 0xFFFF625C.toInt(),"Amber" to 0xFFFFBB4A.toInt(),"Mint" to 0xFF64FFD4.toInt(),"Sky" to 0xFF4892FF.toInt(),"Violet" to 0xFFB36AFF.toInt(),"White" to Color.WHITE)
  colors.chunked(3).forEach { group ->
   val row=ui.row(); colorCard.addView(row,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=ui.dp(8) })
   group.forEach { (name,color) ->
    val b=ui.button(name) { wheel.select(color); colorName.text=name }
    b.textSize=13f; b.setTextColor(color); row.addView(b,LinearLayout.LayoutParams(0,-2,1f).apply { leftMargin=ui.dp(3); rightMargin=ui.dp(3) }); swatches.add(b)
   }
  }
  ui.space(colorCard,8); colorCard.addView(intensityLabel); colorCard.addView(slider,LinearLayout.LayoutParams(-1,ui.dp(48)))
  ui.space(colorCard,8); ui.full(colorCard,apply)
  wheel.onColor={ color -> preview.background=ui.shape(color,14); colorName.text=String.format("#%06X",0xFFFFFF and color) }
  slider.setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener {
   override fun onProgressChanged(s:SeekBar,p:Int,user:Boolean) { intensityLabel.text="Color intensity · ${p+1}%" }
   override fun onStartTrackingTouch(s:SeekBar) {}
   override fun onStopTrackingTouch(s:SeekBar) {}
  })
  setReady(false)
 }
 fun setReady(ready:Boolean,colorSupported:Boolean=true) {
  ui.enable(on,ready); ui.enable(off,ready); ui.enable(apply,ready && colorSupported)
  colorCard.visibility=if(colorSupported) View.VISIBLE else View.GONE
 }
 fun setColor(color:Int) { wheel.select(color) }
}
