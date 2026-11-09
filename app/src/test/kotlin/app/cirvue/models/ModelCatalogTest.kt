package app.cirvue.models

import java.net.URI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCatalogTest {
    @Test fun everyFileHasIntegrityMetadataAndPinnedHttpsUrl() {
        val sha256 = Regex("[0-9a-f]{64}")
        val pinnedPath = Regex("/[^/]+/[^/]+/resolve/[0-9a-f]{40}/.+")
        assertTrue(ModelPack.entries.isNotEmpty())
        for (pack in ModelPack.entries) {
            assertTrue(pack.name, pack.files.isNotEmpty())
            assertEquals(pack.name, pack.files.size, pack.files.map { it.name }.distinct().size)
            for (file in pack.files) {
                val label = "${pack.name}/${file.name}"
                assertTrue(label, sha256.matches(file.sha256))
                assertTrue(label, file.size > 0)
                val uri = URI(file.url)
                assertEquals(label, "https", uri.scheme)
                assertEquals(label, "huggingface.co", uri.host)
                assertTrue(label, pinnedPath.matches(uri.path))
                assertFalse(label, file.url.contains("/main/"))
            }
        }
    }
}
