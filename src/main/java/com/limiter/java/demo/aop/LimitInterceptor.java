package com.limiter.java.demo.aop;

import com.alibaba.fastjson.JSONObject;
import com.google.common.collect.ImmutableList;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.io.Serializable;
import java.lang.reflect.Method;
import java.util.Map;


/**
 * 分布式限流拦截器
 */
@Slf4j
@Aspect
@Configuration
public class LimitInterceptor {

    @Value("${rateLimit}")
    private String limitMap;

    private static final String UNKNOWN = "unknown";

    private final RedisTemplate<String, Serializable> limitRedisTemplate;
    @Resource
    private HttpServletRequest request;

    @Autowired
    public LimitInterceptor(RedisTemplate<String, Serializable> limitRedisTemplate) {
        this.limitRedisTemplate = limitRedisTemplate;
    }

    /**
     * 切面
     *
     * @param pjp
     * @return java.lang.Object
     */
    @Around("@annotation(com.limiter.java.demo.aop.Limit)")
    public Object interceptor(ProceedingJoinPoint pjp) {
        MethodSignature signature = (MethodSignature) pjp.getSignature();
        Method method = signature.getMethod();
        Limit limitAnnotation = method.getAnnotation(Limit.class);
        String limitType = limitAnnotation.limitType();
        String name = limitAnnotation.name();
        String key;
        /**
         * 根据限流类型获取不同的key ,如果不传我们会以方法名作为key
         */
        switch (limitType) {
            case "IP":
                key = getIpAddress();
                break;
            case "CUSTOMER":
                key = getAppKey();
                break;
            default:
                key = StringUtils.upperCase(method.getName());
        }
        int limitPeriod = limitAnnotation.period();
        //int limitCount = limitAnnotation.count();
        int limitCount = getRate(key);

        ImmutableList<String> keys = ImmutableList.of(StringUtils.join(limitAnnotation.prefix(), key));
        try {
            String luaScript = buildLuaScript();
            RedisScript<Number> redisScript = new DefaultRedisScript<>(luaScript, Number.class);
            Number count = limitRedisTemplate.execute(redisScript, keys, limitCount, limitPeriod);
            if (count != null && count.intValue() <= limitCount) {
                log.info("Access try count is {} for name={} and key = {}", count, name, key);
                return pjp.proceed();
            } else {
                //限流处理
                //告警
                //doSomething();
                log.error("系统繁忙，限流处理中，请稍后再试");
                return "系统繁忙，限流处理中，请稍后再试";
                //throw new RuntimeException("You have been dragged into the blacklist");
            }
        } catch (Throwable e) {
            if (e instanceof RuntimeException) {
                throw new RuntimeException(e.getLocalizedMessage());
            }
            throw new RuntimeException("server exception");
        }
    }

    /**
     * 编写 redis Lua 限流脚本
     */
    public String buildLuaScript() {
        StringBuilder lua = new StringBuilder();
        lua.append("local c");
        lua.append("\nc = redis.call('get',KEYS[1])");
        // 调用不超过最大值，则直接返回
        lua.append("\nif c and tonumber(c) > tonumber(ARGV[1]) then");
        lua.append("\nreturn c;");
        lua.append("\nend");
        // 执行计算器自加
        lua.append("\nc = redis.call('incr',KEYS[1])");
        lua.append("\nif tonumber(c) == 1 then");
        // 从第一次调用开始限流，设置对应键值的过期
        lua.append("\nredis.call('expire',KEYS[1],ARGV[2])");
        lua.append("\nend");
        lua.append("\nreturn c;");
        return lua.toString();
    }


    /**
     * 获取id地址
     */
    public String getIpAddress() {
        HttpServletRequest request = ((ServletRequestAttributes) RequestContextHolder.getRequestAttributes()).getRequest();
        String ip = request.getHeader("x-forwarded-for");
        if (ip == null || ip.length() == 0 || UNKNOWN.equalsIgnoreCase(ip)) {
            ip = request.getHeader("Proxy-Client-IP");
        }
        if (ip == null || ip.length() == 0 || UNKNOWN.equalsIgnoreCase(ip)) {
            ip = request.getHeader("WL-Proxy-Client-IP");
        }
        if (ip == null || ip.length() == 0 || UNKNOWN.equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        return ip;
    }


    /**
     * 从请求参数中获取appKey
     *
     * @return
     */
    public String getAppKey() {
        JSONObject reqObject = JSONObject.parseObject(request.getParameter("content"));
        if (!reqObject.isEmpty()) {
            return reqObject.getString("key");
        }
        return null;
    }


    /**
     * 根据平台key值获取对应的限流速率
     *
     * @return
     */
    public int getRate(String key) {
        Map<String, String> map = JSONObject.parseObject(limitMap, Map.class);
        return Integer.parseInt(map.get(key));
    }
}
