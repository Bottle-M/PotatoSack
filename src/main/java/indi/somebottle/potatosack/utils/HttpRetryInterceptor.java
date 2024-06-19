package indi.somebottle.potatosack.utils;

import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;

/**
 * 自定义okhttp3拦截器，用于指定每个请求在网络原因下的重试次数
 */
public class HttpRetryInterceptor implements Interceptor {
    // 网络原因下的最大重试次数
    private static final int MAX_RETRY_COUNT = 3;

    @NotNull
    @Override
    public Response intercept(@NotNull Interceptor.Chain chain) throws IOException {
        Request request = chain.request();
        Response response = null;
        IOException exception = null;
        // 网络原因下的重试
        // 20240611 终止条件改为 <=
        for (int i = 0; i <= MAX_RETRY_COUNT; i++) {
            try {
                response = chain.proceed(request);
                // 20240611 如果请求成功了就清除异常
                exception = null;
                break;
            } catch (IOException e) {
                // 因为网络问题(比如Connect timeout)而导致的请求失败，重试
                exception = e;
                if (i != MAX_RETRY_COUNT) {
                    System.out.println("Retrying to request due to network issues...(" + (i + 1) + "/" + MAX_RETRY_COUNT + ")");
                    try {
                        // 等待 5 秒后重试
                        Thread.sleep(5000);
                    } catch (InterruptedException e1) {
                        e1.printStackTrace();
                    }
                }
            }
        }
        // 网络原因下的重试结束，但没有请求成功，则抛出异常
        if (exception != null) {
            throw exception;
        }
        // 请求成功
        return response;
    }
}
