package com.tailorshop.counter

// Which installed TTS voice speaks an announcement (owner 11-02, Alexa #4 6574558463). Android voices carry no gender
// metadata, so a "male" voice is only one the owner picked and heard in Settings (saved as maleVoice-en), or one whose
// engine name says male (never "female"). Pitch is never used to fake one: no male voice → null, and the caller reports it.
object Voices {
    /** What the selection needs from android.speech.tts.Voice (kept plain for tests). */
    data class Option(val name: String, val language: String, val installed: Boolean = true, val network: Boolean = false)

    private fun namedMale(name: String) = Regex("(^|[^a-z])male([^a-z]|$)", RegexOption.IGNORE_CASE).containsMatchIn(name)

    /** The male English voice for a DUE announcement, or null when none is installed / chosen. */
    fun maleEnglish(voices: List<Option>, chosen: String?, online: Boolean): Option? {
        val usable = voices.filter { it.language == "en" && it.installed && (online || !it.network) }
        return usable.firstOrNull { it.name == chosen } ?: usable.filter { namedMale(it.name) }.minByOrNull { if (it.network) 1 else 0 }
    }

    /** The usual Sofia voice (the owner's Russian voice choice), or null for the engine's Russian default. */
    fun sofia(voices: List<Option>, chosen: String?, online: Boolean): Option? =
        voices.firstOrNull { it.name == chosen && it.language == "ru" && it.installed && (online || !it.network) }
}
