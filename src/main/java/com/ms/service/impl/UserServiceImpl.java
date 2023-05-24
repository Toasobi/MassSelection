package com.ms.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ms.dto.LoginFormDTO;
import com.ms.dto.Result;
import com.ms.dto.UserDTO;
import com.ms.entity.User;
import com.ms.mapper.UserMapper;
import com.ms.service.IUserService;
import com.ms.utils.RegexUtils;
import com.ms.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.ms.utils.RedisConstants.*;
import static com.ms.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * @author: Omerta
 * @create-date: 2023/5/24 11:34
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result sendCode(String phone, HttpSession session) {
        //1.校验手机号
        if(RegexUtils.isPhoneInvalid(phone)){
            //2.不符合返回
            return Result.fail("手机号格式不正确！");
        }

        //3.符合生成
        String code = RandomUtil.randomNumbers(6);

        //4.存储至session中 -> 存储至redis中
//        session.setAttribute("code",code);
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone,code,LOGIN_CODE_TTL,TimeUnit.MINUTES);

        //5.发送验证码
        //模拟一下流程
        log.debug("发送验证码成功:{}",code);

        //6.返回
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        //1.校验手机号
        if(RegexUtils.isPhoneInvalid(loginForm.getPhone())){
            //2.不符合返回
            return Result.fail("手机号格式不正确！");
        }

        String code = loginForm.getCode();
        String phone = loginForm.getPhone();
        //        String CacheCode = session.getAttribute("code").toString();
        String CacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY+phone);

        //2.校验验证码
        if(loginForm.getCode() == null || !(CacheCode.equals(code))){
            //3.不一致，报错
            return Result.fail("验证码不一致，请重新输入！");
        }

        //4.一致，根据手机号查用户
        User user = query().eq("phone", phone).one();

        //5.判断用户是否存在
        if(user == null){
            //6.不存在，创建并保存
            user = createUserWithPhone(phone);
//            log.debug("创建一个用户");
        }

        //7.存在，保存用户至session中 -> 保存用户至redis中
//        session.setAttribute("user",user);

        //7.1 随机生成token
        String token = UUID.randomUUID().toString(true);

        //7.2 user转为hash存储
        UserDTO userDTO = BeanUtil.copyProperties(user,UserDTO.class);
        /**
         * 因为用的是stringRedisTemplate，所以需要保证putAll的map的kv值全是string
         * 我们可以使用下面方法将map的类型重构
         */
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName,fieldValue) -> fieldValue.toString()));

        //7.3 存入redis中
        String tokenKey = LOGIN_USER_KEY + token;
        stringRedisTemplate.opsForHash().putAll(tokenKey,userMap);

        //7.4 设置token有效期
        stringRedisTemplate.expire(tokenKey,LOGIN_USER_TTL,TimeUnit.MINUTES);

        return Result.ok(token);
    }

    private User createUserWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        user.setCreateTime(LocalDateTime.now());
        save(user);
        return user;
    }

    @Override
    public Result sign() {
        // 1.获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        // 2.获取日期
        LocalDateTime now = LocalDateTime.now();
        // 3.拼接key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;
        // 4.获取今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();
        // 5.写入Redis SETBIT key offset 1
        stringRedisTemplate.opsForValue().setBit(key, dayOfMonth - 1, true);
        return Result.ok();
    }

    @Override
    public Result signCount() {
        // 1.获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        // 2.获取日期
        LocalDateTime now = LocalDateTime.now();
        // 3.拼接key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;
        // 4.获取今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();
        // 5.获取本月截止今天为止的所有的签到记录，返回的是一个十进制的数字 BITFIELD sign:5:202203 GET u14 0
        List<Long> result = stringRedisTemplate.opsForValue().bitField(
                key,
                BitFieldSubCommands.create()
                        .get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0)
        );
        if (result == null || result.isEmpty()) {
            // 没有任何签到结果
            return Result.ok(0);
        }
        Long num = result.get(0);
        if (num == null || num == 0) {
            return Result.ok(0);
        }
        // 6.循环遍历
        int count = 0;
        while (true) {
            // 6.1.让这个数字与1做与运算，得到数字的最后一个bit位  // 判断这个bit位是否为0
            if ((num & 1) == 0) {
                // 如果为0，说明未签到，结束
                break;
            }else {
                // 如果不为0，说明已签到，计数器+1
                count++;
            }
            // 把数字右移一位，抛弃最后一个bit位，继续下一个bit位
            num >>>= 1;
        }
        return Result.ok(count);
    }




}
