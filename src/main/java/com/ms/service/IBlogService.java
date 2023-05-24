package com.ms.service;

import com.ms.dto.Result;
import com.ms.entity.Blog;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * @author: Omerta
 * @create-date: 2023/5/24 11:34
 */
public interface IBlogService extends IService<Blog> {

    Result queryHotBlog(Integer current);

    Result queryBlogById(Long id);

    Result likeBlog(Long id);

    Result queryBlogLikes(Long id);

    Result saveBlog(Blog blog);

    Result queryBlogOfFollow(Long max, Integer offset);

}
