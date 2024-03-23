package com.zhenwu.api.fiter;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.zhenwu.api.model.RequestData;
import com.zhenwu.api.model.RequestInfo;
import com.zhenwu.common.entity.InnerApiTransferApiInvokeLog;
import com.zhenwu.common.entity.InnerApiTransferInterfaceInfo;
import com.zhenwu.common.entity.InnerApiTransferUser;
import com.zhenwu.common.service.InnerApiInterfaceInfoService;
import com.zhenwu.common.service.InnerApiUserInterfaceInfoService;
import com.zhenwu.common.service.InnerApiUserService;
import com.zhenwu.common.util.CommonUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.reactivestreams.Publisher;
import org.slf4j.MDC;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.stereotype.Component;
import org.springframework.util.StopWatch;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
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

    private static final String USER_ACCESS_KEY = "userAccesskey";

    private static final String REQUEST_INTERFACE_ID = "requestInterfaceId";

    @DubboReference
    private InnerApiUserService innerApiUserService;

    @DubboReference
    private InnerApiInterfaceInfoService innerApiInterfaceInfoService;

    @DubboReference
    private InnerApiUserInterfaceInfoService innerApiUserInterfaceInfoService;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {

        ServerHttpRequest request = exchange.getRequest();
        ServerHttpResponse response = exchange.getResponse();
        StopWatch stopWatch = new StopWatch();

        // 1.获取请求信息，并记录请求日志
        Map<String, Object> attributes = exchange.getAttributes();
        String requestBody = (String) attributes.get("requestBody");
        attributes.remove("requestBody");
        log.info("requestBody = {}", requestBody);
        RequestInfo requestInfo = this.verifyAndExtractInfo(requestBody);
        if (requestInfo == null) {
            response.setStatusCode(HttpStatus.BAD_REQUEST);
            response.getHeaders().add("Content-Type", "application/json");
            String errorMessage = "{\"error\": \"Bad Request!\"}";
            return response.writeWith(Mono.just(response.bufferFactory().wrap(errorMessage.getBytes())));
        }
        log.info("extract data = {}", requestInfo);

        InnerApiTransferInterfaceInfo innerApiTransferInterfaceInfo = requestInfo.getInnerApiTransferInterfaceInfo();
        InnerApiTransferUser innerApiTransferUser = requestInfo.getInnerApiTransferUser();
        MDC.put(USER_ACCESS_KEY, innerApiTransferUser.getUserAccesskey());
        MDC.put(REQUEST_INTERFACE_ID, innerApiTransferInterfaceInfo.getId().toString());

        // 2.查询用户调用次数是否充足
        int leftInvokeNum = this.innerApiUserInterfaceInfoService.queryLeftInvokeNum(innerApiTransferInterfaceInfo.getId(), innerApiTransferUser.getId());
        if (leftInvokeNum < 1) {
            response.setStatusCode(HttpStatus.OK);
            response.getHeaders().add("Content-Type", "application/json");
            String errorMessage = "{\"error\": \"您的调用次数已经用尽!\"}";
            return response.writeWith(Mono.just(response.bufferFactory().wrap(errorMessage.getBytes())));
        }

        ServerHttpRequest newRequest = mutateRequest(request, innerApiTransferInterfaceInfo, requestInfo.getRequestData().getInterfaceData());
        // 3.获取响应对象
        DataBufferFactory bufferFactory = response.bufferFactory();
        ServerHttpResponseDecorator responseDecorator = new ServerHttpResponseDecorator(response) {
            @Override
            public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
                HttpStatus statusCode = getStatusCode();
                stopWatch.stop();
                Long interfaceInfoId = innerApiTransferInterfaceInfo.getId();
                Long userId = innerApiTransferUser.getId();

                InnerApiTransferApiInvokeLog innerApiTransferApiInvokeLog = new InnerApiTransferApiInvokeLog();
                innerApiTransferApiInvokeLog.setUserId(userId);
                innerApiTransferApiInvokeLog.setInterfaceInfoId(interfaceInfoId);
                InetSocketAddress requestRemoteAddress = request.getRemoteAddress();
                if (requestRemoteAddress != null) {
                    innerApiTransferApiInvokeLog.setRequestIp(requestRemoteAddress.toString());
                }
                innerApiTransferApiInvokeLog.setResponseStatus(statusCode == null ? 500 : statusCode.value());
                innerApiTransferApiInvokeLog.setCostTime((int) stopWatch.getTotalTimeMillis());
                innerApiTransferApiInvokeLog.setRequestParameter(requestInfo.getRequestData().getInterfaceData());

                if (HttpStatus.OK.equals(statusCode) && body instanceof Flux) {
                    // 响应成功，减少调用次数
                    boolean invokeCountResult = innerApiUserInterfaceInfoService.invokeCount(interfaceInfoId, userId);
                    log.info("invokeCountResult = {}", invokeCountResult);

                    // 获取响应 ContentType
                    String responseContentType = exchange.getAttribute(ORIGINAL_RESPONSE_CONTENT_TYPE_ATTR);
                    log.info("responseContentType= {}", responseContentType);
                    // 记录 JSON 格式数据的响应体
                    if (!StrUtil.isEmpty(responseContentType) && responseContentType.contains(MediaType.APPLICATION_JSON_VALUE)) {
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
                            boolean insertInvokeLogResult = innerApiUserInterfaceInfoService.insertInvokeLog(innerApiTransferApiInvokeLog);
                            log.info("insertInvokeLogResult = {}", insertInvokeLogResult);
                            MDC.remove(USER_ACCESS_KEY);
                            MDC.remove(REQUEST_INTERFACE_ID);
                            return bufferFactory.wrap(list.toString().getBytes());
                        }));
                    }
                }
                log.info("body = {}", body);
                // 记录接口响应日志
                boolean insertInvokeLogResult = innerApiUserInterfaceInfoService.insertInvokeLog(innerApiTransferApiInvokeLog);
                log.info("insertInvokeLogResult = {}", insertInvokeLogResult);
                MDC.remove(USER_ACCESS_KEY);
                MDC.remove(REQUEST_INTERFACE_ID);
                return super.writeWith(body);
            }
        };

        stopWatch.start();
        ServerWebExchange mutatedExchange = exchange.mutate()
                .request(newRequest)
                .response(responseDecorator)
                .build();
        
        return chain.filter(mutatedExchange);
    }

    @Override
    public int getOrder() {
        // 必须要小于 -1
        return -2;
    }

    /**
     * 根据接口信息重新构造请求对象
     * @param request 原始请求
     * @param innerApiTransferInterfaceInfo 接口信息
     * @param interfaceData 接口数据
     * @return 符合接口要求的新请求对象
     */
    private ServerHttpRequest mutateRequest(ServerHttpRequest request, InnerApiTransferInterfaceInfo innerApiTransferInterfaceInfo, String interfaceData) {
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

        // 1.查询用户信息
        InnerApiTransferUser innerApiTransferUser = this.innerApiUserService.getUserInfo(accessKey);
        if (innerApiTransferUser == null) {
            // 未查询到用户信息
            log.error("未查询到{}的用户信息", accessKey);
            return null;
        }

        // 2.判断用户状态是否正常
        if (!new Integer(0).equals(innerApiTransferUser.getUserStatus())) {
            log.error("用户{}的状态不正常", accessKey);
            return null;
        }

        String decryptData;
        try {
            decryptData = CommonUtils.decrypt(data, innerApiTransferUser.getUserPrivatekey());
        } catch (Exception e) {
            log.error("数据解析失败", e);
            return null;
        }
        RequestData requestData = JSONUtil.toBean(decryptData, RequestData.class);

        // 3.验证签名
        String userSign = requestData.getSign();
        String interfaceToken = requestData.getInterfaceToken();
        String sign = requestData.getInterfaceData() == null ?
                CommonUtils.sign(innerApiTransferUser.getUserAccesskey() + interfaceToken + innerApiTransferUser.getUserSecretkey())
                : CommonUtils.sign(requestData.getInterfaceData() + innerApiTransferUser.getUserSecretkey());
        if (!sign.equals(userSign)) {
            log.error("用户{}的签名认证失败", accessKey);
            return null;
        }

        // 4.查询接口信息
        InnerApiTransferInterfaceInfo innerApiTransferInterfaceInfo = this.innerApiInterfaceInfoService.getInterfaceInfo(interfaceToken);
        if (innerApiTransferInterfaceInfo == null) {
            log.error("未查询到{}的用户访问的接口{}信息", accessKey, interfaceToken);
            return null;
        }

        // 5.判读接口状态
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
