package com.zhenwu.api.config;

import com.zhenwu.api.service.RouteService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.route.RouteDefinitionLocator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.annotation.Resource;
/**
 * @author zhenwu
 * 自定义路由规则配置类
 */
@Configuration
@Slf4j
public class RouteConfig {

    @Resource
    private RouteService routeService;

    @Bean
    public RouteDefinitionLocator customRouteDefinitionLocator() {
        return this.routeService.InitializeRouteInfo();
    }
}