package com.ms.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.ms.dto.LoginFormDTO;
import com.ms.dto.Result;
import com.ms.entity.User;

import javax.servlet.http.HttpSession;

/**
 * @author: Omerta
 * @create-date: 2023/5/24 11:34
 */
public interface IUserService extends IService<User> {

    Result sendCode(String phone, HttpSession session);

    Result login(LoginFormDTO loginForm, HttpSession session);

    Result sign();

    Result signCount();

}
