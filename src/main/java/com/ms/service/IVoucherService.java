package com.ms.service;

import com.ms.dto.Result;
import com.ms.entity.Voucher;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * @author: Omerta
 * @create-date: 2023/5/24 11:34
 */
public interface IVoucherService extends IService<Voucher> {

    Result queryVoucherOfShop(Long shopId);

    void addSeckillVoucher(Voucher voucher);
}
