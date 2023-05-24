package com.ms.service.impl;

import com.ms.entity.UserInfo;
import com.ms.mapper.UserInfoMapper;
import com.ms.service.IUserInfoService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

/**
 * @author: Omerta
 * @create-date: 2023/5/24 11:34
 */
@Service
public class UserInfoServiceImpl extends ServiceImpl<UserInfoMapper, UserInfo> implements IUserInfoService {

}
