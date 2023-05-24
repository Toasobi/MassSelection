package com.ms.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ms.dto.Result;
import com.ms.entity.VoucherOrder;
import com.ms.mapper.VoucherOrderMapper;
import com.ms.service.IVoucherOrderService;
import com.ms.utils.RedisIdWorker;
import com.ms.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * @author: Omerta
 * @create-date: 2023/5/24 11:34
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Autowired
    SeckillVoucherServiceImpl seckillVoucherService;

    @Autowired
    private VoucherOrderServiceImpl voucherOrderServiceImpl;

    @Autowired
    RedisIdWorker redisIdWorker;

    @Autowired
    StringRedisTemplate stringRedisTemplate;

    @Autowired
    RedissonClient redissonClient;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor(); //开个单线程用于处理消息队列中的消息

    //类初始化后立即执行
    @PostConstruct
    private void init(){
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    //提前往redis中创建好了消息队列 XGROUP CREATE stream.orders g1 0 MKSTREAM

    private static final String queueName = "stream.orders";


    private class VoucherOrderHandler implements Runnable{

        @Override
        public void run() {
            while(true){
                try{
                    //读取消息队列中的消息 XREADGROUP GROUP g1 c1 COUNT 1 STREAMS stream.orders >
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
                    );
                    //判断消息是否获取成功
                    if(list == null || list.isEmpty()){
                        //没有说明没有消息，继续下一次获取
                        continue;
                    }
                    //获取到了消息，需要进行解析
                    MapRecord<String, Object, Object> entries = list.get(0);
                    Map<Object, Object> value = entries.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    //解析成功，可以下单
                    handleVoucherOrder(voucherOrder);
                    //ACK确认 SACK stream.orders g1 id
                    stringRedisTemplate.opsForStream().acknowledge(queueName,"c1",entries.getId());

                }catch (Exception e){
                    log.error("处理订单异常！",e);
                    handlePendingList();
                }
            }
        }
    }

    private void handlePendingList() {
        while(true){
            try{
                //读取pending-list中的消息 XREADGROUP GROUP g1 c1 COUNT 1 STREAMS stream.orders 0
                List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                        Consumer.from("g1", "c1"),
                        StreamReadOptions.empty().count(1),
                        StreamOffset.create(queueName, ReadOffset.from("0"))
                );
                //判断消息是否获取成功
                if(list == null || list.isEmpty()){
                    //没有pending-list说明没有消息，结束循环
                    break;
                }
                //获取到了消息，需要进行解析
                MapRecord<String, Object, Object> entries = list.get(0);
                Map<Object, Object> value = entries.getValue();
                VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                //解析成功，可以下单
                handleVoucherOrder(voucherOrder);
                //ACK确认 SACK stream.orders g1 id
                stringRedisTemplate.opsForStream().acknowledge(queueName,"c1",entries.getId());

            }catch (Exception e){
                log.error("处理pending-list异常！",e);
                try {
                    Thread.sleep(20);
                } catch (InterruptedException ex) {
                    throw new RuntimeException(ex);
                }
            }
        }
    }


//    @Override
//    public Result seckillVoucher(Long voucherId) {
//        //获取用户
//        Long id = UserHolder.getUser().getId();
//        //1.执行lua脚本
//        Long result = stringRedisTemplate.execute(
//                SECKILL_SCRIPT,
//                Collections.emptyList(),
//                voucherId.toString(), id.toString()
//        );
//
//        //2.判断结果是否为0
//        int r = result.intValue();
//        if(r != 0){
//            return Result.fail(r == 1 ? "库存不足！":"不准重复下单！");
//        }
//
//        //2.2 为0，证明有购买资格，把下单信息放在阻塞队列中
//        long orderId = redisIdWorker.nextId("order");
//        //TODO 将信息保存到阻塞队列中
//
//        //3.返回订单信息
//        return Result.ok(orderId);
//
//
//    }


    @Override
    public Result seckillVoucher(Long voucherId) {
        //获取用户
        Long id = UserHolder.getUser().getId();
        //获取订单id
        long orderId = redisIdWorker.nextId("order");
        //1.执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), id.toString(), String.valueOf(orderId)
        );

        //判断结果是否为0
        int r = result.intValue();
        if(r != 0){
            //不等于0，表示抢购失败
            return Result.fail(r == 1 ? "库存不足！":"您已经下过单了！");
        }

        //3.返回订单信息
        return Result.ok(orderId);
    }


    private void handleVoucherOrder(VoucherOrder voucherOrder){
        //1.获取用户id
        Long userId = voucherOrder.getUserId();
        //2.获取锁对象
        RLock lock = redissonClient.getLock("order:" + userId);
        boolean isLock = lock.tryLock();
        if(!isLock){
            log.debug("不允许重复下单!");
        }
        try{
            voucherOrderServiceImpl.createVoucherOrder(voucherOrder); //防止事务失效
        }finally {
            lock.unlock();
        }
    }




