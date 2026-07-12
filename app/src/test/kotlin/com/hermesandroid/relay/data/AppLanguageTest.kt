package com.hermesandroid.relay.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLanguageTest {

    @Test
    fun emptyLocaleListUsesSystemDefault() {
        assertEquals(AppLanguage.SYSTEM_DEFAULT, AppLanguage.fromLanguageTags(""))
    }

    @Test
    fun englishRegionsResolveToEnglish() {
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromLanguageTags("en-US"))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromLanguageTags("en-GB,fr"))
    }

    @Test
    fun simplifiedChineseTagsResolveToSimplifiedChinese() {
        assertEquals(AppLanguage.SIMPLIFIED_CHINESE, AppLanguage.fromLanguageTags("zh-Hans"))
        assertEquals(AppLanguage.SIMPLIFIED_CHINESE, AppLanguage.fromLanguageTags("zh-CN"))
        assertEquals(AppLanguage.SIMPLIFIED_CHINESE, AppLanguage.fromLanguageTags("zh-SG"))
    }

    @Test
    fun languageOptionsProduceExpectedLocaleLists() {
        assertTrue(AppLanguage.SYSTEM_DEFAULT.toLocaleList().isEmpty)
        assertEquals("en", AppLanguage.ENGLISH.toLocaleList().toLanguageTags())
        assertEquals("zh-Hans", AppLanguage.SIMPLIFIED_CHINESE.languageTag)
        assertEquals(
            "zh",
            AppLanguage.SIMPLIFIED_CHINESE.toLocaleList()[0]?.language,
        )
    }
}
