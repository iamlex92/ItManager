package com.itmanager.app.data.model

/** Kinds of peripherals, in the order the form offers them. */
enum class PeripheralKind(val label: String) {
    MONITOR("Монитор"),
    KEYBOARD("Клавиатура"),
    MOUSE("Мышь"),
    DOCK("Док-станция"),
    HEADSET("Гарнитура"),
    WEBCAM("Веб-камера"),
    SPEAKERS("Колонки"),
    SCANNER("Сканер"),
    PRINTER("Принтер (USB)"),
    PROJECTOR("Проектор"),
    UPS("ИБП"),
    OTHER("Другое");

    companion object {
        fun parse(raw: String?): PeripheralKind = entries.firstOrNull { it.name == raw?.uppercase() } ?: OTHER
    }
}

/**
 * Equipment without a network port that belongs to a workplace: a monitor, a keyboard, a docking station.
 * [deviceId] is the device it is attached to (usually a computer); null: it is in stock.
 */
data class Peripheral(
    val id: String,
    val kind: PeripheralKind,
    val vendor: String?,
    val model: String?,
    /** What matters about it in a few words: «24", 1920×1080». */
    val specs: String?,
    val serialNumber: String?,
    val inventoryNumber: String?,
    val comment: String?,
    val deviceId: String?,
    val deviceName: String?,
) {
    /** «Монитор Dell P2422H» */
    val title: String get() = listOfNotNull(kind.label, vendor, model).joinToString(" ")

    /** «24", 1920×1080 · S/N CN0X1 · инв. 00123» */
    val details: String
        get() = listOfNotNull(
            specs,
            serialNumber?.let { "S/N $it" },
            inventoryNumber?.let { "инв. $it" },
        ).joinToString(" · ")

    fun toDraft() = PeripheralDraft(kind, vendor, model, specs, serialNumber, inventoryNumber, comment, deviceId)

    fun matches(query: String): Boolean {
        val q = query.trim()
        if (q.isEmpty()) return true
        return listOfNotNull(kind.label, vendor, model, specs, serialNumber, inventoryNumber, comment, deviceName)
            .any { it.contains(q, ignoreCase = true) }
    }
}

/** What the peripheral form sends: a new item or changes to one. Blank fields are cleared. */
data class PeripheralDraft(
    val kind: PeripheralKind,
    val vendor: String? = null,
    val model: String? = null,
    val specs: String? = null,
    val serialNumber: String? = null,
    val inventoryNumber: String? = null,
    val comment: String? = null,
    val deviceId: String? = null,
) {
    fun trimmed() = copy(
        vendor = vendor.clean(), model = model.clean(), specs = specs.clean(), serialNumber = serialNumber.clean(),
        inventoryNumber = inventoryNumber.clean(), comment = comment.clean(),
    )

    private fun String?.clean() = this?.trim()?.takeIf { it.isNotEmpty() }
}

/** Devices peripherals are never attached to: the cable plant and cabinets. */
val DeviceType.takesPeripherals: Boolean
    get() = this != DeviceType.RACK && this != DeviceType.SOCKET && this != DeviceType.PATCH_PANEL
