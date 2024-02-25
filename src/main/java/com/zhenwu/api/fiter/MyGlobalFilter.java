package com.zhenwu.api.fiter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;

import java.net.URI;

/**
 * @author zhenwu
 */
@Slf4j
//@Component
public class MyGlobalFilter implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        log.error("MyGlobalFilter is execute!");
        ServerHttpRequest request = exchange.getRequest();
        log.error("uri = {}", request.getURI());
        log.error("[request]queryParams = {}", request.getQueryParams());
        ServerHttpRequest newRequest = new ServerHttpRequestDecorator(request) {

            @Override
            public URI getURI() {
                UriComponentsBuilder uriComponentsBuilder = UriComponentsBuilder.fromUri(super.getURI());
                uriComponentsBuilder.queryParam("userId", "2");
                return uriComponentsBuilder.build().toUri();
            }
            @Override
            public String getMethodValue() {
                return "GET";
            }
        };

        MultiValueMap<String, String> queryParams = newRequest.getQueryParams();
        log.error("[newRequest]queryParams = {}", queryParams);

        ServerWebExchange mutatedExchange = exchange.mutate()
                .request(newRequest)
                .build();
        return chain.filter(mutatedExchange);
    }

    @Override
    public int getOrder() {
        return -2;
    }
}