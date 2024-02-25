package com.zhenwu.api.controller;

import com.zhenwu.api.service.RouteService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

/**
 * @author zhenwu
 */
@RestController
@RequestMapping("/gateway")
@Slf4j
public class RouteController {

    @Resource
    private RouteService routeService;

    @GetMapping("/updateRouteInfo")
    public boolean updateRouteInfo() {
        this.routeService.updateRouteInfo();
        return true;
    }

    @GetMapping("/updateRouteTest")
    public String updateRouteTest() {
        log.info("Hello Spring Cloud Gateway!");
        return "Hello World!";
    }
}
