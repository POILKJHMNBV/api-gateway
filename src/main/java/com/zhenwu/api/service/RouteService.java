package com.zhenwu.api.service;

import org.springframework.cloud.gateway.route.RouteDefinitionLocator;

/**
 * @author zhenwu
 */
public interface RouteService {
    RouteDefinitionLocator InitializeRouteInfo();
    void updateRouteInfo();
}
