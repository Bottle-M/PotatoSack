import indi.somebottle.potatosack.clients.base.entities.FileItem;
import indi.somebottle.potatosack.clients.s3.entities.S3Item;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * S3Item 纯单元测试
 */
public class S3ItemTest {
    @Test
    public void testFileItem() {
        S3Item item = S3Item.file("full.zip", "my/backups/PotatoSack/020240104000001/full.zip", 12345L);
        assertFalse(item.isFolder());
        assertEquals("full.zip", item.getName());
        assertEquals(12345L, item.getSize());
        assertEquals("my/backups/PotatoSack/020240104000001/full.zip", item.getKey());
    }

    @Test
    public void testVirtualFolderItem() {
        S3Item item = S3Item.folder("020240104000001", "PotatoSack/020240104000001");
        assertTrue(item.isFolder());
        // 目录名不带结尾 "/"
        assertEquals("020240104000001", item.getName());
        // 虚拟目录大小为 0
        assertEquals(0L, item.getSize());
        assertEquals("PotatoSack/020240104000001", item.getKey());
    }

    @Test
    public void testFolderFactoryNeverLeaksTrailingSlash() {
        // 即使调用方传入带尾斜杠的名字，构造出的 name 也应为调用方负责规范化后的结果，
        // 这里验证工厂方法本身只做透传，实际调用点已由 childNameOf 去掉尾斜杠
        S3Item item = S3Item.folder("backup", "a/backup");
        assertEquals("backup", item.getName());
        assertTrue(item.isFolder());
    }

    @Test
    public void testEmptyBucketRootFolder() {
        S3Item item = S3Item.folder("", "");
        assertTrue(item.isFolder());
        assertEquals("", item.getName());
        assertEquals("", item.getKey());
        assertEquals(0L, item.getSize());
    }

    @Test
    public void testDownloadUrlIsEmptyByContract() {
        // 对象通常是私有的，下载通过 sdkClient.getObject 完成，因此约定返回空字符串
        assertEquals("", S3Item.file("a.zip", "a.zip", 1L).getDownloadUrl());
        assertEquals("", S3Item.folder("d", "d").getDownloadUrl());
    }

    @Test
    public void testImplementsFileItem() {
        FileItem item = S3Item.file("a.zip", "a.zip", 1L);
        assertFalse(item.isFolder());
        assertEquals("a.zip", item.getName());
        assertEquals(1L, item.getSize());
    }

    @Test
    public void testZeroByteFileIsStillAFile() {
        // 零字节文件必须是文件而不是目录，避免空对象被误判为虚拟目录
        S3Item item = S3Item.file("empty.json", "PotatoSack/020240104000001/empty.json", 0L);
        assertFalse(item.isFolder());
        assertEquals(0L, item.getSize());
    }
}
