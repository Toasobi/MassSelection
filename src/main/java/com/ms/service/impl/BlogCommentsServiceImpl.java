package com.ms.service.impl;

import com.ms.entity.BlogComments;
import com.ms.mapper.BlogCommentsMapper;
import com.ms.service.IBlogCommentsService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

/**
 * @author: Omerta
 * @create-date: 2023/5/24 11:34
 */
@Service
public class BlogCommentsServiceImpl extends ServiceImpl<BlogCommentsMapper, BlogComments> implements IBlogCommentsService {

}
