package com.ms.service.impl;

import com.ms.entity.ShopType;
import com.ms.mapper.ShopTypeMapper;
import com.ms.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.List;

import static com.ms.utils.RedisConstants.CACHE_SHOP_KEY_SPRING_CACHE;

/**
 * @author: Omerta
 * @create-date: 2023/5/24 11:34
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {
    @Cacheable(value = {CACHE_SHOP_KEY_SPRING_CACHE},key = "'list'")
    @Override
    public List<ShopType> queryTypeList() {
        List<ShopType> sort = query().orderByAsc("sort").list();
        return sort;
    }
}
