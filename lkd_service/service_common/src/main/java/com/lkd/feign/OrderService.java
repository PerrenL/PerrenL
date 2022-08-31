package com.lkd.feign;
import com.lkd.feign.fallback.OrderServiceFallbackFactory;
import com.lkd.vo.PayVO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(value = "order-service",fallbackFactory = OrderServiceFallbackFactory.class)
public interface OrderService {

    /**
     * 微信支付下单
     * @param payVO
     * @return
     */
    @PostMapping("/order/weixinPay")
    String weixinPay(@RequestBody PayVO payVO);

}