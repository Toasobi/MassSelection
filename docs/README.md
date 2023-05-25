# 项目说明
- 该项目是一个基于springboot和redis开发的系统

# 环境说明

## nginx配置
- server块中监听8080端口（前端项目端口），同时指定前端项目所在位置，指定首页，将其与nginx中的首页替换。

```nginx
    server {
        listen       8080;
        server_name  localhost;
        # 指定前端项目所在的位置
        location / {
            root   html/hmdp;
            index  index.html index.htm;
        }
```

```nginx
        error_page   500 502 503 504  /50x.html;
        location = /50x.html {
            root   html;
        }
```

```nginx
        location /api {  #反向代理
            default_type  application/json;
            #internal;  
            keepalive_timeout   30s;  
            keepalive_requests  1000;  
            #支持keep-alive  
            proxy_http_version 1.1;  
            rewrite /api(/.*) $1 break;  
            proxy_pass_request_headers on;
            #more_clear_input_headers Accept-Encoding;  
            proxy_next_upstream error timeout;  
            proxy_pass http://127.0.0.1:8081;
            #proxy_pass http://backend;
        }
    }
```

```nginx
    upstream backend {
        server 127.0.0.1:8081 max_fails=5 fail_timeout=10s weight=1;
        #server 127.0.0.1:8082 max_fails=5 fail_timeout=10s weight=1;
    }  

```




# 步骤详解

## 登录实现

### 发送验证码

```java
    @Override
    public Result sendCode(String phone, HttpSession session) {
        //1.校验手机号
        if(RegexUtils.isPhoneInvalid(phone)){
            //2.不符合返回
            return Result.fail("手机号格式不正确！");
        }

        //3.符合生成
        String code = RandomUtil.randomNumbers(6);

        //4.存储至session中 -> 存储至redis中
//        session.setAttribute("code",code);
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone,code,LOGIN_CODE_TTL,TimeUnit.MINUTES);

        //5.发送验证码
        //模拟一下流程
        log.debug("发送验证码成功:{}",code);

        //6.返回
        return Result.ok();
    }
```


### 具体登录操作

```java
@Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        //1.校验手机号
        if(RegexUtils.isPhoneInvalid(loginForm.getPhone())){
            //2.不符合返回
            return Result.fail("手机号格式不正确！");
        }

        String code = loginForm.getCode();
        String phone = loginForm.getPhone();
        //        String CacheCode = session.getAttribute("code").toString();
        String CacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY+phone);

        //2.校验验证码
        if(loginForm.getCode() == null || !(CacheCode.equals(code))){
            //3.不一致，报错
            return Result.fail("验证码不一致，请重新输入！");
        }

        //4.一致，根据手机号查用户
        User user = query().eq("phone", phone).one();

        //5.判断用户是否存在
        if(user == null){
            //6.不存在，创建并保存
            user = createUserWithPhone(phone);
//            log.debug("创建一个用户");
        }

        //7.存在，保存用户至session中 -> 保存用户至redis中
//        session.setAttribute("user",user);

        //7.1 随机生成token
        String token = UUID.randomUUID().toString(true);

        //7.2 user转为hash存储
        UserDTO userDTO = BeanUtil.copyProperties(user,UserDTO.class);
        /**
         * 因为用的是stringRedisTemplate，所以需要保证putAll的map的kv值全是string
         * 我们可以使用下面方法将map的类型重构
         */
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName,fieldValue) -> fieldValue.toString()));

        //7.3 存入redis中
        String tokenKey = LOGIN_USER_KEY + token;
        stringRedisTemplate.opsForHash().putAll(tokenKey,userMap);

        //7.4 设置token有效期
        stringRedisTemplate.expire(tokenKey,LOGIN_USER_TTL,TimeUnit.MINUTES);

        return Result.ok(token);
    }
```

### 校验登录
> 双重拦截器，TL，拦截器注册，redistemplate传参


> 登录校验拦截器
```java
@Slf4j
public class LoginInterceptor implements HandlerInterceptor {
    
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        UserDTO user = UserHolder.getUser();
        if(user == null){
            response.setStatus(401);
            return false; //拦截
        }
        //放行
        return true;
    }

}
```


> token刷新拦截器
```java
@Slf4j
public class RefreshTokenInterceptor implements HandlerInterceptor {

    private StringRedisTemplate stringRedisTemplate;

    public RefreshTokenInterceptor(StringRedisTemplate stringRedisTemplate){
        this.stringRedisTemplate = stringRedisTemplate;
    }


    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        //1.获取session -> 获取头中的token
//        HttpSession session = request.getSession();
        String token = request.getHeader("authorization");

        if(StrUtil.isBlank(token)){
            return true; //放行
        }

        //2.获取session中用户 -> 去redis中查找出用户
//        Object user = session.getAttribute("user");

        String tokenKey = LOGIN_USER_KEY + token;

        Map<Object, Object> userMap = stringRedisTemplate.opsForHash().entries(tokenKey);

        //3.判断存在
        if(userMap.isEmpty()){
            return true; //放行
        }
        //5.存在，保存至ThreadLocal中
        //map -> userDTO
        UserDTO userDTO = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);
        UserHolder.saveUser(BeanUtil.copyProperties(userDTO,UserDTO.class));

        //6 刷新token过期时间
        stringRedisTemplate.expire(tokenKey,RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);

        //7.放行
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        // 移除用户
        UserHolder.removeUser();
    }
}
```

