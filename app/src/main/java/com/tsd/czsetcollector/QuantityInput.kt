package com.tsd.czsetcollector

import android.content.Context
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import androidx.appcompat.widget.AppCompatEditText

/** A full keyboard-scanner burst cannot replace the manually entered quantity. */
class QuantityInput(context:Context):AppCompatEditText(context) {
    private var stable="1"
    private var replacing=false
    private val finish=Runnable{finishInput()}
    init {
        inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        setRawInputType(InputType.TYPE_CLASS_NUMBER);setSingleLine(true);setSelectAllOnFocus(true)
        addTextChangedListener(object:TextWatcher {
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){}
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){}
            override fun afterTextChanged(s:Editable?){if(!replacing){removeCallbacks(finish);postDelayed(finish,300)}}
        })
        setOnEditorActionListener{_,_,_->finishInput();true}
        setQuantity("1")
    }
    fun setQuantity(value:String) {
        removeCallbacks(finish);stable=value;replacing=true
        try{setText(value);error=null}finally{replacing=false}
    }
    fun quantity():String{finishInput();return text?.toString().orEmpty()}
    private fun finishInput() {
        removeCallbacks(finish)
        val value=text?.toString().orEmpty()
        if(value.matches(Regex("[0-9]{0,4}"))){stable=value;return}
        val previous=stable;setQuantity(previous)
        error=if(value.length>=14 || value.any{!it.isDigit()})"Сканируйте в поле GTIN; количество сохранено"
            else "Количество от 1 до 1000; прежнее значение сохранено"
    }
    override fun onDetachedFromWindow(){removeCallbacks(finish);super.onDetachedFromWindow()}
}
