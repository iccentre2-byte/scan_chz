package com.tsd.czsetcollector

/** A working session owns a copy of its profile. UI selection cannot retarget it. */
class WorkSession {
    var profile: OrganizationProfile? = null
        private set
    var streaming = false
        private set

    fun enter(candidate: OrganizationProfile, current: Job?) {
        check(profile == null) { "Сначала завершите рабочую сессию" }
        require(Codes.inn(candidate.inn)) { "Проверьте ИНН профиля" }
        require(candidate.pg == "grocery" && candidate.environment in setOf("production", "sandbox")) { "Неподдерживаемый профиль" }
        require(Codes.gtin(candidate.template.setGtin)) { "Проверьте GTIN набора" }
        val expected = Codes.composition(candidate.template.expected.entries.joinToString("\n") { "${it.key}=${it.value}" })
        val frozen = candidate.copy(template = Template(candidate.template.setGtin, expected.toMap()))
        require(current == null || matches(frozen, current)) { "Незавершённый набор относится к другому профилю. Сначала завершите или сбросьте его." }
        profile = frozen
        streaming = false
    }

    fun leave(current: Job?) {
        check(current == null) { "Сначала завершите или сбросьте незавершённый набор" }
        pause()
        profile = null
    }

    fun start() {
        check(profile != null) { "Сначала выберите профиль и начните работу" }
        streaming = true
    }

    fun pause() { streaming = false }
    fun owns(job: Job): Boolean = profile?.let { matches(it, job) } ?: false
    fun canReceive(foreground: Boolean, halted: Boolean, dialogOpen: Boolean): Boolean =
        profile != null && foreground && !halted && !dialogOpen
    fun canTrigger(foreground: Boolean, halted: Boolean, dialogOpen: Boolean): Boolean =
        streaming && canReceive(foreground, halted, dialogOpen)

    companion object {
        fun matches(p: OrganizationProfile, j: Job): Boolean =
            p.inn == j.inn && p.pg == j.pg && p.environment == j.environment && p.template == j.template
        fun fromJob(j: Job, title: String = "Незавершённый набор"): OrganizationProfile =
            OrganizationProfile(j.inn, j.pg, title, j.environment, j.template.copy(expected = j.template.expected.toMap()))
    }
}
