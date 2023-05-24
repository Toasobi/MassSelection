package com.ms.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ms.entity.Voucher;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * @author: Omerta
 * @create-date: 2023/5/24 11:34
 */
public interface VoucherMapper extends BaseMapper<Voucher> {

    List<Voucher> queryVoucherOfShop(@Param("shopId") Long shopId);
}
