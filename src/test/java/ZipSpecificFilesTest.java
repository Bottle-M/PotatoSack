import indi.somebottle.potatosack.tasks.entities.ZipEntryInfo;
import indi.somebottle.potatosack.utils.Utils;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;

import static org.junit.Assert.assertFalse;

/**
 * 扫描后文件消失时，不能生成一份声称成功却漏掉该条目的备份。
 */
public class ZipSpecificFilesTest {
    @Test
    public void missingFileAfterScanFailsEntireArchive() throws Exception {
        File directory = Files.createTempDirectory("potatosack-zip-test").toFile();
        directory.deleteOnExit();
        File source = new File(directory, "deleted-after-scan.dat");
        File zip = new File(directory, "backup.zip");
        assertFalse(Utils.zipSpecificFiles(
                new ZipEntryInfo[]{new ZipEntryInfo(source.getAbsolutePath(), "world/deleted-after-scan.dat")},
                zip.getAbsolutePath(), true));
    }
}