> 拦截器注册
```java
@Configuration
public class MvcConfig implements WebMvcConfigurer {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 登录拦截器
        registry.addInterceptor(new LoginInterceptor())
                .excludePathPatterns(
                        "/shop/**",
                        "/voucher/**",
                        "/shop-type/**",
                        "/upload/**",
                        "/blog/hot",
                        "/user/code",
                        "/user/login"
                ).order(1);
        // token刷新的拦截器
        registry.addInterceptor(new RefreshTokenInterceptor(stringRedisTemplate)).addPathPatterns("/**").order(0);
    }
}
```

## Redis缓存

### 缓存策略

```java
@Override
    public Result queryById(Long id) {
        String key = CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        if(StrUtil.isNotBlank(shopJson)){
            //返回缓存中的值
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return Result.ok(shop);
        }
        //缓存中没有值
        Shop shop = getById(id);
        if(shop == null){
            //数据库中没有值
            return Result.fail("查询不到该值！");
        }
        //查询到了，写入缓存
        String s = JSONUtil.toJsonStr(shop);
        stringRedisTemplate.opsForValue().set(key,s,CACHE_SHOP_TTL,TimeUnit.MINUTES);
        // 7.返回
        return Result.ok(shop);
    }
```

### 使用SpringCache实现缓存策略
> 配置文件，MyCacheConfig配置，@Cacheable注解

```java
@Configuration
@EnableCaching
public class MyCacheConfig {

    @Bean
    RedisCacheConfiguration redisCacheConfiguration(){

        RedisCacheConfiguration config = RedisCacheConfiguration.defaultCacheConfig();

        config = config.serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer()));
        config = config.serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(new GenericJackson2JsonRedisSerializer()));

        return config;
    }
}
```

```java
 @Cacheable(value = {CACHE_SHOP_KEY_SPRING_CACHE},key = "'type'")
    @Override
    public List<ShopType> queryTypeList() {
        List<ShopType> sort = query().orderByAsc("sort").list();
        return sort;
    }
```



### 实现redis缓存与数据库的双写一致
> 注意@Transactional->开启事务。单体架构下更新数据库和删除缓存使用单体事务保证原子性。</p>
> 在分布式下，需要使用MQ开启事务

```java
@PutMapping
    public Result updateShop(@RequestBody Shop shop) {
        // 写入数据库
        return shopService.update(shop);
    }

//接口实现
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
```

## Redis缓存问题解决方案

### 解决缓存击穿
> 解决缓存击穿的思路就是数据库端查询结果为空值的时候将此时的k-v值（v为""表示空字符串）存入缓存 </p>
> 在redis缓存中查询的时候使用`StrUtil.isBlank()`条件进行判断，空字符串调用`isBlank()`方法的时候返回true

```java
//判断
//防止缓存穿透策略
        if(StrUtil.isBlank(shopJson) && shopJson != null){
            log.debug("查询不存在的值，防止穿透策略启动！");
//            return Result.fail("查询不到该值！");
            return null;
        }



//添加空值
if(shop == null){
                //数据库中没有值
                //存储空值防止穿透
                stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
    //            return Result.fail("查询不到该值！");
                return null;
            }

```

### 使用互斥锁实现缓存击穿解决（单体架构下）


> **先是加锁，释放锁的方法**
>> 加锁解锁都是利用redis实现，加锁使用`setIfAbsent()`函数，解锁直接`delete()`
```java 
 private boolean tryLock(String key){
        Boolean ifAbsent = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", LOCK_SHOP_TTL, TimeUnit.MINUTES);
        return BooleanUtil.isTrue(ifAbsent); //防止自动拆箱导致的空指针异常
    }

private boolean unlock(String key){
        Boolean delete = stringRedisTemplate.delete(key);
        return BooleanUtil.isTrue(delete);
    }


```

> **下面是业务整体**
>> 记住获取到锁之后要二次查询缓存
>>
>> 该业务属于初级版本，比如在获取锁失败后直接休眠50ms再次获取，以及分布式下不可用等问题，在后面会使用redisson解决
```java
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

```


### 使用逻辑过期解决缓存穿透问题
> 逻辑过期表示时间超过了指定时间缓存不会失效，而是逻辑意义上的失效。它的过期时间其实是由程序员控制的 
>
> 一般这种方法用于存储热点数据，其业务决定一般缓存未命中时直接返回空（没查到就是没在这次活动中咯）
>
> 这种做法的好处是，后台修改热点商品的数据时不会立即生效，而是逻辑过期后下一个请求将其缓存重建之后生效。并且缓存重建是创建新线程执行，是某种意义上的异步 

>> 下面是缓存重构函数
```java
/**
 * 缓存重建
 */
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
```

>> 下面是整体逻辑（同时利用了互斥锁）
```java
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
```



## 对redis工具类的封装
> 可以参考CacheClient这个类的代码
>> 我们拿出封装好的互斥锁解决缓存穿透函数进行讲解：

```java
public <R,ID> R queryWithPassThrough(
            String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit){
        String key = keyPrefix + id;
        // 1.从redis查询商铺缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        // 2.判断是否存在
        if (StrUtil.isNotBlank(json)) {
            // 3.存在，直接返回
            return JSONUtil.toBean(json, type);
        }
        // 判断命中的是否是空值
        if (json != null) {
            // 返回一个错误信息
            return null;
        }

        // 4.不存在，根据id查询数据库
        R r = dbFallback.apply(id);
        // 5.不存在，返回错误
        if (r == null) {
            // 将空值写入redis
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            // 返回错误信息
            return null;
        }
        // 6.存在，写入redis
        this.set(key, r, time, unit);
        return r;
    }
```

