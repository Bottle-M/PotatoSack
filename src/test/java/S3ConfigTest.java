import indi.somebottle.potatosack.utils.Config;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * S3 配置键与默认配置文件的纯单元测试
 * <p>
 * 沿用 {@code DropboxUtilsTest} 的测试风格：直接校验键名字符串与 resources/configs.yml 的内容。
 * </p>
 */
public class S3ConfigTest {
    @Test
    public void testS3ConfigKeys() {
        assertEquals("client.s3.endpoint", Config.KEYS.CLIENT.S3.ENDPOINT);
        assertEquals("client.s3.region", Config.KEYS.CLIENT.S3.REGION);
        assertEquals("client.s3.bucket", Config.KEYS.CLIENT.S3.BUCKET);
        assertEquals("client.s3.access-key", Config.KEYS.CLIENT.S3.ACCESS_KEY);
        assertEquals("client.s3.secret-key", Config.KEYS.CLIENT.S3.SECRET_KEY);
        assertEquals("client.s3.session-token", Config.KEYS.CLIENT.S3.SESSION_TOKEN);
        assertEquals("client.s3.path-style-access", Config.KEYS.CLIENT.S3.PATH_STYLE_ACCESS);
    }

    @Test
    public void testDefaultConfigContainsS3Section() throws Exception {
        String defaultConfig = Files.readString(Path.of("src/main/resources/configs.yml"), StandardCharsets.UTF_8);
        assertTrue("configs.yml 应包含 s3 节点", defaultConfig.contains("    s3:"));
        assertTrue(defaultConfig.contains("endpoint:"));
        assertTrue(defaultConfig.contains("region:"));
        assertTrue(defaultConfig.contains("bucket:"));
        assertTrue(defaultConfig.contains("access-key:"));
        assertTrue(defaultConfig.contains("secret-key:"));
        assertTrue(defaultConfig.contains("session-token:"));
        assertTrue(defaultConfig.contains("path-style-access:"));
        // 默认 region 为 us-east-1
        assertTrue(defaultConfig.contains("region: \"us-east-1\""));
        // 默认 path-style 为 false
        assertTrue(defaultConfig.contains("path-style-access: false"));
    }

    @Test
    public void testDefaultConfigKeepsOneDriveAsDefaultProvider() throws Exception {
        String defaultConfig = Files.readString(Path.of("src/main/resources/configs.yml"), StandardCharsets.UTF_8);
        // 升级不应改变已有用户的行为，默认 provider 仍然是 onedrive
        assertTrue(defaultConfig.contains("use: onedrive"));
        assertFalse(defaultConfig.contains("use: s3"));
    }

    @Test
    public void testDefaultConfigDocumentsS3Alternatives() throws Exception {
        String defaultConfig = Files.readString(Path.of("src/main/resources/configs.yml"), StandardCharsets.UTF_8);
        // 注释中应说明 endpoint 留空代表 AWS S3，以及自定义 endpoint 的用法
        assertTrue(defaultConfig.contains("AWS S3 endpoint"));
        assertTrue(defaultConfig.contains("MinIO"));
        assertTrue(defaultConfig.contains("path-style"));
        // 不应在默认配置中放入任何真实凭证
        assertFalse(defaultConfig.contains("AKIA"));
    }

    @Test
    public void testDefaultConfigDoesNotShipCredentials() throws Exception {
        String defaultConfig = Files.readString(Path.of("src/main/resources/configs.yml"), StandardCharsets.UTF_8);
        // access-key / secret-key / session-token 的默认值必须是空字符串
        assertTrue(defaultConfig.contains("access-key: \"\""));
        assertTrue(defaultConfig.contains("secret-key: \"\""));
        assertTrue(defaultConfig.contains("session-token: \"\""));
        assertTrue(defaultConfig.contains("bucket: \"\""));
        assertTrue(defaultConfig.contains("endpoint: \"\""));
    }

    @Test
    public void testUseCommentListsAllSupportedProviders() throws Exception {
        String defaultConfig = Files.readString(Path.of("src/main/resources/configs.yml"), StandardCharsets.UTF_8);
        assertTrue(defaultConfig.contains("onedrive / dropbox / s3"));
    }
}
