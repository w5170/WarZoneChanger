package com.github.megatronking.netbare.gateway;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * A virtual gateway interceptor.
 */
public interface Interceptor<Req extends Request, ReqChain extends AbstractRequestChain<Req, ?>,
        Res extends Response, ResChain extends AbstractResponseChain<Res, ?>> {

    void intercept(@NonNull ReqChain chain, @NonNull ByteBuffer buffer) throws IOException;

    void intercept(@NonNull ResChain chain, @NonNull ByteBuffer buffer) throws IOException;

    default void onRequestFinished(@NonNull Req request) {}

    default void onResponseFinished(@NonNull Res response) {}
}
