package com.itmanager.app

import com.itmanager.app.data.model.MergePreview
import org.junit.Assert.assertTrue
import org.junit.Test

class MergePreviewTest {
    private fun preview(ports: Int = 0, dropped: Int = 0) = MergePreview(
        keepName = "SRV-1", sourceName = "SRV-1-b", ports = ports, cablesMoved = 1, cablesDropped = dropped, configs = 0,
        alerts = 0, peripherals = 2, faceplateMoved = false, guests = 0, rackMembers = 0,
        extraIpsAdded = listOf("10.0.0.9"), filledFields = listOf("comment"),
    )

    @Test
    fun summaryNamesWhatMovesAndWhatIsLost() {
        val text = preview(ports = 3, dropped = 1).summary()
        assertTrue("портов: 3" in text && "кабелей: 1" in text && "периферии: 2" in text)
        assertTrue("10.0.0.9" in text && "comment" in text && "будут удалены): 1" in text)
        assertTrue(text.startsWith("«SRV-1-b» будет удалено из учёта и объединено с «SRV-1»"))
    }
}
