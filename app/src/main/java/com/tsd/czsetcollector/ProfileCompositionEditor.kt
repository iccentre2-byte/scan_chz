package com.tsd.czsetcollector

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.*

class ProfileCompositionEditor @JvmOverloads constructor(context:Context,attrs:AttributeSet?=null):LinearLayout(context,attrs) {
    private data class Row(val container:LinearLayout,val gtin:GtinInput,val quantity:QuantityInput)
    private val rows=mutableListOf<Row>()
    var onTarget:((GtinInput,String)->Unit)?=null
    var onScan:((GtinInput,String)->Unit)?=null
    var onError:((String)->Unit)?=null
    init{orientation=VERTICAL}
    private fun dp(value:Int)=(value*resources.displayMetrics.density).toInt()
    fun show(expected:Map<String,Int>) {
        removeAllViews();rows.clear()
        if(expected.isEmpty())addRow()else expected.forEach{(g,n)->addRow(g,n.toString())}
    }
    fun addRow(gtinValue:String="",quantityValue:String="1"):GtinInput {
        require(rows.size<100){"Не более 100 разных GTIN в составе"}
        val container=LinearLayout(context).apply{orientation=VERTICAL;setPadding(0,dp(8),0,dp(12))}
        val title=TextView(context).apply{text="GTIN пачки";textSize=15f}
        val gtinLine=LinearLayout(context).apply{orientation=HORIZONTAL}
        val gtin=GtinInput(context).apply{hint="14 цифр или скан кода пачки";minHeight=dp(48);setText(gtinValue)}
        val scan=Button(context).apply{text="Скан";minHeight=dp(48);contentDescription="Сканировать GTIN пачки"}
        gtinLine.addView(gtin,LayoutParams(0,LayoutParams.WRAP_CONTENT,1f));gtinLine.addView(scan,LayoutParams(dp(80),LayoutParams.WRAP_CONTENT))
        val counts=LinearLayout(context).apply{orientation=HORIZONTAL;gravity=android.view.Gravity.CENTER_VERTICAL}
        val label=TextView(context).apply{text="Количество"}
        val minus=Button(context).apply{text="−";minHeight=dp(48);setPadding(0,0,0,0);contentDescription="Уменьшить количество на одну пачку"}
        val quantity=QuantityInput(context).apply{setQuantity(quantityValue);minHeight=dp(48);gravity=android.view.Gravity.CENTER;contentDescription="Количество пачек от 1 до 1000"}
        val plus=Button(context).apply{text="+";minHeight=dp(48);setPadding(0,0,0,0);contentDescription="Увеличить количество на одну пачку"}
        counts.addView(label,LayoutParams(0,LayoutParams.WRAP_CONTENT,1f))
        counts.addView(minus,LayoutParams(dp(48),LayoutParams.WRAP_CONTENT));counts.addView(quantity,LayoutParams(dp(68),LayoutParams.WRAP_CONTENT));counts.addView(plus,LayoutParams(dp(48),LayoutParams.WRAP_CONTENT))
        val remove=Button(context).apply{text="Удалить эту строку";minHeight=dp(48)}
        container.addView(title);container.addView(gtinLine);container.addView(counts);container.addView(remove)
        val row=Row(container,gtin,quantity);rows.add(row);addView(container)
        gtin.setOnFocusChangeListener{_,focused->if(focused)onTarget?.invoke(gtin,"GTIN пачки")}
        scan.setOnClickListener{onScan?.invoke(gtin,"GTIN пачки")}
        fun step(delta:Int){try{quantity.setQuantity(ProfileEditing.step(quantity.quantity(),delta).toString())}catch(e:Exception){quantity.error=e.message;onError?.invoke(e.message?:"Проверьте количество")}}
        minus.setOnClickListener{step(-1)};plus.setOnClickListener{step(1)}
        remove.setOnClickListener{
            rows.remove(row);removeView(container)
            if(rows.isEmpty())addRow()
        }
        applyEnabled(container,isEnabled)
        return gtin
    }
    fun composition():Map<String,Int> {
        val draft=rows.map{row->
            val gtin=try{row.gtin.validated()}catch(e:Exception){row.gtin.error=e.message;row.gtin.requestFocus();throw e}
            val qty=row.quantity.quantity()
            val amount=qty.toIntOrNull()
            if(amount==null || amount !in 1..1000){
                row.quantity.error="Количество от 1 до 1000";row.quantity.requestFocus()
                throw IllegalArgumentException("Введите количество пачек от 1 до 1000")
            }
            CompositionRow(gtin,qty)
        }
        return ProfileEditing.composition(draft)
    }
    override fun setEnabled(enabled:Boolean){super.setEnabled(enabled);for(i in 0 until childCount)applyEnabled(getChildAt(i),enabled)}
    private fun applyEnabled(view:View,enabled:Boolean){view.isEnabled=enabled;if(view is android.view.ViewGroup)for(i in 0 until view.childCount)applyEnabled(view.getChildAt(i),enabled)}
}
