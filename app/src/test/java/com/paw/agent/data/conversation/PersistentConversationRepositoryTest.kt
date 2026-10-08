package com.paw.agent.data.conversation

import com.paw.agent.core.model.Conversation
import com.paw.agent.core.model.Message
import com.paw.agent.core.model.MessageRole
import com.paw.agent.core.model.MessageStatus
import com.paw.agent.core.model.ToolCall
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class PersistentConversationRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `addMessage updates state and persists conversation to disk`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)
        val dir = tempFolder.newFolder("test_conv_1")

        val repo = PersistentConversationRepository(
            scope = testScope,
            storageDirectory = dir,
            ioDispatcher = testDispatcher,
        )
        testScope.advanceUntilIdle()

        val msg = Message(
            id = "msg-1",
            role = MessageRole.USER,
            content = "打开设置应用",
            createdAt = System.currentTimeMillis(),
        )
        repo.addMessage(msg)
        testScope.advanceUntilIdle()

        val currentConv = repo.conversation.value
        assertEquals(1, currentConv.messages.size)
        assertEquals("打开设置应用", currentConv.messages.first().content)

        val savedFile = File(dir, "${currentConv.id}.json")
        assertTrue("Disk file should exist", savedFile.exists())
        assertTrue(savedFile.readText().contains("打开设置应用"))
    }

    @Test
    fun `reloads existing conversations from disk on init`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)
        val dir = tempFolder.newFolder("test_conv_reload")

        val repo1 = PersistentConversationRepository(
            scope = testScope,
            storageDirectory = dir,
            ioDispatcher = testDispatcher,
        )
        testScope.advanceUntilIdle()

        repo1.addMessage(Message(id = "m1", role = MessageRole.USER, content = "第一条历史消息"))
        testScope.advanceUntilIdle()
        val originalId = repo1.conversation.value.id

        // Create second repo instance pointing to the same folder
        val repo2 = PersistentConversationRepository(
            scope = testScope,
            storageDirectory = dir,
            ioDispatcher = testDispatcher,
        )
        testScope.advanceUntilIdle()

        assertEquals(originalId, repo2.conversation.value.id)
        assertEquals("第一条历史消息", repo2.conversation.value.messages.first().content)
        assertEquals(1, repo2.historyList.value.size)
    }

    @Test
    fun `newConversation creates new conversation and updates history`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)
        val dir = tempFolder.newFolder("test_conv_new")

        val repo = PersistentConversationRepository(
            scope = testScope,
            storageDirectory = dir,
            ioDispatcher = testDispatcher,
        )
        testScope.advanceUntilIdle()

        val newId = repo.newConversation()
        testScope.advanceUntilIdle()

        assertEquals(newId, repo.conversation.value.id)
        assertTrue(repo.conversation.value.messages.isEmpty())
        assertTrue(repo.historyList.value.any { it.id == newId })
    }

    @Test
    fun `deleteConversation deletes file and falls back cleanly`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)
        val dir = tempFolder.newFolder("test_conv_del")

        val repo = PersistentConversationRepository(
            scope = testScope,
            storageDirectory = dir,
            ioDispatcher = testDispatcher,
        )
        testScope.advanceUntilIdle()

        val idToDelete = repo.conversation.value.id
        repo.deleteConversation(idToDelete)
        testScope.advanceUntilIdle()

        val deletedFile = File(dir, "$idToDelete.json")
        assertFalse(deletedFile.exists())
        assertFalse(repo.historyList.value.any { it.id == idToDelete })
    }

    @Test
    fun `concurrent message additions do not race or corrupt disk state`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)
        val dir = tempFolder.newFolder("test_conv_concurrent")

        val repo = PersistentConversationRepository(
            scope = testScope,
            storageDirectory = dir,
            ioDispatcher = testDispatcher,
        )
        testScope.advanceUntilIdle()

        val jobs = (1..20).map { i ->
            testScope.launch {
                repo.addMessage(Message(id = "msg-$i", role = MessageRole.USER, content = "并发测试消息 $i"))
            }
        }
        jobs.joinAll()
        testScope.advanceUntilIdle()

        val conv = repo.conversation.value
        assertEquals(20, conv.messages.size)

        val file = File(dir, "${conv.id}.json")
        assertTrue(file.exists())
        // File must be valid JSON parseable
        val content = file.readText()
        assertTrue(content.contains("并发测试消息 20"))
    }

    @Test
    fun `computeStats prioritizes structured toolCalls and handles unstructured fallback`() {
        val convWithStructuredCalls = Conversation(
            id = "c1",
            title = "搜索任务",
            messages = listOf(
                Message(id = "u1", role = MessageRole.USER, content = "在微信中搜索用户"),
                Message(
                    id = "a1",
                    role = MessageRole.ASSISTANT,
                    content = "",
                    toolCalls = listOf(
                        ToolCall(id = "tc1", name = "launch_app", arguments = "{}"),
                        ToolCall(id = "tc2", name = "tap", arguments = "{}"),
                        ToolCall(id = "tc3", name = "tap", arguments = "{}"),
                    ),
                ),
            ),
            createdAt = 1000L,
            updatedAt = 5000L,
        )

        val stats = PersistentConversationRepository.computeStats(convWithStructuredCalls)
        assertEquals(3, stats.stepCount)
        assertEquals(1, stats.toolCounts["launch_app"])
        assertEquals(2, stats.toolCounts["tap"])
        assertEquals(4000L, stats.durationMs)

        val convWithLegacyText = Conversation(
            id = "c2",
            title = "旧版任务",
            messages = listOf(
                Message(id = "u2", role = MessageRole.USER, content = "旧版测试"),
                Message(id = "t1", role = MessageRole.TOOL, content = "✔ inputText: 完成输入"),
            ),
        )
        val statsLegacy = PersistentConversationRepository.computeStats(convWithLegacyText)
        assertEquals(1, statsLegacy.stepCount)
        assertEquals(1, statsLegacy.toolCounts["inputText"])
    }

    @Test
    fun `initialization race condition preserves newly posted message`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)
        val dir = tempFolder.newFolder("test_race")

        // Pre-populate old conversation on disk
        val oldConvFile = File(dir, "old-session.json")
        oldConvFile.writeText("""
            {
              "id": "old-session",
              "title": "旧历史会话",
              "createdAt": 1000,
              "updatedAt": 2000,
              "messages": [
                { "id": "m-old", "role": "user", "content": "历史任务" }
              ]
            }
        """.trimIndent())

        // Create repo instance
        val repo = PersistentConversationRepository(
            scope = testScope,
            storageDirectory = dir,
            ioDispatcher = testDispatcher,
        )

        // Immediately add a new message before disk load finishes
        repo.addMessage(Message(id = "m-new", role = MessageRole.USER, content = "用户刚发送的新任务"))

        // Complete all async coroutines
        testScope.advanceUntilIdle()

        assertTrue(repo.isInitialized.value)
        val messages = repo.conversation.value.messages
        assertTrue("Newly posted message must not be overwritten by disk reload", messages.any { it.content == "用户刚发送的新任务" })
    }

    @Test
    fun `clear deletes image files belonging to current conversation`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)
        val dir = tempFolder.newFolder("test_clear_media")

        val repo = PersistentConversationRepository(
            scope = testScope,
            storageDirectory = dir,
            ioDispatcher = testDispatcher,
        )
        testScope.advanceUntilIdle()

        val sampleBase64 = "data:image/jpeg;base64,/9j/4AAQSkZJRg=="
        repo.addMessage(Message(
            id = "img-msg",
            role = MessageRole.USER,
            content = "带图消息",
            images = listOf(sampleBase64),
        ))
        testScope.advanceUntilIdle()

        val imagesDir = File(dir, "images")
        val imageFilesBefore = imagesDir.listFiles() ?: emptyArray()
        assertTrue("Image file should be extracted and saved to disk", imageFilesBefore.isNotEmpty())

        repo.clear()
        testScope.advanceUntilIdle()

        val imageFilesAfter = imagesDir.listFiles() ?: emptyArray()
        assertEquals("Image files belonging to cleared conversation should be deleted", 0, imageFilesAfter.size)
    }

    @Test
    fun `corrupt json file is backed up, deleted, and capped`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)
        val dir = tempFolder.newFolder("test_corrupt_files")

        // Create corrupt files
        val badJsonFile = File(dir, "corrupt-conv.json")
        badJsonFile.writeText("{ this is definitely not valid json")

        val repo = PersistentConversationRepository(
            scope = testScope,
            storageDirectory = dir,
            ioDispatcher = testDispatcher,
        )
        testScope.advanceUntilIdle()

        assertFalse("Original corrupt json file should be deleted", badJsonFile.exists())
        val corruptBackups = dir.listFiles { f -> f.name.startsWith("corrupt-conv") && f.name.endsWith(".corrupt") } ?: emptyArray()
        assertEquals("Should create 1 corrupt backup", 1, corruptBackups.size)
    }
}
