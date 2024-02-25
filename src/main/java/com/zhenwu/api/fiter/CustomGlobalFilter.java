package com.zhenwu.api.fiter;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.zhenwu.api.model.RequestData;
import com.zhenwu.api.model.RequestInfo;
import com.zhenwu.common.entity.InnerApiTransferInterfaceInfo;
import com.zhenwu.common.entity.InnerApiTransferUser;
import com.zhenwu.common.service.InnerApiInterfaceInfoService;
import com.zhenwu.common.service.InnerApiUserInterfaceInfoService;
import com.zhenwu.common.service.InnerApiUserService;
import com.zhenwu.common.util.CommonUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.reactivestreams.Publisher;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.handler.predicate.PredicateDefinition;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.ORIGINAL_RESPONSE_CONTENT_TYPE_ATTR;

/**
 * @author zhenwu
 */
@Slf4j
@Component
public class CustomGlobalFilter implements GlobalFilter, Ordered {

    @DubboReference
    private InnerApiUserService innerApiUserService;

    @DubboReference
    private InnerApiInterfaceInfoService innerApiInterfaceInfoService;

    @DubboReference
    private InnerApiUserInterfaceInfoService innerApiUserInterfaceInfoService;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {

        log.info("[1]attributes = {}", exchange.getAttributes());
        Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        if (route != null) {
            log.info("The gatewayRoute of class: {}", route.getClass().getName());
        }

        // 获取请求信息，并记录请求日志
        Map<String, Object> attributes = exchange.getAttributes();
        String requestBody = (String) attributes.get("requestBody");
        attributes.remove("requestBody");
        log.info("requestBody = {}", requestBody);
        RequestInfo requestInfo = this.verifyAndExtractInfo(requestBody);
        if (requestInfo == null) {
            return Mono.empty();
        }
        log.info("extract data = {}", requestInfo);

        ServerHttpRequest request = exchange.getRequest();
        ServerHttpResponse response = exchange.getResponse();
        InnerApiTransferInterfaceInfo innerApiTransferInterfaceInfo = requestInfo.getInnerApiTransferInterfaceInfo();
        InnerApiTransferUser innerApiTransferUser = requestInfo.getInnerApiTransferUser();
        // 查询用户调用次数是否充足
        int leftInvokeNum = this.innerApiUserInterfaceInfoService.queryLeftInvokeNum(innerApiTransferInterfaceInfo.getId(), innerApiTransferUser.getId());
        if (leftInvokeNum < 1) {
            response.setStatusCode(HttpStatus.OK);
            response.getHeaders().add("Content-Type", "application/json");
            String errorMessage = "{\"error\": \"您的调用次数已经用尽!\"}";
            return response.writeWith(Mono.just(response.bufferFactory().wrap(errorMessage.getBytes())));
        }

        ServerHttpRequest newRequest = handleRequest(request, innerApiTransferInterfaceInfo, requestInfo.getRequestData().getInterfaceData());
        // 获取响应对象
        DataBufferFactory bufferFactory = response.bufferFactory();
        ServerHttpResponseDecorator responseDecorator = new ServerHttpResponseDecorator(response) {
            @Override
            public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
                HttpStatus statusCode = getStatusCode();
                log.info("statusCode = {}", statusCode);
                if (HttpStatus.OK.equals(statusCode) && body instanceof Flux) {

                    // 响应成功，减少调用次数
                    boolean result = innerApiUserInterfaceInfoService.invokeCount(innerApiTransferInterfaceInfo.getId(), innerApiTransferUser.getId());
                    log.info("result = {}", result);

                    // 获取响应 ContentType
                    String responseContentType = exchange.getAttribute(ORIGINAL_RESPONSE_CONTENT_TYPE_ATTR);
                    log.info("responseContentType= {}", responseContentType);
                    // 记录 JSON 格式数据的响应体
                    if (!StringUtils.isEmpty(responseContentType) && responseContentType.contains(MediaType.APPLICATION_JSON_VALUE)) {
                        Flux<? extends DataBuffer> fluxBody = Flux.from(body);
                        // 解决返回体分段传输
                        return super.writeWith(fluxBody.buffer().map(dataBuffers -> {
                            List<String> list = new ArrayList<>();
                            dataBuffers.forEach(dataBuffer -> {
                                byte[] content = new byte[dataBuffer.readableByteCount()];
                                dataBuffer.read(content);
                                DataBufferUtils.release(dataBuffer);
                                list.add(new String(content, StandardCharsets.UTF_8));
                            });
                            log.info("响应数据:{}", list);
                            String responseData = "{\"name\":\"jack\"}";
                            log.info("<------------------------ RESPONSE RESULT = [{}]", responseData.replaceAll("\n","").replaceAll("\t",""));
                            return bufferFactory.wrap(responseData.getBytes());
                        }));
                    }
                }
                log.info("body = {}", body);
                return super.writeWith(body);
            }
        };

        ServerWebExchange mutatedExchange = exchange.mutate()
                .request(newRequest)
                .response(responseDecorator)
                .build();

