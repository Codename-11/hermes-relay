package com.hermesandroid.relay.ui.components

/** Unicode first-strong direction, independent of the device locale. */
internal fun isRtlText(text: CharSequence): Boolean {
    var offset = 0
    while (offset < text.length) {
        val codePoint = Character.codePointAt(text, offset)
        when (Character.getDirectionality(codePoint)) {
            Character.DIRECTIONALITY_LEFT_TO_RIGHT -> return false
            Character.DIRECTIONALITY_RIGHT_TO_LEFT,
            Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC -> return true
        }
        offset += Character.charCount(codePoint)
    }
    return false
}