//    @Override
//    @Transactional
//    public Result seckillVoucher(Long voucherId) {
//        //1.查询劵
//        SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);
//        //2.判断秒杀是否开始
//        LocalDateTime beginTime = seckillVoucher.getBeginTime();
//        if(!LocalDateTime.now().isAfter(beginTime)){
//            return Result.fail("代金券抢购活动未开始");
//        }
//        //3.判断秒杀是否结束
//        LocalDateTime endTime = seckillVoucher.getEndTime();
//        if(LocalDateTime.now().isAfter(endTime)){
//            return Result.fail("代金券抢购活动已结束");
//        }
//        //4.库存是否充足
//        if(seckillVoucher.getStock()<0){
//            return Result.fail("库存不足");
//        }
//        Long userId = UserHolder.getUser().getId();
//        /**
//         * 此处加锁加在整个函数上，确保事务提交后再释放锁
//         * 锁的对象是userId（缩小锁的范围），因为只需要实现一人一单的功能
//         */
//
////        SimpleRedisLock simpleRedisLock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
//
//        RLock lock = redissonClient.getLock("order:" + userId);
//
////        boolean isLock = simpleRedisLock.tryLock(1200);
//
//        boolean isLock = lock.tryLock();
//
////           synchronized (userId.toString().intern()){
////            return voucherOrderServiceImpl.createVoucherOrder(voucherId); //防止事务失效
////        }  --> 这个不适用于分布式
//
//        if(!isLock){
//            return Result.fail("不允许重复下单");
//        }
//        try{
//            return voucherOrderServiceImpl.createVoucherOrder(voucherId); //防止事务失效
//        }finally {
////            simpleRedisLock.unlock();
//            lock.unlock();
//        }
//    }

//    @Override
//    @Transactional
//    public Result createVoucherOrder(Long voucherId) {
//        UserDTO user = UserHolder.getUser();
//        //一人一单
//        Long userId = user.getId();
//
//        /**
//         * 可以保证比较的是值（userId是包装类对象，直接比较不会相等,toString也只是new了一个新string对象）
//         *         需要用intern()方法
//         */
//            //查询订单
//            Integer count = query().eq("voucher_id", voucherId).eq("user_id", userId).count();
//
//            //判断是否已经购买过
//            if(count > 0){
//                return Result.fail("您已经抢购过此商品！");
//            }
//
//            //5.扣除库存
//            boolean isUpdate = seckillVoucherService.update().setSql("stock = stock -1").eq("voucher_id", voucherId).gt("stock",0).update();
//            if(!isUpdate){
//                return Result.fail("扣除库存失败");
//            }
//
//            //6.创建订单
//            //6.1 创建对象
//            VoucherOrder voucherOrder = new VoucherOrder();
//
//            //6.2 生成唯一id
//            long orderId = redisIdWorker.nextId("order");
//            voucherOrder.setId(orderId);
//
//            //6.3 用户id
//            voucherOrder.setUserId(userId);
//
//            //6.4 代金券id
//            voucherOrder.setVoucherId(voucherId);
//
//            save(voucherOrder);
//            //返回订单id
//            return Result.ok(orderId);
//
//    }

    @Override
    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        //一人一单
        Long userId = voucherOrder.getUserId();

        /**
         * 可以保证比较的是值（userId是包装类对象，直接比较不会相等,toString也只是new了一个新string对象）
         *         需要用intern()方法
         */
        //查询订单
        Integer count = query().eq("voucher_id", voucherOrder.getVoucherId()).eq("user_id", userId).count();

        //判断是否已经购买过
        if(count > 0){
            log.debug("您已经抢购过此商品！");
            return;
        }

        //5.扣除库存
        boolean isUpdate = seckillVoucherService.update().setSql("stock = stock -1").eq("voucher_id", voucherOrder.getVoucherId()).gt("stock",0).update();
        if(!isUpdate){
            log.debug("扣除库存失败");
            return;
        }

        save(voucherOrder);
    }
}