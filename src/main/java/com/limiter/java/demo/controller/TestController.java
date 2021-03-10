package com.limiter.java.demo.controller;

import com.limiter.java.demo.aop.Limit;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 *
 */
@RestController
public class TestController {

    @Limit
    @RequestMapping("/limiter")
    public String testLimiter(@RequestParam("content") String content) {
        return "成功，通过限流";
    }
}