> 上述函数中，<R,ID>表示参数类型，R是返回值类型，Class<R> type的目的是指定R的类型，这样才能使用JSONUtil.toBean()方法
>
> Function<ID, R> dbFallback是操作数据库方法，该方法由于涉及到数据库（封装类不能知道操作哪个库），需要程序员在调用的时候手动传递该方法，dbFallback.apply()执行


## 优惠券秒杀业务

### Redis生成全局唯一id
> 由于订单数据量非常大，肯定之后会进行分表，若是普通自增肯定会导致id重复问题，于是我们需要全局唯一id(分布式可用)。
>> 唯一性，高可用，递增性，安全性，高性能

```java
public long nextId(String keyPrefix) {
        //生成时间戳
        LocalDateTime now = LocalDateTime.now();
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
        long timeStamp = nowSecond - BEGIN_TIMESTAMP;
        //生成序列号
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd")); //使用精确到天的日期作为分隔，以防超出自动增长大小限制
        long count = stringRedisTemplate.opsForValue().increment("icr:" + keyPrefix + ":" + date);
        //拼接与返回
        return timeStamp << COUNT_BITS | count;
    }

```

### 秒杀下单流程(初级版本)
> 这里只讲业务流程，并发问题，集群下问题在后面一一解决
```java
@Override
    @Transactional
    public Result seckillVoucher(Long voucherId) {
        //1.查询劵
        SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);
        //2.判断秒杀是否开始
        LocalDateTime beginTime = seckillVoucher.getBeginTime();
        if(!LocalDateTime.now().isAfter(beginTime)){
            return Result.fail("代金券抢购活动未开始");
        }
        //3.判断秒杀是否结束
        LocalDateTime endTime = seckillVoucher.getEndTime();
        if(LocalDateTime.now().isAfter(endTime)){
            return Result.fail("代金券抢购活动已结束");
        }
        //4.库存是否充足
        if(seckillVoucher.getStock()<0){
            return Result.fail("库存不足");
        }
        Long userId = UserHolder.getUser().getId();

        //5.扣除库存
        boolean isUpdate = seckillVoucherService.update().setSql("stock = stock -1").eq("voucher_id", voucherId).update();
        if(!isUpdate){
            return Result.fail("扣除库存失败");
        }

        //6.创建订单
        //6.1 创建对象
        VoucherOrder voucherOrder = new VoucherOrder();

        //6.2 生成唯一id
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);

        //6.3 用户id
        voucherOrder.setUserId(userId);

        //6.4 代金券id
        voucherOrder.setVoucherId(voucherId);

        save(voucherOrder);
        //返回订单id
        return Result.ok(orderId);

    }
```

### 解决秒杀超卖
> 在并发下（秒杀），多个线程同时去抢着修改数据库，很可能出现库存超卖现象。我们可以使用乐观锁的方式解决 
>
> 乐观锁是一种加锁理念，即认为别人不会同时修改数据。乐观锁不会上锁，只是在执行更新的时候判断一下在此期间别人是否修改了数据
>> 方法一：先查一次，后面修改时再查一次，判断两次是否相等。**缺点：**失败率太高，而且不符合业务需求
>>
>> 方法二：在修改时直接判断库存是否大于0（利用数据库行锁）。（使用这种）

```java
//5.扣除库存
boolean isUpdate = seckillVoucherService.update().setSql("stock = stock -1").eq("voucher_id", voucherId).update();
if(!isUpdate){
    return Result.fail("扣除库存失败");
}


//加乐观锁
boolean isUpdate = seckillVoucherService.update().setSql("stock = stock -1").eq("voucher_id", voucherId).gt("stock",0).update(); //gt指大于

```

### 实现一人一单
> 前情提要： 查询订单和封装对象的操作已经封装成createVoucherOrder()方法

```java
@Override
    @Transactional
    public Result createVoucherOrder(Long voucherId)
```

#### 初级版本：使用synchronize
>因为实现一人一单的业务逻辑，即只需要锁住相同用户就可以了
>
>该方式不适用于分布式，因为synchronize是本地锁
>
>由于userId是Long包装类型的，而且不同线程进来有不同的UserHolder。为了使synchronize锁住的是同一个对象，我们需要使用`toString()和intern()`方法。（只使用toString()只是又new了一个String类型的对象，还不能保证二者相等）

```java
Long userId = UserHolder.getUser().getId();

synchronized (userId.toString().intern()){
        return voucherOrderServiceImpl.createVoucherOrder(voucherId); //防止事务失效
    }
```

#### 版本2：使用redis实现分布式可用锁
> key使用前缀加用户id（一人一单）的形式。tryLock非阻塞加锁，加锁失败直接返回错误结果：一人不能购买多个！

```java
public boolean tryLock(long timeoutSec){
    long threadId = Thread.currentThread().getId(); //获取唯一标识作为value
    stringRedisTemplate.opsForValue().setIfAbsent(key,value,seconds,m)
}

public void unlock(){
    stringRedisTemplate.delete(key)
}

```
---

