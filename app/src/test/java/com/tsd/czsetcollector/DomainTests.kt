package com.tsd.czsetcollector
import org.junit.Assert.*
import org.junit.Test

class DomainTests {
    private val gtin="04607010740016"
    private val code="01${gtin}215w02o2iH/agzq"
    @Test fun groceryNormalization(){assertEquals(code,Codes.normalize("]d2"+code+"\u001d93ABCD"))}
    @Test fun serialWith91(){val x="01${gtin}21ab91cdEF?%123";assertEquals(x,Codes.normalize(x+"\u001d91SIGN"))}
    @Test fun composition(){assertEquals(mapOf(gtin to 6),Codes.composition("$gtin=6"))}
    @Test(expected=IllegalArgumentException::class) fun duplicateGtin(){Codes.composition("$gtin=3\n$gtin=3")}
    @Test(expected=IllegalArgumentException::class) fun zeroQty(){Codes.composition("$gtin=0")}
    @Test(expected=IllegalArgumentException::class) fun ssccIsNotSet(){Codes.normalize("00"+"0".repeat(18))}
    @Test fun checksums(){assertTrue(Codes.gtin(gtin));assertTrue(Codes.inn("7707083893"));assertFalse(Codes.inn("7700000000"))}
    @Test fun independentLists(){val a=Job();val b=Job();a.children.add(code);assertTrue(b.children.isEmpty());assertNotEquals(a.id,b.id)}
    @Test fun pcUrlValidation(){PcClient.validate(PcConfig("https://192.168.1.2:18765","k".repeat(43),"a".repeat(64)))}
    @Test(expected=IllegalArgumentException::class) fun rejectPublicPc(){PcClient.validate(PcConfig("https://8.8.8.8:18765","k".repeat(43),"a".repeat(64)))}
    @Test(expected=IllegalArgumentException::class) fun rejectUnencryptedPc(){PcClient.validate(PcConfig("http://192.168.1.2:18765","k".repeat(43),"a".repeat(64)))}
}
