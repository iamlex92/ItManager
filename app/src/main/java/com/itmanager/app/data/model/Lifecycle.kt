package com.itmanager.app.data.model

import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** One dated thing that is about to run out: a warranty, a license, a certificate, another deadline. */
data class Expiration(
    val source: String,
    val title: String,
    /** ISO date. */
    val due: String,
    val daysLeft: Int,
    /** expired, week, month, soon or ok. */
    val level: String,
    val deviceId: String?,
)

/** A license without its key: the key is read only on the web console. */
data class LicenseInfo(
    val id: String,
    val software: String,
    val vendor: String?,
    val kind: String,
    val seats: Int?,
    val used: Int,
    val over: Boolean,
    val validUntil: String?,
    val supplier: String?,
    val hasKey: Boolean,
)

data class LifecycleOverview(val expirations: List<Expiration>, val licenses: List<LicenseInfo>)

/** «13.11.2026» from an ISO date; the text itself when it is not one. */
fun shortDate(iso: String?): String? {
    if (iso.isNullOrBlank()) return null
    return try {
        LocalDate.parse(iso.take(10)).format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))
    } catch (e: java.time.format.DateTimeParseException) {
        iso
    }
}

/** Days from today to an ISO date (negative when it is past); null when it is not a date. */
fun daysUntil(iso: String?): Int? {
    if (iso.isNullOrBlank()) return null
    return try {
        java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(), LocalDate.parse(iso.take(10))).toInt()
    } catch (e: java.time.format.DateTimeParseException) {
        null
    }
}

/** «через 12 дн.», «сегодня», «просрочено на 3 дн.». */
fun daysLeftText(days: Int): String = when {
    days < 0 -> "просрочено на ${-days} дн."
    days == 0 -> "сегодня"
    else -> "через $days дн."
}

fun licenseKindLabel(kind: String): String = when (kind) {
    "per_device" -> "на компьютер"
    "per_user" -> "на пользователя"
    "organization" -> "на организацию"
    "subscription" -> "подписка"
    else -> "другое"
}
