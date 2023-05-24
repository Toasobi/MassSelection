package com.ms.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ms.dto.Result;
import com.ms.entity.Shop;
import com.ms.mapper.ShopMapper;
import com.ms.service.IShopService;
import com.ms.utils.CacheClient;
import com.ms.utils.RedisData;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.ms.utils.RedisConstants.*;

/**
 * @author: Omerta
 * @create-date: 2023/5/24 11:34
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {


    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;

    @Override
    public Result queryById(Long id) {
        Shop shop = queryWithMutex(id);
        return Result.ok(shop);
    }

    /**
     * 缓存策略封装函数（逻辑过期实现，热点信息适用,不需要存空值防止击穿）
     * @param id
     * @return
     */
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10); //线程池

    public Shop queryWithLogicExpire(Long id){
        String key = CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        if(StrUtil.isBlank(shopJson)){
            //缓存不存在直接返回空
            return null;
        }

        //1.命中缓存,先进行json返序列化
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
        //2.判断是否过期
        if(redisData.getExpireTime().isAfter(LocalDateTime.now())){
            //2.1 未过期，直接返回
            return shop;
        }
        //2.2 过期，准备缓存重建，先获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);
        //3.实习缓存重建
        //3.1 判断锁获取是否成功
        if(isLock){
            //3.2 成功，开启新线程，实现重建
            CACHE_REBUILD_EXECUTOR.submit(()->{
                try {
                    this.saveShopToRedis(id,20L);
                }catch (Exception e){
                    throw e;
                }finally {
                    unlock(lockKey);
                }
            });
        }
        //3.3 返回过期的旧对象
        return shop;
    }



        /**
         * 缓存策略封装函数（互斥锁实现击穿解决）
         * @param id
         * @return
         */
    public Shop queryWithMutex(Long id){
        String key = CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        if(StrUtil.isNotBlank(shopJson)){
            //返回缓存中的值
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        //防止缓存穿透策略
        if(StrUtil.isBlank(shopJson) && shopJson != null){
            log.debug("查询不存在的值，防止穿透策略启动！");
//            return Result.fail("查询不到该值！");
            return null;
        }

        //实现缓存重建
        boolean tryLock = false;
        String LockKey = LOCK_SHOP_KEY + id;

        try {
            //4.1 先进行一次获取锁
            tryLock = tryLock(LockKey);

            //4.2 判断
            while(!tryLock){
                //4.3 失败，尝试休眠
                Thread.sleep(50);
                //再次获取锁
                tryLock = tryLock(LockKey);
            }

            //4.4 成功获取，二次查询缓存，没有再查库
            String shopJson2 = stringRedisTemplate.opsForValue().get(key);
            if(StrUtil.isNotBlank(shopJson2)){
                //返回缓存中的值
                Shop shop = JSONUtil.toBean(shopJson2, Shop.class);
                return shop;
            }

            //缓存中没有值
            Shop shop = getById(id);

            //模拟业务执行流程
            Thread.sleep(200);
            if(shop == null){
                //数据库中没有值
                //存储空值防止穿透
                stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
    //            return Result.fail("查询不到该值！");
                return null;
            }
            //查询到了，写入缓存
            String s = JSONUtil.toJsonStr(shop);
            stringRedisTemplate.opsForValue().set(key,s,CACHE_SHOP_TTL,TimeUnit.MINUTES);
            // 7.返回
            return shop;
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }finally {
            unlock(LockKey);
        }
    }


    /**
     * 缓存策略封装函数
     * @param id
     * @return
     */
    public Shop queryWithPassThrough(Long id){
        String key = CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        if(StrUtil.isNotBlank(shopJson)){
            //返回缓存中的值
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        //防止缓存穿透策略
        if(StrUtil.isBlank(shopJson) && shopJson != null){
            log.debug("查询不存在的值，防止穿透策略启动！");
//            return Result.fail("查询不到该值！");
            return null;
        }

        //缓存中没有值
        Shop shop = getById(id);
        if(shop == null){
            //数据库中没有值
            //存储空值防止穿透
            stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
//            return Result.fail("查询不到该值！");
            return null;
        }
        //查询到了，写入缓存
        String s = JSONUtil.toJsonStr(shop);
        stringRedisTemplate.opsForValue().set(key,s,CACHE_SHOP_TTL,TimeUnit.MINUTES);
        // 7.返回
        return shop;
    }

    /**
     * 加互斥锁
     * @param key
     * @return
     */
    private boolean tryLock(String key){
        Boolean ifAbsent = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", LOCK_SHOP_TTL, TimeUnit.MINUTES);
        return BooleanUtil.isTrue(ifAbsent); //防止自动拆箱导致的空指针异常
    }

    /**
     * 释放锁
     * @param key
     * @return
     */
    private boolean unlock(String key){
        Boolean delete = stringRedisTemplate.delete(key);
        return BooleanUtil.isTrue(delete);
    }


    public void saveShopToRedis(Long id,Long seconds){
        //1.查出数据
        Shop byId = getById(id);
        //2.封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(byId);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(seconds));
        //3.导入redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id,JSONUtil.toJsonStr(redisData));
    }


    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("店铺id不能为空");
        }
        // 1.更新数据库
        updateById(shop);
        // 2.删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);
        return Result.ok();
    }

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        log.debug("what the fuck");
//        // 1.判断是否需要根据坐标查询
//        if (x == null || y == null) {
//            // 不需要坐标查询，按数据库查询
//            Page<Shop> page = query()
//                    .eq("type_id", typeId)
//                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
//            // 返回数据
//            return Result.ok(page.getRecords());
//        }
//
//        // 2.计算分页参数
//        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
//        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;
//
//        // 3.查询redis、按照距离排序、分页。结果：shopId、distance
//        String key = SHOP_GEO_KEY + typeId;
//        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo() // GEOSEARCH key BYLONLAT x y BYRADIUS 10 WITHDISTANCE
//                .search(
//                        key,
//                        GeoReference.fromCoordinate(x, y),
//                        new Distance(5000),
//                        RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end)
//                );
//        // 4.解析出id
//        if (results == null) {
//            return Result.ok(Collections.emptyList());
//        }
//        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
//        if (list.size() <= from) {
//            // 没有下一页了，结束
//            return Result.ok(Collections.emptyList());
//        }
//        // 4.1.截取 from ~ end的部分
//        List<Long> ids = new ArrayList<>(list.size());
//        Map<String, Distance> distanceMap = new HashMap<>(list.size());
//        list.stream().skip(from).forEach(result -> {
//            // 4.2.获取店铺id
//            String shopIdStr = result.getContent().getName();
//            ids.add(Long.valueOf(shopIdStr));
//            // 4.3.获取距离
//            Distance distance = result.getDistance();
//            distanceMap.put(shopIdStr, distance);
//        });
//        // 5.根据id查询Shop
//        String idStr = StrUtil.join(",", ids);
//        List<Shop> shops = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();
//        for (Shop shop : shops) {
//            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
//        }
//        // 6.返回
//        return Result.ok(shops);

        List<Shop> shops = query().list();
        return Result.ok(shops);
    }
}