> **问题1：分布式锁误删**
>
> 线程1拿到锁执行，结果业务处于某种原因阻塞了，过了一会锁因为超时自动释放了，结果被线程2拿到了，线程2开始执行业务。结果没执行完呢线程1醒了，并且执行完了业务，把锁一删...
>
> 可想而知，线程2的锁被删除了（我刚提的锁啊~）。就在此时线程3又来了，拿到了锁。最后，线程2和线程3形成了并行，没有锁住...
>
> 解决方法：在获取锁的时候存进去一个标识（咱们存了个线程id），然后在释放锁的时候判断这个标识是否和自己一致。


>**1.不能直接使用线程id作为value，要解决分布式下多个jvm之间递增的线程id重复的问题**

```java
private static final String ID_PREFIX = UUID.randomUUID().toString(true) + "-";

String threadId = ID_PREFIX + Thread.currentThread().getId(); //拼接uuid

stringRedisTemplate.opsForValue()
.setIfAbsent(KEY_PREFIX+name,threadId,timeoutSec,TimeUnit.SECONDS);
```

> **2.改进释放锁逻辑**

```java
public void unlock() {
    // 获取线程标示
    String threadId = ID_PREFIX + Thread.currentThread().getId();
    // 获取锁中的标示
    String id = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);
    // 判断标示是否一致
    if(threadId.equals(id)) {
        // 释放锁
        stringRedisTemplate.delete(KEY_PREFIX + name);
    }
}
```

---

>**问题二：分布式锁的原子性问题**
>
>假如在释放锁方法中在判断标识是否一致之后还没有释放锁，结果被阻塞了，还是会导致之后阻塞恢复后把别人的锁删了。**所以判断和删除应该是原子性的！**
>
>解决：使用lua脚本(放在resource下就行)

`unlock.lua:`
```lua
-- 比较线程标示与锁中的标示是否一致
if(redis.call('get', KEYS[1]) ==  ARGV[1]) then
    -- 释放锁 del key
    return redis.call('del', KEYS[1])
end
return 0
```

```java
private static final String KEY_PREFIX = "lock:";
private static final String ID_PREFIX = UUID.randomUUID().toString(true) + "-";

/**
 * 对lua脚本的使用
 */
private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }


public void unlock() {
        // 调用lua脚本
        stringRedisTemplate.execute(
                UNLOCK_SCRIPT,
                Collections.singletonList(KEY_PREFIX + name),
                ID_PREFIX + Thread.currentThread().getId());
    }
```


#### 最终版本：使用专业框架redisson
> 我们在之前使用setnx实现的基于redis的锁仍存在**不可重入**，**不可重试**，**超时释放**，**主从一致性**的问题，这些单靠我们解决很难
>
> 于是我们引入专业的redission


> 配置类编写
```java
@Configuration
public class  RedissonConfig {

    @Bean
    public RedissonClient redissonClient(){
        // 配置
        Config config = new Config();
        config.useSingleServer().setAddress("redis://43.138.199.12:6379");
        // 创建RedissonClient对象
        return Redisson.create(config);
    }
}
```

> 使用
```java
    @Autowired
    RedissonClient redissonClient;

    RLock lock = redissonClient.getLock("order:" + userId);

    boolean isLock = lock.tryLock();

    if(!isLock){
            return Result.fail("不允许重复下单");
            }
    try{
            return voucherOrderServiceImpl.createVoucherOrder(voucherId); //防止事务失效
    }finally {
            lock.unlock();
        }


```

### 实现异步秒杀策略
>这里的异步，即用户来秒杀时通过redis保证lua脚本的原子性并发安全的现行扣减库存，判断一人一单后直接返回，数据库操作异步执行
>
> 我们在之前尝试过使用jvm的阻塞队列完成异步策略，但是这样会有**服务宕机后消息丢失以及空间上限不足**等问题
>
>中小型项目中可以使用redis作为消息队列。redis中有三种数据结构存储消息-->list，pubsub，stream
>> **list**可以**保证安全性，支持阻塞等待，但是不支持消息确认和消息回溯机制**，一旦消息被消费但是中间消费者重启，消息丢失
>>
>>**pubsub**模式最主要的特点就是**广播模式，但是它并不支持消息持久化**
>>
>>**stream**流是redis新出的数据结构，**支持持久化，阻塞等待，消息确认与回溯**，是非常强力的适用于消息队列的数据结构**（选择）**

> 首先，改进优惠券添加功能，在修改数据库同时使用redis缓存优惠券库存信息
```java
// 保存秒杀库存到Redis中
        stringRedisTemplate.opsForValue().set(SECKILL_STOCK_KEY + voucher.getId(), voucher.getStock().toString());
```

> 接着编写lua脚本,在lua脚本中需要执行判断，扣减，发送消息至消息队列的功能
```lua
-- 1.参数列表
-- 1.1.优惠券id
local voucherId = ARGV[1]
-- 1.2.用户id
local userId = ARGV[2]
-- 1.3.订单id
local orderId = ARGV[3]

-- 2.数据key
-- 2.1.库存key
local stockKey = 'seckill:stock:' .. voucherId
-- 2.2.订单key
local orderKey = 'seckill:order:' .. voucherId

-- 3.脚本业务
-- 3.1.判断库存是否充足 get stockKey
if(tonumber(redis.call('get', stockKey)) <= 0) then
    -- 3.2.库存不足，返回1
    return 1
end
-- 3.2.判断用户是否下单 SISMEMBER orderKey userId
if(redis.call('sismember', orderKey, userId) == 1) then
    -- 3.3.存在，说明是重复下单，返回2
    return 2
end
-- 3.4.扣库存 incrby stockKey -1
redis.call('incrby', stockKey, -1)
-- 3.5.下单（保存用户）sadd orderKey userId
redis.call('sadd', orderKey, userId)
-- 3.6.发送消息到队列中， XADD stream.orders * k1 v1 k2 v2 ...
redis.call('xadd', 'stream.orders', '*', 'userId', userId, 'voucherId', voucherId, 'id', orderId) //id命名是为了更好的对应数据库和类字段，方便自动填充
return 0
```

