import indi.somebottle.potatosack.clients.dropbox.utils.DropboxErrorUtils;
import indi.somebottle.potatosack.clients.dropbox.utils.DropboxPathUtils;
import indi.somebottle.potatosack.utils.Config;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class DropboxUtilsTest {
    @Test
    public void testDropboxPathNormalization() {
        assertEquals("", DropboxPathUtils.toDropboxPath(""));
        assertEquals("", DropboxPathUtils.toDropboxPath("/"));
        assertEquals("/PotatoSack/full.zip", DropboxPathUtils.toDropboxPath("PotatoSack/full.zip"));
        assertEquals("/PotatoSack/full.zip", DropboxPathUtils.toDropboxPath("/PotatoSack/full.zip/"));
        assertEquals("/my/backups/PotatoSack", DropboxPathUtils.toDropboxPath("\\my\\backups\\PotatoSack\\"));
    }

    @Test
    public void testDirectIncorrectOffsetParsing() {
        String body = "{\"error_summary\":\"incorrect_offset/..\",\"error\":{\".tag\":\"incorrect_offset\",\"correct_offset\":12345}}";
        assertEquals(Long.valueOf(12345), DropboxErrorUtils.getCorrectOffset(body));
    }

    @Test
    public void testNestedIncorrectOffsetParsing() {
        String body = "{\"error_summary\":\"lookup_failed/incorrect_offset/..\",\"error\":{\".tag\":\"lookup_failed\",\"lookup_failed\":{\".tag\":\"incorrect_offset\",\"correct_offset\":67890}}}";
        assertEquals(Long.valueOf(67890), DropboxErrorUtils.getCorrectOffset(body));
    }

    @Test
    public void testDropboxConfigKeysAndDefaults() throws Exception {
        assertEquals("client.dropbox.app-key", Config.KEYS.CLIENT.DROPBOX.APP_KEY);
        assertEquals("client.dropbox.app-secret", Config.KEYS.CLIENT.DROPBOX.APP_SECRET);
        assertEquals("client.dropbox.refresh-token", Config.KEYS.CLIENT.DROPBOX.REFRESH_TOKEN);

        String defaultConfig = Files.readString(Path.of("src/main/resources/configs.yml"), StandardCharsets.UTF_8);
        assertTrue(defaultConfig.contains("dropbox:"));
        assertTrue(defaultConfig.contains("app-key:"));
        assertTrue(defaultConfig.contains("app-secret:"));
        assertTrue(defaultConfig.contains("refresh-token:"));
    }
}
