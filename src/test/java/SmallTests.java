import org.junit.Test;

import java.io.File;
import java.nio.file.Path;

public class SmallTests {
    @Test
    public void increTest(){
        byte[] buffer=new byte[10];
        int writePos=10;
        try {
            buffer[writePos++] = 'a';
        }catch (Exception e){
            System.out.println(e);
        }
        System.out.println(writePos);
    }

    @Test
    public void pathRelativizeTest(){
        File root=new File("/opt/server");
        File file=new File("/opt/server/world/regions/test.rca");
        Path serverRootPath=Path.of(root.toURI());
        Path serverFilePath=Path.of(file.toURI());
        String relativePathStr=serverRootPath.relativize(serverFilePath).toString();
        System.out.println(relativePathStr.replace('\\','/'));
    }

    @Test
    public void pathResolveTest(){
        File root=new File("/opt/server");
        File absoluteFile=new File(root,"world/regions/test.rca");
        System.out.println(absoluteFile.getPath());
    }
}
