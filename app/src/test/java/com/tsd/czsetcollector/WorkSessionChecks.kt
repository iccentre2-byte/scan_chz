package com.tsd.czsetcollector

/** These same checks run under JUnit in CI and standalone on the JVM. */
object WorkSessionChecks {
    @JvmStatic fun main(args:Array<String>) { println("WorkSession checks: ${run()} PASS") }
    fun run():Int {
        var n=0
        fun ok(value:Boolean){check(value){"Failed check #${n+1}"};n++}
        fun rejects(block:()->Unit){ok(runCatching(block).isFailure)}
        val expected=linkedMapOf("04607010740016" to 6)
        val p=OrganizationProfile("7707083893","grocery","Кириешки 6","production",Template("04609144660000",expected))
        val job=Job(inn=p.inn,environment=p.environment,template=p.template,setCode="01${p.template.setGtin}21ABCDEFGHIJKLM")
        val s=WorkSession()
        ok(s.profile==null);ok(!s.streaming)
        ok(!s.canReceive(true,false,false));ok(!s.canTrigger(true,false,false));rejects{s.start()}
        s.enter(p,null)
        ok(s.profile==p);ok(!s.streaming);ok(s.canReceive(true,false,false));ok(!s.canTrigger(true,false,false))
        rejects{s.enter(p,null)}
        expected["04607010740016"]=99
        ok(s.profile!!.template.expected["04607010740016"]==6)
        expected["04607010740016"]=6
        s.start();ok(s.streaming);ok(s.canTrigger(true,false,false))
        ok(!s.canTrigger(false,false,false));ok(!s.canTrigger(true,true,false));ok(!s.canTrigger(true,false,true))
        ok(!s.canReceive(false,false,false));ok(!s.canReceive(true,true,false));ok(!s.canReceive(true,false,true))
        ok(s.owns(job));ok(!s.owns(job.copy(inn="500100732259")));ok(!s.owns(job.copy(environment="sandbox")))
        ok(!s.owns(job.copy(template=Template(p.template.setGtin,mapOf("04607010740016" to 5)))))
        ok(!s.owns(job.copy(pg="milk")))
        val frozen=s.profile
        rejects{s.leave(job)};ok(s.profile==frozen)
        s.pause();ok(!s.streaming);ok(s.profile==frozen);ok(s.canReceive(true,false,false));ok(!s.canTrigger(true,false,false))
        s.start();ok(s.streaming)
        s.leave(null);ok(s.profile==null);ok(!s.streaming);ok(!s.owns(job))
        rejects{s.enter(p,job.copy(environment="sandbox"))};ok(s.profile==null)
        s.enter(WorkSession.fromJob(job,"Восстановленный профиль"),job)
        ok(s.owns(job));ok(!s.streaming);ok(s.profile!!.title=="Восстановленный профиль")
        s.leave(null)
        rejects{s.enter(p.copy(inn="bad"),null)}
        rejects{s.enter(p.copy(environment="unknown"),null)}
        rejects{s.enter(p.copy(pg="milk"),null)}
        rejects{s.enter(p.copy(template=Template("bad",expected)),null)}
        rejects{s.enter(p.copy(template=Template(p.template.setGtin,emptyMap())),null)}
        ok(s.profile==null);ok(!s.streaming)
        // Same organization but different GTIN composition is NOT the same work context.
        s.enter(p,null)
        val titleOnly=job.copy()
        ok(s.owns(titleOnly));s.leave(null)
        return n
    }
}