> 接着编写业务

>> 初始化脚本
```java
private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }
```

>> 执行脚本发送消息至消息队列，通过脚本返回内容判断结果
```java
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
```

>> 通过一个单线程阻塞执行消息队列中任务，异步无需返回结果（注意此时是业务外单线程，不能再使用UserHolder获取用户信息了）
>>
>> 前提：**提前往redis中创建好了消息队列 XGROUP CREATE stream.orders g1 0 MKSTREAM** 
>> (创建一个名为g1的消费者组，关联到stream.orders这个流数据结构，指定0作为消费者组的起始ID，
>> 如果stream.orders不存在则自动创建一个空的流12。这样，g1消费者组就可以从stream.orders中读取数据了)

>> 定义单线程任务，消息队列任务接收流程**重点关注**
```java
private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor(); //开个单线程用于处理消息队列中的消息

private static final String queueName = "stream.orders";

//类初始化后立即执行
@PostConstruct
private void init(){
    SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
}

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

    //消息确认失败后，消息会放入pending-list中，这是失败后的流程
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

```

>> 消息接收后执行的业务流程
>>
>> 其实很多情况下的判断已经不在需要，包括加锁可能也不太需要了，这里只是**兜底方案**
```java
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
```

## 探店笔记点赞功能（已实现点赞时间排序功能）
> 实现思路：使用redis中的set集合记录所有点赞过的人的id，**一旦已经点过赞了则需要设置islike属性让前端响应为高亮**，同时对点击事件做业务接口实现
>
> 本文档直接**快进到使用redis的ZSet数据结构的改进版本**。通过多存储一个时间戳，实现点赞时间排序的功能


> Blog entity 
>
> @TableField(exist = false)表示数据库中没有的字段，这个类中加了该注解的包括用户信息和是否点赞
```java
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_blog")
public class Blog implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    /**
     * 商户id
     */
    private Long shopId;
    /**
     * 用户id
     */
    private Long userId;
    /**
     * 用户图标
     */
    @TableField(exist = false)
    private String icon;
    /**
     * 用户姓名
     */
    @TableField(exist = false)
    private String name;
    /**
     * 是否点赞过了
     */
    @TableField(exist = false)
    private Boolean isLike;

    /**
     * 标题
     */
    private String title;

    /**
     * 探店的照片，最多9张，多张以","隔开
     */
    private String images;

    /**
     * 探店的文字描述
     */
    private String content;

    /**
     * 点赞数量
     */
    private Integer liked;

    /**
     * 评论数量
     */
    private Integer comments;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;


}
```

> 业务逻辑--每一次点击如何操作redis和数据库
```java
@Override
    public Result likeBlog(Long id) {
        // 1.获取登录用户
        Long userId = UserHolder.getUser().getId();
        // 2.判断当前登录用户是否已经点赞
        String key = BLOG_LIKED_KEY + id;
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        if (score == null) {
            // 3.如果未点赞，可以点赞
            // 3.1.数据库点赞数 + 1
            boolean isSuccess = update().setSql("liked = liked + 1").eq("id", id).update();
            // 3.2.保存用户到Redis的set集合  zadd key value score
            if (isSuccess) {
                stringRedisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
            }
        } else {
            // 4.如果已点赞，取消点赞
            // 4.1.数据库点赞数 -1
            boolean isSuccess = update().setSql("liked = liked - 1").eq("id", id).update();
            // 4.2.把用户从Redis的set集合移除
            if (isSuccess) {
                stringRedisTemplate.opsForZSet().remove(key, userId.toString());
            }
        }
        return Result.ok();
    }
```



> 判断是否已经点赞，如果点赞了则设置blog的isLike属性为true，这个方法一般在查询的时候被调用，因为**isLike不是数据库字段所以每次查询都应该赋一次值**
>
> 用户未登录，无需查询是否点赞，如果查询了则每次未登录进入首页会**爆空指针异常**
```java
private void isBlogLiked(Blog blog) {
        // 1.获取登录用户
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            // 用户未登录，无需查询是否点赞
            return;
        }
        Long userId = user.getId();
        // 2.判断当前登录用户是否已经点赞
        String key = "blog:liked:" + blog.getId();
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        blog.setIsLike(score != null);
    }
```

> 点赞时间排序
> 
> 这里注意细节：
>
> 1.redis中zset查出来的值时间戳小的放首位（**小score在前**）
>
> 2.由于数据库的SQL语句中的关键字IN并不会按你给的的顺序查找，单纯使用queryListByIds()并不会返回正确的顺序，这里建议使用SQL语句中的**ORDER BY FIELD手动指定顺序**
>使用mp中的last()将ORDER BY FIELD语句放在SQL末尾（自定义查询），**然后记住FIELD后面不能写死，需要进行字符串拼接**

