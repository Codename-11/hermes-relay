package com.hermesandroid.relay.screenshots

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.hermesandroid.relay.data.ChatMessage
import com.hermesandroid.relay.data.MessageRole
import com.hermesandroid.relay.ui.components.ChatInputBar
import com.hermesandroid.relay.ui.components.ChatInputTrailing
import com.hermesandroid.relay.ui.components.MarkdownContent
import com.hermesandroid.relay.ui.components.MessageBubble
import com.hermesandroid.relay.ui.theme.HermesRelayTheme
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Local, synthetic visual evidence; no server, credentials or conversation history. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h800dp-xhdpi")
class ChatRtlScreenshotTest {
    @get:Rule val compose = createComposeRule()

    @Test fun arabicConversationAndComposer() {
        var reply by mutableStateOf("أهلاً! ")
        var streaming by mutableStateOf(true)
        compose.setContent {
            HermesRelayTheme(themePreference = "dark") {
                Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    MessageBubble(ChatMessage(id = "user", role = MessageRole.USER,
                        content = "ممكن تشرحلي الخطوات؟", timestamp = 1_700_000_000_000L), animationEnabled = false)
                    MessageBubble(ChatMessage(id = "assistant", role = MessageRole.ASSISTANT,
                        content = reply, isStreaming = streaming, timestamp = 1_700_000_000_000L),
                        showAgentIdentity = false, showStreamingStatus = false, animationEnabled = false)
                    ChatInputBar(value = "تمام، نبدأ من هنا", onValueChange = {}, placeholder = "Message",
                        trailing = ChatInputTrailing.SEND, onSend = {}, onVoice = {}, onStop = {},
                        onAttachPhotos = {}, onAttachFiles = {}, onAttachCamera = {}, onPasteImage = {},
                        onLongPressAttach = {}, charLimit = 20_000, caption = null, voiceReady = false,
                        showVoiceHint = false, onVoiceHintShown = {}, isDarkTheme = true)
                }
            }
        }
        compose.runOnIdle { reply += "هنمشي واحدة واحدة، وكل خطوة فيها مثال صغير عشان الكلام يبقى واضح.\n\nافتح الملف الأول، وبعد كده احفظ التغييرات وجرب النتيجة.\n\n- راجع الإعدادات\n- جرّب رسالة جديدة" }
        waitFor("راجع الإعدادات")
        capture("arabic-streaming")
        compose.runOnIdle { streaming = false }
        capture("arabic-finished")
    }

    @Test fun mixedParagraphsListsAndCode() {
        compose.setContent {
            HermesRelayTheme(themePreference = "dark") {
                Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
                    MarkdownContent("# الخطوات\n\n😀 123 ... افتح الملف وراجع الإعدادات.\n\nEnglish paragraphs stay on the left.\n\n- أول خطوة بالعربي\n- English list item\n- شغّل `tool --flag (x)` دلوقتي\n\n7. احفظ التغييرات\n8. Check the output\n\n```sh\ntool --flag (x)\n```\n\nخلصنا كده.", MaterialTheme.colorScheme.onSurface)
                }
            }
        }
        waitFor("خلصنا كده.")
        capture("mixed-markdown")
    }

    @Test fun englishConversationKeepsItsLayout() {
        compose.setContent {
            HermesRelayTheme(themePreference = "dark") {
                Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    MessageBubble(ChatMessage(id = "user", role = MessageRole.USER, content = "Show me the steps.", timestamp = 1_700_000_000_000L), animationEnabled = false)
                    MessageBubble(ChatMessage(id = "assistant", role = MessageRole.ASSISTANT,
                        content = "# Steps\n\nStart here.\n\nOpen the file and save the changes.\n\n- Check settings\n- Send a test message\n\n7. Save the file\n8. Check the output", timestamp = 1_700_000_000_000L),
                        showAgentIdentity = false, animationEnabled = false)
                }
            }
        }
        waitFor("Check the output")
        capture("english-conversation")
    }

    private fun waitFor(text: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun capture(name: String) {
        val output = File("build/ui-evidence/chat-rtl/$name.png").apply { parentFile?.mkdirs() }
        compose.onRoot().captureRoboImage(output.absolutePath)
    }
}
