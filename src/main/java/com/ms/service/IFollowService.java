package com.ms.service;

import com.ms.dto.Result;
import com.ms.entity.Follow;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * @author: Omerta
 * @create-date: 2023/5/24 11:34
 */
public interface IFollowService extends IService<Follow> {

    Result follow(Long followUserId, Boolean isFollow);

    Result isFollow(Long followUserId);

    Result followCommons(Long id);
}
