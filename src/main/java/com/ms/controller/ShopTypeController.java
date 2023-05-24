package com.ms.controller;


import com.ms.dto.Result;
import com.ms.entity.ShopType;
import com.ms.service.IShopTypeService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import java.util.List;

/**
 * 前端总控
 * @author: Omerta
 * @create-date: 2023/5/24 11:34
 */
@RestController
@RequestMapping("/shop-type")
public class ShopTypeController {
    @Resource
    private IShopTypeService typeService;


    @GetMapping("list")
    public Result queryTypeList() {
        List<ShopType> typeList = typeService.queryTypeList();
        return Result.ok(typeList);
    }
}
