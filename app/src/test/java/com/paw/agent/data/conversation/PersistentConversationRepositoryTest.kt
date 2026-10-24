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
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
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
    @Test fun `media write failure preserves original attachment`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val dir = tempFolder.newFolder("media_write_failure")
        val repo = PersistentConversationRepository(scope = scope, storageDirectory = dir, ioDispatcher = dispatcher)
        scope.advanceUntilIdle()
        val images = File(dir, "images")
        assertTrue(images.delete())
        images.writeText("block image writes")
        val raw = java.util.Base64.getEncoder().encodeToString(byteArrayOf(0x89.toByte(), 80, 78, 71, 13, 10, 26, 10))
        repo.addMessage(Message("photo", MessageRole.USER, "image", images = listOf(raw)))
        scope.advanceUntilIdle()
        assertEquals(listOf(raw), repo.conversation.value.messages.single().images)
    }

    @Test fun `media cleanup and queued normalization never resurrect image references`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val dir = tempFolder.newFolder("media_clear_race")
        val repo = PersistentConversationRepository(scope = scope, storageDirectory = dir, ioDispatcher = dispatcher)
        scope.advanceUntilIdle()
        val raw = java.util.Base64.getEncoder().encodeToString(byteArrayOf(0x89.toByte(), 80, 78, 71, 13, 10, 26, 10))
        repo.addMessage(Message("photo", MessageRole.USER, "image", images = listOf(raw)))
        repo.clearAllMedia()
        scope.advanceUntilIdle()
        assertTrue(repo.conversation.value.messages.single().images.isEmpty())
        assertTrue(File(dir, "images").listFiles().orEmpty().isEmpty())
    }
    @Test fun `png attachment keeps its actual format on disk`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val dir = tempFolder.newFolder("png_format")
        val repo = PersistentConversationRepository(scope = scope, storageDirectory = dir, ioDispatcher = dispatcher)
        scope.advanceUntilIdle()
        val pngHeader = byteArrayOf(0x89.toByte(), 80, 78, 71, 13, 10, 26, 10)
        repo.addMessage(Message("png", MessageRole.USER, "image", images = listOf(
            java.util.Base64.getEncoder().encodeToString(pngHeader),
        )))
        scope.advanceUntilIdle()
        val image = repo.conversation.value.messages.single().images.single()
        assertTrue(image.endsWith(".png"))
        assertTrue(File(image.removePrefix("file://")).isFile)
    }
    @Test fun `indexed cold start loads only latest body and older pages remain accessible`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val dir = tempFolder.newFolder("indexed_pages")
        for (i in 1..120) File(dir, "conv_$i.json").writeText(
            """{"id":"conv_$i","title":"Task $i","updatedAt":$i,"messages":[{"id":"m$i","role":"user","content":"Goal $i"}]}""",
        )
        val initial = PersistentConversationRepository(scope = scope, storageDirectory = dir, ioDispatcher = dispatcher)
        scope.advanceUntilIdle()
        assertEquals(100, initial.historyList.value.size)
        assertTrue(initial.hasMoreHistory.value)
        assertTrue(initial.historyList.value.all { it.messages.isEmpty() && it.summary != null })
        val cached = PersistentConversationRepository(scope = scope, storageDirectory = dir, ioDispatcher = dispatcher)
        scope.advanceUntilIdle()
        assertEquals(1, cached.historyBodyReads.get())
        cached.loadMoreHistory()
        scope.advanceUntilIdle()
        assertEquals(120, cached.historyList.value.size)
        assertFalse(cached.hasMoreHistory.value)
        assertTrue(cached.loadConversation("conv_1"))
        assertEquals("Goal 1", cached.conversation.value.messages.single().content)
    }

    @Test fun `streaming updates are persisted in bounded batches and final content survives`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val dir = tempFolder.newFolder("batched_writes")
        val repo = PersistentConversationRepository(scope = scope, storageDirectory = dir, ioDispatcher = dispatcher)
        scope.advanceUntilIdle()
        val before = repo.conversationWrites.get()
        repo.addMessage(Message("stream", MessageRole.ASSISTANT, "", status = MessageStatus.STREAMING))
        repeat(100) {
            repo.appendToMessage("stream", "x")
            scope.advanceTimeBy(10)
            scope.runCurrent()
        }
        repo.updateMessage("stream") { it.copy(status = MessageStatus.COMPLETE) }
        scope.advanceUntilIdle()
        assertTrue(repo.conversationWrites.get() - before <= 6)
        assertTrue(File(dir, "${repo.conversation.value.id}.json").readText().contains("x".repeat(100)))
    }

    @Test fun `media cleanup updates references in active and archived conversations`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val dir = tempFolder.newFolder("media_references")
        val repo = PersistentConversationRepository(scope = scope, storageDirectory = dir, ioDispatcher = dispatcher)
        scope.advanceUntilIdle()
        val first = repo.conversation.value.id
        repo.addMessage(Message("image1", MessageRole.USER, "old", images = listOf("aGVsbG8=")))
        scope.advanceUntilIdle()
        repo.newConversation()
        repo.addMessage(Message("image2", MessageRole.USER, "new", images = listOf("aGVsbG8=")))
        scope.advanceUntilIdle()
        repo.clearAllMedia()
        scope.advanceUntilIdle()
        assertTrue(repo.conversation.value.messages.all { it.images.isEmpty() })
        assertTrue(repo.getConversation(first)!!.messages.all { it.images.isEmpty() })
        assertTrue(File(dir, "images").listFiles().orEmpty().isEmpty())
    }

    @Test fun `legacy timestamps are never reported as active execution duration`() {
        val stats = PersistentConversationRepository.computeStats(Conversation("old", createdAt = 1, updatedAt = 9_999_999))
        assertEquals(0L, stats.durationMs)
    }
    @Test
    fun `failed disk save is visible and can be retried without losing content`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val dir = tempFolder.newFolder("retry_save")
        val repo = PersistentConversationRepository(scope = scope, storageDirectory = dir, ioDispatcher = dispatcher)
        scope.advanceUntilIdle()
        val target = File(dir, "${repo.conversation.value.id}.json")
        assertTrue(target.delete())
        assertTrue(target.mkdir())
        val blocker = File(target, "blocker").apply { writeText("blocked") }
        repo.addMessage(Message(id = "m1", role = MessageRole.USER, content = "must survive retry"))
        scope.advanceUntilIdle()
        assertNotNull(repo.storageError.value)
        assertTrue(blocker.delete())
        assertTrue(target.delete())
        repo.retryFailedSaves()
        scope.advanceUntilIdle()
        assertEquals(null, repo.storageError.value)
        assertTrue(target.readText().contains("must survive retry"))
    }
    @Test
    fun `rapid conversation switches preserve every pending conversation`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val dir = tempFolder.newFolder("rapid_switch")
        val repo = PersistentConversationRepository(scope = scope, storageDirectory = dir, ioDispatcher = dispatcher)
        scope.advanceUntilIdle()
        val ids = (1..4).map {
            val id = repo.newConversation()
            assertEquals(id, repo.conversation.value.id)
            repo.addMessage(Message(id = "m$it", role = MessageRole.USER, content = "task-$it"))
            id
        }
        scope.advanceUntilIdle()
        ids.forEachIndexed { i, id -> assertTrue(File(dir, "$id.json").readText().contains("task-${i + 1}")) }
    }

    @Test
    fun `pending save cannot resurrect a deleted conversation`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val dir = tempFolder.newFolder("delete_pending")
        val repo = PersistentConversationRepository(scope = scope, storageDirectory = dir, ioDispatcher = dispatcher)
        scope.advanceUntilIdle()
        val id = repo.conversation.value.id
        repo.addMessage(Message(id = "m", role = MessageRole.USER, content = "delete me"))
        repo.deleteConversation(id)
        scope.advanceUntilIdle()
        assertFalse(File(dir, "$id.json").exists())
        assertFalse(repo.historyList.value.any { it.id == id })
    }

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
                    durationMs = 4000L,
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

    @Test
    fun `loadConversation restores targeted historical conversation into active state`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)
        val dir = tempFolder.newFolder("test_load_conv")

        val repo = PersistentConversationRepository(
            scope = testScope,
            storageDirectory = dir,
            ioDispatcher = testDispatcher,
        )
        testScope.advanceUntilIdle()

        val firstId = repo.conversation.value.id
        repo.addMessage(Message(id = "m1", role = MessageRole.USER, content = "第一条会话消息"))
        testScope.advanceUntilIdle()

        val secondId = repo.newConversation()
        testScope.advanceUntilIdle()
        repo.addMessage(Message(id = "m2", role = MessageRole.USER, content = "第二条会话消息"))
        testScope.advanceUntilIdle()

        assertEquals(secondId, repo.conversation.value.id)

        // Restore first conversation
        val restored = repo.loadConversation(firstId)
        assertTrue(restored)
        assertEquals(firstId, repo.conversation.value.id)
        assertEquals("第一条会话消息", repo.conversation.value.messages.first().content)

        // Non-existent id returns false and doesn't mutate current conversation
        val failed = repo.loadConversation("non-existent-id-12345")
        assertFalse(failed)
        assertEquals(firstId, repo.conversation.value.id)
    }

    @Test
    fun `cold start history loading is capped by MAX_HISTORY_ITEMS`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)
        val dir = tempFolder.newFolder("test_history_cap")

        // Pre-create 120 conversation JSON files on disk
        val now = System.currentTimeMillis()
        for (i in 1..120) {
            val convFile = File(dir, "conv_$i.json")
            convFile.writeText(
                """{"id":"conv_$i","title":"Task $i","createdAt":${now + i},"updatedAt":${now + i},"messages":[]}"""
            )
            convFile.setLastModified(now + i)
        }

        val repo = PersistentConversationRepository(
            scope = testScope,
            storageDirectory = dir,
            ioDispatcher = testDispatcher,
        )
        testScope.advanceUntilIdle()

        assertEquals(PersistentConversationRepository.MAX_HISTORY_ITEMS, repo.historyList.value.size)
        assertEquals(100, repo.historyList.value.size)
    }
}
