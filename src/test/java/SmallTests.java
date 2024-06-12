import org.junit.Test;

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
}
