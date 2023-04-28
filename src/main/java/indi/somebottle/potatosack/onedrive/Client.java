package indi.somebottle.potatosack.onedrive;

import com.azure.identity.ClientSecretCredential;
import com.azure.identity.ClientSecretCredentialBuilder;
import com.microsoft.graph.authentication.TokenCredentialAuthProvider;
import com.microsoft.graph.models.User;
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
        User usr = graphClient.users().byId("***REMOVED***").buildRequest().get();
        if (usr != null) {
            System.out.println("User: " + usr.id);
        }
    }
}
