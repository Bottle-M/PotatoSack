import indi.somebottle.potatosack.clients.s3.utils.S3PathUtils;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * S3PathUtils 纯单元测试（无网络、无 Bukkit 环境）
 */
public class S3PathUtilsTest {
    @Test
    public void testEmptyAndRootPaths() {
        assertEquals("", S3PathUtils.normalize(""));
        assertEquals("", S3PathUtils.normalize("/"));
        assertEquals("", S3PathUtils.normalize("//"));
        assertEquals("", S3PathUtils.normalize("   "));
        assertEquals("", S3PathUtils.normalize(null));
        assertEquals("", S3PathUtils.normalize("\\"));
    }

    @Test
    public void testBackslashConversion() {
        assertEquals("my/backups/PotatoSack", S3PathUtils.normalize("\\my\\backups\\PotatoSack\\"));
        assertEquals("my/backups", S3PathUtils.normalize("my\\backups"));
    }

    @Test
    public void testDuplicateAndBoundarySlashes() {
        assertEquals("a/b", S3PathUtils.normalize("//a//b//"));
        assertEquals("a/b/c", S3PathUtils.normalize("a/b/c"));
        assertEquals("a/b", S3PathUtils.normalize("/a/b/"));
        // 反斜杠与正斜杠混用后产生的重复分隔符也要合并
        assertEquals("a/b/c", S3PathUtils.normalize("a\\\\b//c"));
    }

    @Test
    public void testBaseDirCombination() {
        // Client.buildFullPath 会拼出 "base-dir/PotatoSack/full.zip"，规范化的 key 里
        // base-dir 必须原样保留，且不会因为 base-dir 的尾斜杠产生双斜杠
        assertEquals("my/backups/PotatoSack/full.zip", S3PathUtils.normalize("/my/backups//PotatoSack/full.zip"));
        assertEquals("my/backups/PotatoSack/full.zip",
                S3PathUtils.normalize("my/backups/" + "PotatoSack/full.zip"));
        // base-dir 为空时，key 直接从 PotatoSack 开始，不能有开头斜杠
        assertEquals("PotatoSack/020240104000001/full.zip",
                S3PathUtils.normalize("PotatoSack/020240104000001/full.zip"));
        assertEquals("PotatoSack/020240104000001/full.zip",
                S3PathUtils.normalize("/PotatoSack/020240104000001/full.zip"));
    }

    @Test
    public void testToDirPrefix() {
        assertEquals("", S3PathUtils.toDirPrefix(""));
        assertEquals("", S3PathUtils.toDirPrefix(null));
        assertEquals("a/b/", S3PathUtils.toDirPrefix("a/b"));
        // prefix 尾 "/" 只添加一次
        assertEquals("a/b/", S3PathUtils.toDirPrefix(S3PathUtils.normalize("a/b/")));
    }

    @Test
    public void testRequireNonRoot() {
        assertEquals("a/b", S3PathUtils.requireNonRoot("/a/b/"));
        assertEquals("a", S3PathUtils.requireNonRoot("a"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testRequireNonRootRejectsEmpty() {
        S3PathUtils.requireNonRoot("/");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testDotDotIsRejected() {
        S3PathUtils.normalize("../other-bucket-key");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testNestedDotDotIsRejected() {
        S3PathUtils.normalize("backups/../../etc/passwd");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testWindowsStyleDotDotIsRejected() {
        S3PathUtils.normalize("backups\\..\\..\\secret");
    }

    @Test
    public void testSingleDotIsAllowed() {
        // 单个 "." 只是一个普通 key 段，不属于穿越语义
        assertEquals("a/./b", S3PathUtils.normalize("a/./b"));
    }
}
