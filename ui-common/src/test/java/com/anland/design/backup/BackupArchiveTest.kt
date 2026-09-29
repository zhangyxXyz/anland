package com.anland.design.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BackupArchiveTest {
    @Test fun `serialized contents and intermediate files round trip`() {
        val root = createTempDirectory("backup-test-").toFile()
        val intermediate = File(root, "source.bin").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val zip = File(root, "backup.zip")

        BackupArchive.pack(listOf(
            BackupInput.Serialized("data/settings.json", "{\"ok\":true}"),
            BackupInput.IntermediateFile("files/payload.bin", intermediate),
        ), zip)
        val restored = BackupArchive.unpack(zip, File(root, "restored"))

        assertEquals("{\"ok\":true}", restored.text("data/settings.json"))
        assertTrue(restored.file("files/payload.bin")!!.readBytes().contentEquals(byteArrayOf(1, 2, 3)))
        root.deleteRecursively()
    }

    @Test fun `AES encrypted backup round trips and rejects wrong password`() {
        val root = createTempDirectory("backup-encrypted-").toFile()
        val zip = File(root, "backup.zip")
        BackupArchive.pack(listOf(BackupInput.Serialized("secret.txt", "private")), zip, "correct horse")

        assertEquals("private", BackupArchive.unpack(zip, File(root, "restored"), "correct horse").text("secret.txt"))
        try {
            BackupArchive.unpack(zip, File(root, "wrong"), "wrong password")
            fail("Wrong password should not decrypt the backup")
        } catch (_: Exception) {
            // Expected: Zip4j rejects the AES password while extracting.
        } finally {
            root.deleteRecursively()
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `unsafe zip entry is rejected`() {
        val root = createTempDirectory("backup-slip-").toFile()
        val zip = File(root, "bad.zip")
        ZipOutputStream(zip.outputStream()).use {
            it.putNextEntry(ZipEntry("../outside.txt")); it.write(byteArrayOf(1)); it.closeEntry()
        }
        try { BackupArchive.unpack(zip, File(root, "restored")) } finally { root.deleteRecursively() }
    }
}
