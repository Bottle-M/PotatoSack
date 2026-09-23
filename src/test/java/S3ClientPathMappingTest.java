import indi.somebottle.potatosack.clients.s3.S3Client;
import org.junit.Test;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.lang.reflect.Method;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

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
    public void objectDirectChildIsMappedWithoutMarkerClassification() throws Exception {
        Method method = S3Client.class.getDeclaredMethod(
                "objectChildNameOf", String.class, String.class);
        method.setAccessible(true);

        assertEquals("backup.json",
                method.invoke(null, "PotatoSack/backup.json", "PotatoSack/"));
        // prefix 自身不是目录列表中的文件项。
        assertNull(method.invoke(null, "PotatoSack/", "PotatoSack/"));
        // delimiter 负责把含后续斜杠的 key 汇总到 CommonPrefixes；这里仅作防御性过滤。
        assertNull(method.invoke(null, "PotatoSack/backup/", "PotatoSack/"));
        assertNull(method.invoke(null, "PotatoSack/backup/nested/file.bin", "PotatoSack/"));
        assertNull(method.invoke(null, "other/file.bin", "PotatoSack/"));
    }
    @Test
    public void noSuchBucketIsNeverTreatedAsMissingObject() throws Exception {
        Method method = S3Client.class.getDeclaredMethod("isObjectNotFound", S3Exception.class);
        method.setAccessible(true);

        NoSuchBucketException noSuchBucket = NoSuchBucketException.builder()
                .statusCode(404)
                .build();
        assertFalse((Boolean) method.invoke(null, noSuchBucket));

        NoSuchKeyException objectNotFound = NoSuchKeyException.builder()
                .statusCode(404)
                .build();
        assertTrue((Boolean) method.invoke(null, objectNotFound));
    }

}
