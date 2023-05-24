package com.ms.service;

import com.ms.dto.Result;
import com.ms.entity.VoucherOrder;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * @author: Omerta
 * @create-date: 2023/5/24 11:34
 */
public interface IVoucherOrderService extends IService<VoucherOrder> {

    Result seckillVoucher(Long voucherId);

    void createVoucherOrder(VoucherOrder voucherOrder);


}