```java
@Override
    public Result queryBlogLikes(Long id) {
        String key = BLOG_LIKED_KEY + id;
        // 1.查询top5的点赞用户 zrange key 0 4
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key, 0, 4); //查询前五条id集合
        if (top5 == null || top5.isEmpty()) {
            return Result.ok(Collections.emptyList()); //返回空
        }
        // 2.解析出其中的用户id
        List<Long> ids = top5.stream().map(Long::valueOf).collect(Collectors.toList()); //stream流解析封装
        String idStr = StrUtil.join(",", ids);
        // 3.根据用户id查询用户 WHERE id IN ( 5 , 1 ) ORDER BY FIELD(id, 5, 1)
        // User转UserDTO
        List<UserDTO> userDTOS = userService.query()
                .in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list()
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        // 4.返回
        return Result.ok(userDTOS);
    }
```

> 关注,取关与共同关注
>> 关注和取关逻辑较为简单，要实现共同关注，需要使用到redis求交集的功能，于是在关注后，还需要将数据加入redis中（set存储）
 
> 关注和取关
```java
@Override
    public Result follow(Long followUserId, Boolean isFollow) {
        // 1.获取登录用户
        Long userId = UserHolder.getUser().getId();
        String key = "follows:" + userId;
        // 1.判断到底是关注还是取关
        if (isFollow) {
            // 2.关注，新增数据
            Follow follow = new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(followUserId);
            boolean isSuccess = save(follow);
            if (isSuccess) {
                // 把关注用户的id，放入redis的set集合 sadd userId followerUserId
                stringRedisTemplate.opsForSet().add(key, followUserId.toString());
            }
        } else {
            // 3.取关，删除 delete from tb_follow where user_id = ? and follow_user_id = ?
            boolean isSuccess = remove(new QueryWrapper<Follow>()
                    .eq("user_id", userId).eq("follow_user_id", followUserId));
            if (isSuccess) {
                // 把关注用户的id从Redis集合中移除
                stringRedisTemplate.opsForSet().remove(key, followUserId.toString());
            }
        }
        return Result.ok();
    }
```

> 共同关注功能 : **stringRedisTemplate.opsForSet().intersect()**
```java
@Override
    public Result followCommons(Long id) {
        // 1.获取当前用户
        Long userId = UserHolder.getUser().getId();
        String key = "follows:" + userId;
        // 2.求交集
        String key2 = "follows:" + id;
        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(key, key2);
        if (intersect == null || intersect.isEmpty()) {
            // 无交集
            return Result.ok(Collections.emptyList());
        }
        // 3.解析id集合
        List<Long> ids = intersect.stream().map(Long::valueOf).collect(Collectors.toList());
        // 4.查询用户
        List<UserDTO> users = userService.listByIds(ids)
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(users);
    }
```

## Feed 流实现推送功能
> Feed流产品有两种不同的推送模式：
>> TimeLine,即按照时间顺序排列，例如朋友圈，实现简单，但是内容获取效率低
>>
>>智能排序,即投喂感兴趣的内容，但是如果不精准可能会起反作用

这里我们**选用TimeLine模式**，其中TimeLine模式下又有三种推送方式
>> 拉模式（读扩散），即临时拉去发件箱里的东西，**缺点：有延时**
>>
>> 推模式（写扩散），有几个粉丝写几份（广播），再发送到每一个收件箱中，对收件箱做排序，**缺点：内存占用高**
>>
>> 推拉结合，即普通人采用推模式，千万大v则使用拉模式。或者说活跃粉丝使用推模式，咸鱼使用拉模式

最终我们**采用推模式**完成这次的业务，业务的关键是**实现收件箱，以及对收件箱进行排序！**


> 如何实现分页？

feed流中存在数据不断变化的问题，所以角标会一直变化，因此传统的分页是无法完成需求的（可能会出现重复读问题）。
我们可以使用滚动分页，第一页指定lastId非常大，往下查5条，后面就不按角标查而是按照lastId查。

> 业务前需要了解的？
>> 1. 滚动查询指令

**` ZREVRANGEBYSCORE z1 (max) (min) WITHSCORES LIMIT 0 3 `**

（通过分数倒序查询）（key）（最大值，即下一次查询时这里记录的上一次的最小的score）（最小值，一般为0）（限制关键字）（offset，即小于等于max的第几个元素）

>> 2.参数解析

`max` : 当前时间戳 | 上一次查询最小时间戳

`min` : 0

`offset` : 0 | 在上一次结果中与最小值score相等的值个数

> 业务代码详解

> 保存博客并推送到粉丝收件箱(重点看第4条)
```java
 @Override
    public Result saveBlog(Blog blog) {
        // 1.获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        // 2.保存探店笔记
        boolean isSuccess = save(blog);
        if(!isSuccess){
            return Result.fail("新增笔记失败!");
        }
        // 3.查询笔记作者的所有粉丝 select * from tb_follow where follow_user_id = ?
        List<Follow> follows = followService.query().eq("follow_user_id", user.getId()).list();
        // 4.推送笔记id给所有粉丝
        for (Follow follow : follows) {
            // 4.1.获取粉丝id
            Long userId = follow.getUserId();
            // 4.2.推送
            String key = FEED_KEY + userId;
            stringRedisTemplate.opsForZSet().add(key, blog.getId().toString(), System.currentTimeMillis());
        }
        // 5.返回id
        return Result.ok(blog.getId());
    }
```

