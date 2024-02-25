package com.zhenwu.api.service.impl;

import cn.hutool.json.JSONUtil;
import com.zhenwu.api.service.RouteService;
import com.zhenwu.common.entity.InterfaceRoute;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.event.RefreshRoutesEvent;
import org.springframework.cloud.gateway.filter.FilterDefinition;
import org.springframework.cloud.gateway.handler.predicate.PredicateDefinition;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.route.RouteDefinitionLocator;
import org.springframework.cloud.gateway.route.RouteDefinitionRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import javax.annotation.Resource;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * @author zhenwu
 */
@Service
@Slf4j
public class RedisRouteService implements RouteService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private ApplicationEventPublisher publisher;

    @Resource
    private RouteDefinitionRepository routeDefinitionRepository;

    @Override
    public RouteDefinitionLocator InitializeRouteInfo() {
        log.info("开始初始化路由信息....");
        this.refreshRouteInfo();
        log.info("路由信息初始化成功！");
        return () -> this.routeDefinitionRepository.getRouteDefinitions();
    }

    private void refreshRouteInfo() {
        String routeInfoJson = stringRedisTemplate.opsForValue().get("route");
        log.info("路由信息 = {}", routeInfoJson);
        if (routeInfoJson == null) {
            return;
        }
        List<InterfaceRoute> interfaceRouteList = JSONUtil.toList(routeInfoJson, InterfaceRoute.class);
        int size = interfaceRouteList.size();
        for (int i = 0; i < size; i++) {
            InterfaceRoute interfaceRoute = interfaceRouteList.get(i);

            RouteDefinition routeDefinition = new RouteDefinition();
            routeDefinition.setId(i + "");
            routeDefinition.setUri(URI.create(interfaceRoute.getHost()));

            // 创建断言
            List<PredicateDefinition> predicates = new ArrayList<>();
            PredicateDefinition predicateDefinition = new PredicateDefinition();
            predicateDefinition.setName("Path");
            List<String> paths = interfaceRoute.getPaths();
            for (int j = 0; j < paths.size(); j++) {
                predicateDefinition.addArg("_genkey_" + j, paths.get(j));
            }
            predicates.add(predicateDefinition);
            routeDefinition.setPredicates(predicates);

            // 创建过滤器
            List<FilterDefinition> filters = new ArrayList<>();
            FilterDefinition filterDefinition = new FilterDefinition();
            filterDefinition.setName("StripPrefix");
            filterDefinition.addArg("_genkey_0", "1");
            filters.add(filterDefinition);
            routeDefinition.setFilters(filters);
            this.routeDefinitionRepository.save(Mono.just(routeDefinition)).subscribe();
        }
    }

    @Override
    public void updateRouteInfo() {
        log.info("开始更新路由信息....");
        this.refreshRouteInfo();
        this.publisher.publishEvent(new RefreshRoutesEvent(this));
        log.info("路由信息更新成功！");
    }
}
