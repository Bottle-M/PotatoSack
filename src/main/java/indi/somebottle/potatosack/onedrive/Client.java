package indi.somebottle.potatosack.onedrive;

import com.azure.identity.ClientSecretCredential;
import com.azure.identity.ClientSecretCredentialBuilder;
import com.microsoft.graph.authentication.TokenCredentialAuthProvider;
import com.microsoft.graph.models.Drive;
import com.microsoft.graph.models.DriveItem;
import com.microsoft.graph.models.User;
import com.microsoft.graph.requests.DriveCollectionPage;
import com.microsoft.graph.requests.DriveItemCollectionPage;
import com.microsoft.graph.requests.GraphServiceClient;
import com.microsoft.graph.requests.UserCollectionPage;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;

public class Client {
    // 验证凭据
    private final ClientSecretCredential clientSecretCredential;
    private final TokenCredentialAuthProvider authProvider;
    private final GraphServiceClient<?> graphClient;

    public Client(String clientId, String clientSecret, String tenantId) throws ExecutionException, InterruptedException {
        List<String> scopes = new ArrayList<>(Arrays.asList("https://graph.microsoft.com/.default"));
        clientSecretCredential = new ClientSecretCredentialBuilder()
                .clientId(clientId)
                .clientSecret(clientSecret)
                .tenantId(tenantId)
                .build();
        authProvider = new TokenCredentialAuthProvider(scopes, clientSecretCredential);
        graphClient = GraphServiceClient
                .builder()
                .authenticationProvider(authProvider)
                .buildClient();
        DriveItemCollectionPage dr = graphClient.users().byId("").drive().root().children().buildRequest().get();
        List<DriveItem> drItems=dr.getCurrentPage();
        for(DriveItem it:drItems) {
            System.out.println(it.name);
        }
    }
}