> 实现滚动分页查询关注的人发的博客

逻辑前准备
```java
// 接口路径和参数
@GetMapping("/of/follow")
    public Result queryBlogOfFollow(
            @RequestParam("lastId") Long max, @RequestParam(value = "offset", defaultValue = "0") Integer offset){
        return blogService.queryBlogOfFollow(max, offset);
    }

//返回值entity
@Data
public class ScrollResult {
    private List<?> list;
    private Long minTime;
    private Integer offset;
}

```
> 逻辑代码
>> 重点注意：1.第4条的巧妙算法 2.ORDER BY FIELD限制数据库顺序查询 3.最后记得遍历查询blog有关的用户和是否被点赞从而补全数据，封装返回
```java
// 逻辑代码
@Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        // 1.获取当前用户
        Long userId = UserHolder.getUser().getId();
        // 2.查询收件箱 ZREVRANGEBYSCORE key Max Min LIMIT offset count
        String key = FEED_KEY + userId;
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(key, 0, max, offset, 2);
        // 3.非空判断
        if (typedTuples == null || typedTuples.isEmpty()) {
            return Result.ok();
        }
        // 4.解析数据：blogId、minTime（时间戳）、offset
        List<Long> ids = new ArrayList<>(typedTuples.size());
        long minTime = 0; // 2
        int os = 1; // 2
        for (ZSetOperations.TypedTuple<String> tuple : typedTuples) { // 5 4 4 2 2
            // 4.1.获取id
            ids.add(Long.valueOf(tuple.getValue()));
            // 4.2.获取分数(时间戳）
            long time = tuple.getScore().longValue();
            if(time == minTime){
                os++;
            }else{
                minTime = time;
                os = 1;
            }
        }
         // 5.根据id查询blog
        String idStr = StrUtil.join(",", ids);
        List<Blog> blogs = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();

        for (Blog blog : blogs) {
            // 5.1.查询blog有关的用户
            queryBlogUser(blog);
            // 5.2.查询blog是否被点赞
            isBlogLiked(blog);
        }

        // 6.封装并返回
        ScrollResult r = new ScrollResult();
        r.setList(blogs);
        r.setOffset(os);
        r.setMinTime(minTime);

        return Result.ok(r);
    }
```
 

## GEO数据结构实现有关地理位置业务
> GEO数据结构允许存储地理位置信息（经纬度坐标信息），同时有命令支持在一个范围内进行查找

> 相关指令

`GEOADD [KEY] [经纬度] [成员名称（value值）]` --> 在底层将经纬度转化为score进行存储

`GEODIST [KEY] [两个成员的名称] [单位，默认m]` --> 返回两个地点的距离

`GEOSEARCH [KEY] FROMLONLAT(搜索方式，这里是经纬度查询) [经纬度] BYRADIUS(根据半径查找) 10km(半径长度) WITHDIST(返回值指出具体离多远)`

### 实现附近商家搜索功能

> 导入商铺数据
>> 首先需要按照商铺类型做分组，类型相同的商品作为一组，以typeId为key存入同一个GEO集合中
>>
>> 这里使用了stream流特性，通过stream().collect()完成以typeId的分组，返回map。后通过map.entrySet()的遍历拿到每一组的信息。最后
>> 使用了geoadd的批量写入操作，先new出locations后再往里面放数据，最后一起导入到redis中
```java
@Test
    void loadShopData() {
        // 1.查询店铺信息
        List<Shop> list = shopService.list();
        // 2.把店铺分组，按照typeId分组，typeId一致的放到一个集合
        Map<Long, List<Shop>> map = list.stream().collect(Collectors.groupingBy(Shop::getTypeId));
        // 3.分批完成写入Redis
        for (Map.Entry<Long, List<Shop>> entry : map.entrySet()) {
            // 3.1.获取类型id
            Long typeId = entry.getKey();
            String key = SHOP_GEO_KEY + typeId;
            // 3.2.获取同类型的店铺的集合
            List<Shop> value = entry.getValue();
            List<RedisGeoCommands.GeoLocation<String>> locations = new ArrayList<>(value.size());
            // 3.3.写入redis GEOADD key 经度 纬度 member
            for (Shop shop : value) {
                // stringRedisTemplate.opsForGeo().add(key, new Point(shop.getX(), shop.getY()), shop.getId().toString());
                locations.add(new RedisGeoCommands.GeoLocation<>(
                        shop.getId().toString(),
                        new Point(shop.getX(), shop.getY())
                ));
            }
            stringRedisTemplate.opsForGeo().add(key, locations);
        }
    }
```


> 业务实现

- **controller：(注意x，y参数前端可以不传)**
```java
/**
     * 根据商铺类型分页查询商铺信息
     * @param typeId 商铺类型
     * @param current 页码
     * @return 商铺列表
     */
    @GetMapping("/of/type")
    public Result queryShopByType(
            @RequestParam("typeId") Integer typeId,
            @RequestParam(value = "current", defaultValue = "1") Integer current,
            @RequestParam(value = "x", required = false) Double x, 
            @RequestParam(value = "y", required = false) Double y
    ) {
       return shopService.queryShopByType(typeId, current, x, y);
    }
```
- **业务逻辑：**
>> 注意这里的分页 geosearch中的limit只能指定查0到end个数据，必须要**人工进行判断检测和手动截取from到end之间的数据**
>>
>> 可以使用stream流做手动截取，同时因为geosearch查出来的数据也是有**远近顺序**的，需要使用`last("ORDER BY FIELD(id," + idStr + ")")`使数据库查询遵循顺序