        log.info("[2]attributes = {}", mutatedExchange.getAttributes());
        return chain.filter(mutatedExchange);
    }

    @Override
    public int getOrder() {
        // 必须要小于 -1
        return -2;
    }

    private ServerHttpRequest handleRequest(ServerHttpRequest request, InnerApiTransferInterfaceInfo innerApiTransferInterfaceInfo, String interfaceData) {
        String interfaceRequestMethod = innerApiTransferInterfaceInfo.getInterfaceRequestMethod();
        ServerHttpRequest newRequest = null;
        switch (interfaceRequestMethod) {
            case "POST":
                newRequest = new ServerHttpRequestDecorator(request) {
                    private final String method = innerApiTransferInterfaceInfo.getInterfaceRequestMethod();

                    @Override
                    public Flux<DataBuffer> getBody() {
                        log.info("interfaceData = {}", interfaceData);
                        DataBuffer buffer = new DefaultDataBufferFactory().wrap(interfaceData.getBytes(StandardCharsets.UTF_8));
                        return Flux.just(buffer);
                    }

                    @Override
                    public String getMethodValue() {
                        log.info("method = {}", method);
                        return method;
                    }
                };
                break;
            case "GET":
                newRequest = new ServerHttpRequestDecorator(request) {
                    private final String method = innerApiTransferInterfaceInfo.getInterfaceRequestMethod();

                    @Override
                    public URI getURI() {
                        UriComponentsBuilder uriComponentsBuilder = UriComponentsBuilder.fromUri(super.getURI());
                        String queryParams = getQueryParamsString();
                        log.info("queryParams = {}", queryParams);
                        if (queryParams == null) {
                            return super.getURI();
                        }
                        return uriComponentsBuilder.replaceQuery(queryParams).build().toUri();
                    }

                    @Override
                    public String getMethodValue() {
                        log.info("method = {}", method);
                        return method;
                    }

                    private String getQueryParamsString() {
                        Map<String, Object> map = JSONUtil.toBean(interfaceData, Map.class);
                        if (map.isEmpty()) {
                            return null;
                        }
                        StringBuilder queryParams = new StringBuilder();
                        for (Map.Entry<String, Object> entry : map.entrySet()) {
                            queryParams.append(entry.getKey()).append("=").append(entry.getValue()).append("&");
                        }
                        queryParams.deleteCharAt(queryParams.length() - 1);
                        return queryParams.toString();
                    }
                };
                break;
        }
        return newRequest;
    }

    private RequestInfo verifyAndExtractInfo(String requestBody) {
        JSONObject jsonObject = JSONUtil.parseObj(requestBody);
        String accessKey = (String) jsonObject.get("accessKey");
        String data = (String) jsonObject.get("data");

        if (StrUtil.isEmpty(accessKey) || StrUtil.isEmpty(data)) {
            // 上送数据错误
            return null;
        }

        // 查询用户信息
        InnerApiTransferUser innerApiTransferUser = this.innerApiUserService.getUserInfo(accessKey);
        if (innerApiTransferUser == null) {
            // 未查询到用户信息
            log.error("未查询到{}的用户信息", accessKey);
            return null;
        }

        String decryptData = CommonUtils.decrypt(data, innerApiTransferUser.getUserPrivatekey());
        // TODO 数据解析失败
        RequestData requestData = JSONUtil.toBean(decryptData, RequestData.class);

        // 验证签名
        String userSign = requestData.getSign();
        String interfaceToken = requestData.getInterfaceToken();
        String sign = requestData.getInterfaceData() == null ?
                CommonUtils.sign(innerApiTransferUser.getUserAccesskey() + interfaceToken + innerApiTransferUser.getUserSecretkey())
                : CommonUtils.sign(requestData.getInterfaceData() + innerApiTransferUser.getUserSecretkey());
        if (!sign.equals(userSign)) {
            log.error("用户{}的签名认证失败", accessKey);
            return null;
        }

        // 查询接口信息
        InnerApiTransferInterfaceInfo innerApiTransferInterfaceInfo = this.innerApiInterfaceInfoService.getInterfaceInfo(interfaceToken);
        if (innerApiTransferInterfaceInfo == null) {
            log.error("未查询到{}的用户访问的接口{}信息", accessKey, interfaceToken);
            return null;
        }

        // 判读接口状态
        if (0 == innerApiTransferInterfaceInfo.getInterfaceStatus() || 0 == innerApiTransferInterfaceInfo.getInterfaceDelete()) {
            log.error("用户{}访问的接口{}已下线或已删除", accessKey, interfaceToken);
            return null;
        }

        RequestInfo requestInfo = new RequestInfo();
        requestInfo.setInnerApiTransferUser(innerApiTransferUser);
        requestInfo.setInnerApiTransferInterfaceInfo(innerApiTransferInterfaceInfo);
        requestInfo.setAccessKey(accessKey);
        requestInfo.setRequestData(requestData);
        return requestInfo;
    }
}
