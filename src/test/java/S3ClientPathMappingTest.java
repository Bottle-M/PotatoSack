import indi.somebottle.potatosack.clients.s3.S3Client;
import org.junit.Test;

import java.lang.reflect.Method;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * S3 list response 到 FileItem basename 的纯逻辑回归测试。
 */
public class S3ClientPathMappingTest {
    @Test
    public void commonPrefixIsMappedToFolderName() throws Exception {
        Method method = S3Client.class.getDeclaredMethod(
                "commonPrefixChildNameOf", String.class, String.class);
        method.setAccessible(true);

        assertEquals("020240104000001",
                method.invoke(null, "PotatoSack/020240104000001/", "PotatoSack/"));
        assertEquals("nested",
                method.invoke(null, "PotatoSack/backup/nested/", "PotatoSack/backup/"));
        assertNull(method.invoke(null, "PotatoSack/backup/nested", "PotatoSack/backup/"));
        assertNull(method.invoke(null, "other/dir/", "PotatoSack/"));
    }

    @Test
    public void objectDirectoryMarkerIsStillFiltered() throws Exception {
        Method method = S3Client.class.getDeclaredMethod(
                "objectChildNameOf", String.class, String.class);
        method.setAccessible(true);

        assertEquals("backup.json",
                method.invoke(null, "PotatoSack/backup.json", "PotatoSack/"));
        assertNull(method.invoke(null, "PotatoSack/backup/", "PotatoSack/"));
        assertNull(method.invoke(null, "PotatoSack/backup/nested/file.bin", "PotatoSack/"));
    }
}
