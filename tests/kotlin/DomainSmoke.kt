package com.tsd.czsetcollector

/** Runs the actual Android domain code using only a JVM. No Android/CHZ mocks. */
object DomainSmoke {
    @JvmStatic fun main(args:Array<String>) {
        var count=0
        fun ok(value:Boolean){check(value);count++}
        fun reject(f:()->Unit){var rejected=false;try{f()}catch(e:IllegalArgumentException){rejected=true};ok(rejected)}
        val g="04607010740016"
        val serial="5w02o2iH/agzq"
        // Synthetic serial; the GTIN is public product metadata. Nothing is submitted.
        val c="01${g}21$serial"
        ok(Codes.gtin(g));ok(!Codes.gtin(g.dropLast(1)+"7"));ok(Codes.inn("7707083893"))
        ok(!Codes.inn("7700000000"));ok(Codes.normalize(c)==c)
        ok(Codes.normalize("]d2"+c+"\u001d91TEST\u001d92CRYPTO")==c)
        ok(Codes.normalize(c+"93ABCD")==c)
        ok(Codes.normalize("(01)${g}(21)$serial")==c)
        val with91="01${g}21ab91cdEF?%123"
        ok(with91.length==31);ok(Codes.normalize(with91+"\u001d91KEY")==with91)
        val quoted="01${g}21a\"b/c%&=<>123"
        ok(quoted.length==31);ok(Codes.normalize(quoted)==quoted)
        reject{Codes.normalize("00"+"0".repeat(18))}
        reject{Codes.normalize("01${g}21short")}
        reject{Codes.normalize(c+"WRONG")}
        reject{Codes.normalize("01${g}21"+"я".repeat(13))}
        ok(Codes.composition("$g=6")==mapOf(g to 6))
        reject{Codes.composition("$g=0")}
        reject{Codes.composition("$g=3\n$g=3")}
        reject{Codes.composition("$g=1001")}
        reject{Codes.composition("")}
        val a=Job();val b=Job();a.children.add(c)
        ok(b.children.isEmpty());ok(a.id!=b.id)
        ok(Labels.status("VERIFIED").contains("проверен"))
        println("PASS: $count JVM checks against Domain.kt")
    }
}
