package indi.somebottle.potatosack.utils;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;

public class ConfigOpts {
    /**
     * 用于测试，获取一个临时Graph Token
     *
     * @return [0] client_id, [1] client_secret, [2] refresh_token
     */
    public static String[] getTestTokens() {
        final String secretFile = "C:\\Users\\58379\\Desktop\\PotatoSack\\secret.txt";
        // 从文件读入信息，第一行是client_id，第二行是client_secret，第三行是refresh_token
        String[] tokens = new String[3];
        try {
            BufferedReader br = new BufferedReader(new FileReader(secretFile));
            tokens[0] = br.readLine();
            tokens[1] = br.readLine();
            tokens[2] = br.readLine();
            br.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return tokens;
    }
}