```java
@Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        // 1.判断是否需要根据坐标查询
        if (x == null || y == null) {
            // 不需要坐标查询，按数据库查询
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            // 返回数据
            return Result.ok(page.getRecords());
        }

        // 2.计算分页参数
        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;

        // 3.查询redis、按照距离排序、分页。结果：shopId、distance
        String key = SHOP_GEO_KEY + typeId;
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo() // GEOSEARCH key BYLONLAT x y BYRADIUS 10 WITHDISTANCE
                .search(
                        key,
                        GeoReference.fromCoordinate(x, y),
                        new Distance(5000),
                        RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end)
                );
        // 4.解析出id
        if (results == null) {
            return Result.ok(Collections.emptyList());
        }
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        if (list.size() <= from) {
            // 没有下一页了，结束
            return Result.ok(Collections.emptyList());
        }
        // 4.1.截取 from ~ end的部分
        List<Long> ids = new ArrayList<>(list.size());
        Map<String, Distance> distanceMap = new HashMap<>(list.size());
        list.stream().skip(from).forEach(result -> {
            // 4.2.获取店铺id
            String shopIdStr = result.getContent().getName();
            ids.add(Long.valueOf(shopIdStr));
            // 4.3.获取距离
            Distance distance = result.getDistance();
            distanceMap.put(shopIdStr, distance);
        });
        // 5.根据id查询Shop
        String idStr = StrUtil.join(",", ids);
        List<Shop> shops = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();
        for (Shop shop : shops) {
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        }
        // 6.返回
        return Result.ok(shops);
    }
        
```

## BitMap数据结构实现用户签到功能
> 用户签到这个业务，如果每个用户签到一次就记录一次数据库，那么内存会超大。于是redis诞生BitMap数据结构。
> 
> 我们可以将0视为未签到，1视为签到，则一个用户一个月的签到量就最多只有31比特占用。这种思路也被称为位图。
>
> 下面是redis中操作该数据结构的一些命令：
>> SETBIT [KEY] [索引] 1/0
>> 
>> BITCOUNT [KEY]
>>
>> GETBIT [KEY] [索引]
>>
>> BITFIELD [KEY] [GET/...] u/i(有无符号位)2(几个bit位) --> 从左向右获取指定位数，并转化为10进制

### 用户签到业务
> 以每个月为key，存储31个bit
> 
> 注意：获取DayOfMonth的时候是从1开始获取，但是redis中bitmap是从0开始设置，注意对应关系

```java
@Override
    public Result sign() {
        // 1.获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        // 2.获取日期
        LocalDateTime now = LocalDateTime.now();
        // 3.拼接key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;
        // 4.获取今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();
        // 5.写入Redis SETBIT key offset 1
        stringRedisTemplate.opsForValue().setBit(key, dayOfMonth - 1, true);
        return Result.ok();
    }
```

### 用户签到统计业务
> 通过末尾（最后一次签到）向前统计，遇到0则结束统计并返回
>
> 可以通过与1进行与运算后右移，重复该动作来完成该业务核心逻辑

```java
@Override
    public Result signCount() {
        // 1.获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        // 2.获取日期
        LocalDateTime now = LocalDateTime.now();
        // 3.拼接key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;
        // 4.获取今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();
        // 5.获取本月截止今天为止的所有的签到记录，返回的是一个十进制的数字 BITFIELD sign:5:202203 GET u14 0
        List<Long> result = stringRedisTemplate.opsForValue().bitField(
                key,
                BitFieldSubCommands.create()
                        .get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0)
        );
        if (result == null || result.isEmpty()) {
            // 没有任何签到结果
            return Result.ok(0);
        }
        Long num = result.get(0);
        if (num == null || num == 0) {
            return Result.ok(0);
        }
        // 6.循环遍历
        int count = 0;
        while (true) {
            // 6.1.让这个数字与1做与运算，得到数字的最后一个bit位  // 判断这个bit位是否为0
            if ((num & 1) == 0) {
                // 如果为0，说明未签到，结束
                break;
            }else {
                // 如果不为0，说明已签到，计数器+1
                count++;
            }
            // 把数字右移一位，抛弃最后一个bit位，继续下一个bit位
            num >>>= 1;
        }
        return Result.ok(count);
    }
```

## HyperLogLog用法
> 可以用它来统计UV，即网站一天的用户访问量（UV是相同用户访问只记录一次），如果将每个访问的用户都存在redis中，数据量会非常恐怖
>
> Hyperlog就如同set一般，只记录一次重复值，同时它能保证它产生的文件大小不超过16kb！！（但是会有0.81%的误差）

>我们用一个案例来测试HyperLogLog，这个测试算法也挺精妙的
```java
@Test
    void testHyperLogLog() {
        String[] values = new String[1000];
        int j = 0;
        for (int i = 0; i < 1000000; i++) {
            j = i % 1000;
            values[j] = "user_" + i;
            if(j == 999){
                // 发送到Redis
                stringRedisTemplate.opsForHyperLogLog().add("hl2", values);
            }
        }
        // 统计数量
        Long count = stringRedisTemplate.opsForHyperLogLog().size("hl2");
        System.out.println("count = " + count);
    }
```
