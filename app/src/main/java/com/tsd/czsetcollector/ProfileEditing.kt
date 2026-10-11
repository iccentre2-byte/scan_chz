package com.tsd.czsetcollector

data class CompositionRow(val gtin:String,val quantity:String)

/** Editor drafts never mutate saved profiles or queue templates. */
object ProfileEditing {
    fun composition(rows:List<CompositionRow>):Map<String,Int> {
        require(rows.isNotEmpty() && rows.size<=100){"Добавьте состав: не более 100 разных GTIN"}
        val text=rows.joinToString("\n") { row->
            val gtin=Codes.profileGtin(row.gtin)
            val qty=row.quantity.trim()
            require(qty.matches(Regex("[0-9]{1,4}"))){"Количество должно быть целым числом от 1 до 1000"}
            "$gtin=$qty"
        }
        return Codes.composition(text)
    }
    fun step(quantity:String,delta:Int):Int {
        require(delta==1 || delta == -1){"Можно изменить количество на одну пачку"}
        val current=quantity.toIntOrNull()
        require(current!=null && current in 1..1000){"Введите количество от 1 до 1000"}
        return (current+delta).coerceIn(1,1000)
    }
    fun availableTitle(base:String,profiles:List<OrganizationProfile>):String {
        var next=base;var suffix=2
        while(profiles.any{it.title.equals(next,true)})next="$base ${suffix++}"
        return next
    }
    fun saved(profiles:List<OrganizationProfile>,editingIndex:Int?,candidate:OrganizationProfile):ProfileData {
        require(editingIndex==null || editingIndex in profiles.indices){"Редактируемый профиль больше не найден"}
        require(candidate.title.isNotBlank()){ "Назовите профиль" }
        require(profiles.indices.none{it!=editingIndex && profiles[it].title.trim().equals(candidate.title.trim(),true)}){
            "Профиль с таким названием уже есть. Укажите другое название; существующий профиль не заменяется"
        }
        val next=profiles.toMutableList()
        val at=editingIndex ?: next.size
        if(editingIndex==null)next.add(candidate)else next[at]=candidate
        return ProfileData(next,at)
    }
}
