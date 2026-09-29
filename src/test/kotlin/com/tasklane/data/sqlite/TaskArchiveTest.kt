package com.tasklane.data.sqlite

import com.tasklane.data.sqlite.StoreFixture.REPO
import com.tasklane.domain.model.AttachmentId
import com.tasklane.domain.model.RepoKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** El `.zip` de intercambio: ida y vuelta con imágenes, y los archivos que no hay que fiarse. */
class TaskArchiveTest {

    private val sha = "a".repeat(64)
    private val other = "b".repeat(64)

    private fun <T> inTemp(block: (Path) -> T): T {
        val dir = Files.createTempDirectory("tasklane-archive")
        return try { block(dir) } finally { dir.toFile().deleteRecursively() }
    }

    private fun write(file: Path, missing: Boolean = false) {
        val tasks = listOf(
            StoreFixture.task("t-1", "con imagen ![](tasklane:$sha)", tags = listOf("a")),
            StoreFixture.task("t-2", "otra ![](tasklane:$other) y ![](tasklane:$sha)"),
        )
        Files.newOutputStream(file).use { out ->
            TaskArchive.Writer(out, "mi repo", "2.28.0").use { archive ->
                archive.tasks(REPO) { xml ->
                    archive.note(tasks)
                    xml.write(tasks)
                }
                archive.attachments { id ->
                    if (missing && id.value == other) null else ByteArrayInputStream("bytes-${id.value.take(1)}".toByteArray())
                }
                assertEquals(if (missing) listOf(AttachmentId(other)) else emptyList<AttachmentId>(), archive.missing)
            }
        }
    }

    @Test
    fun `ida y vuelta con tareas e imagenes`() = inTemp { dir ->
        val file = dir.resolve("a.zip")
        write(file)

        TaskArchive.Reader.open(file).use { reader ->
            assertEquals(2, reader.manifest.tasks)
            assertEquals(2, reader.manifest.attachments)
            assertEquals("mi repo", reader.manifest.repo)
            assertEquals(setOf(sha, other), reader.attachmentIds().map { it.value }.toSet())

            val xml = dir.resolve("tasks.xml")
            reader.extractTasks(xml)
            val back = ArrayList<String>()
            TasksXmlReader.read(xml, RepoKey("otro")) { chunk -> chunk.forEach { back += it.id.value + ":" + it.repo.value } }
            assertEquals(listOf("t-1:otro", "t-2:otro"), back)

            val image = dir.resolve("img")
            reader.extractAttachment(AttachmentId(sha), image)
            assertEquals("bytes-a", Files.readString(image))
        }
    }

    @Test
    fun `una imagen ausente se cuenta y no rompe`() = inTemp { dir ->
        val file = dir.resolve("a.zip")
        write(file, missing = true)
        TaskArchive.Reader.open(file).use { assertEquals(1, it.manifest.attachments) }
    }

    @Test
    fun `lo que no es un archivo de Tasklane se rechaza con su motivo`() = inTemp { dir ->
        fun reason(block: () -> Unit): TaskArchive.Invalid.Reason =
            try { block(); fail("tenía que fallar"); error("") } catch (e: TaskArchive.Invalid) { e.reason }

        val junk = dir.resolve("junk.zip").also { Files.write(it, ByteArray(500) { 7 }) }
        assertEquals(TaskArchive.Invalid.Reason.NOT_ZIP, reason { TaskArchive.Reader.open(junk) })

        val bare = dir.resolve("bare.zip")
        ZipOutputStream(Files.newOutputStream(bare)).use { it.putNextEntry(ZipEntry("x.txt")); it.closeEntry() }
        assertEquals(TaskArchive.Invalid.Reason.NO_MANIFEST, reason { TaskArchive.Reader.open(bare) })

        val noTasks = dir.resolve("nt.zip")
        ZipOutputStream(Files.newOutputStream(noTasks)).use {
            it.putNextEntry(ZipEntry(TaskArchive.MANIFEST)); it.write("format=1\n".toByteArray()); it.closeEntry()
        }
        assertEquals(TaskArchive.Invalid.Reason.NO_TASKS, reason { TaskArchive.Reader.open(noTasks) })

        val future = dir.resolve("f.zip")
        ZipOutputStream(Files.newOutputStream(future)).use {
            it.putNextEntry(ZipEntry(TaskArchive.MANIFEST)); it.write("format=99\n".toByteArray()); it.closeEntry()
            it.putNextEntry(ZipEntry(TaskArchive.TASKS)); it.closeEntry()
        }
        assertEquals(TaskArchive.Invalid.Reason.FUTURE_FORMAT, reason { TaskArchive.Reader.open(future) })
    }

    @Test
    fun `una entrada con ruta no es una imagen`() = inTemp { dir ->
        val file = dir.resolve("evil.zip")
        ZipOutputStream(Files.newOutputStream(file)).use {
            it.putNextEntry(ZipEntry(TaskArchive.MANIFEST)); it.write("format=1\n".toByteArray()); it.closeEntry()
            it.putNextEntry(ZipEntry(TaskArchive.TASKS)); it.closeEntry()
            it.putNextEntry(ZipEntry(TaskArchive.ATTACHMENTS + "../../etc/passwd")); it.write(1); it.closeEntry()
            it.putNextEntry(ZipEntry(TaskArchive.ATTACHMENTS + sha)); it.write(1); it.closeEntry()
        }
        TaskArchive.Reader.open(file).use { assertEquals(listOf(sha), it.attachmentIds().map { id -> id.value }) }
        assertFalse(TaskArchive.safeName("../x"))
        assertTrue(TaskArchive.safeName(sha))
    }
}
