package com.ms.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.ms.dto.UserDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.ms.utils.RedisConstants.LOGIN_USER_KEY;

@Slf4j
public class RefreshTokenInterceptor implements HandlerInterceptor {

    private StringRedisTemplate stringRedisTemplate;

    public RefreshTokenInterceptor(StringRedisTemplate stringRedisTemplate){
        this.stringRedisTemplate = stringRedisTemplate;
    }


    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        //1.获取session -> 获取头中的token
//        HttpSession session = request.getSession();
        String token = request.getHeader("authorization");

        if(StrUtil.isBlank(token)){
            return true; //放行
        }

        //2.获取session中用户 -> 去redis中查找出用户
//        Object user = session.getAttribute("user");

        String tokenKey = LOGIN_USER_KEY + token;

        Map<Object, Object> userMap = stringRedisTemplate.opsForHash().entries(tokenKey);

        //3.判断存在
        if(userMap.isEmpty()){
            return true; //放行
        }
        //5.存在，保存至ThreadLocal中
        //map -> userDTO
        UserDTO userDTO = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);
        UserHolder.saveUser(BeanUtil.copyProperties(userDTO,UserDTO.class));

        //6 刷新token过期时间
        stringRedisTemplate.expire(tokenKey,RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);

        //7.放行
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        // 移除用户
        UserHolder.removeUser();
    }
}
