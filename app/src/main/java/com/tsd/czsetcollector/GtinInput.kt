package com.tsd.czsetcollector

import android.content.Context
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatEditText

/** Numeric soft keyboard, while retaining full scanner keyboard input until AI 01 is extracted. */
class GtinInput @JvmOverloads constructor(context:Context,attrs:AttributeSet?=null):AppCompatEditText(context,attrs) {
    private var replacing=false
    private val normalize=Runnable{normalizeText(false)}
    init {
        inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        setRawInputType(InputType.TYPE_CLASS_NUMBER)
        textSize=16f
        setSingleLine(true);setSelectAllOnFocus(true)
        addTextChangedListener(object:TextWatcher {
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){}
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){}
            override fun afterTextChanged(s:Editable?){
                if(replacing)return
                removeCallbacks(normalize);error=null
                if((s?.length ?: 0)>=14)postDelayed(normalize,300)
            }
        })
        setOnEditorActionListener{_,_,_->normalizeText(true);true}
    }
    fun scan(raw:String) {
        val gtin=Codes.profileGtin(raw)
        removeCallbacks(normalize);replacing=true
        try{setText(gtin);setSelection(gtin.length);error=null}finally{replacing=false}
    }
    fun validated():String {
        val gtin=Codes.profileGtin(text?.toString().orEmpty())
        scan(gtin);return gtin
    }
    private fun normalizeText(report:Boolean) {
        val raw=text?.toString().orEmpty()
        if(raw.isBlank())return
        try{scan(raw)}catch(e:IllegalArgumentException){if(report || raw.length>=14)error=e.message}
    }
    override fun onDetachedFromWindow(){removeCallbacks(normalize);super.onDetachedFromWindow()}
}
