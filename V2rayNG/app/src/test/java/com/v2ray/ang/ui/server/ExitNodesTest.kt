package com.v2ray.ang.ui.server

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ExitNodesTest {

    /** As R.string.label_numbered writes it in English. */
    private fun numbered(name: String, number: Int) = "$name ($number)"

    @Test
    fun eachLabelIsTheNameMadeUniqueWhereItIsTakenAlready() {
        assertEquals(
            listOf("germany", "freedom (default) (2)", "france", "freedom (default) (2) (2)"),
            distinctLabels(listOf("germany", "freedom (default)", "france", "freedom (default) (2)"), setOf("freedom (default)"), ::numbered),
        )
        assertEquals(emptyList<String>(), distinctLabels(emptyList(), setOf("freedom (default)"), ::numbered))
        // The number is written as the language of the screen writes it.
        assertEquals(listOf("freedom（默认）（2）"), distinctLabels(listOf("freedom（默认）"), setOf("freedom（默认）")) { name, number -> "$name（$number）" })
    }

}
