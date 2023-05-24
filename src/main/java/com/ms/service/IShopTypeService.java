package com.ms.service;

import com.ms.entity.ShopType;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

/**
 * @author: Omerta
 * @create-date: 2023/5/24 11:34
 */
public interface IShopTypeService extends IService<ShopType> {

    List<ShopType> queryTypeList();
}
