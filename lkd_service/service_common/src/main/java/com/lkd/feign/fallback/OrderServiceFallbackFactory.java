package com.lkd.feign.fallback;
import com.lkd.feign.OrderService;
import com.lkd.vo.PayVO;
import feign.hystrix.FallbackFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class OrderServiceFallbackFactory implements FallbackFactory<OrderService> {
    @Override
    public OrderService create(Throwable throwable) {
        log.error("调用订单服务失败",throwable);

        return new OrderService() {
            @Override
            public String weixinPay(PayVO payVO) {
                return null;
            }
        };
    }
}