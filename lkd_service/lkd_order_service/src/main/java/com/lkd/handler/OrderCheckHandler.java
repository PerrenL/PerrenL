package com.lkd.handler;
import com.google.common.base.Strings;
import com.lkd.business.MsgHandler;
import com.lkd.common.VMSystem;
import com.lkd.config.TopicConfig;
import com.lkd.contract.OrderCheck;
import com.lkd.emq.Topic;
import com.lkd.entity.OrderEntity;
import com.lkd.service.OrderService;
import com.lkd.utils.JsonUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
@Slf4j
@Topic(TopicConfig.ORDER_CHECK_TOPIC)
public class OrderCheckHandler implements MsgHandler {
    @Autowired
    private OrderService orderService;

    @Override
    public void process(String jsonMsg) throws IOException {
        OrderCheck orderCheck = JsonUtil.getByJson(jsonMsg, OrderCheck.class);
        if(orderCheck == null || Strings.isNullOrEmpty(orderCheck.getOrderNo())) return;
        //查询订单
        OrderEntity orderEntity = orderService.getByOrderNo(orderCheck.getOrderNo());
        if(orderEntity == null) return;
        if(orderEntity.getStatus().equals(VMSystem.ORDER_STATUS_CREATE)){  //如果
            log.info("订单无效处理 订单号：{}",orderCheck.getOrderNo());
            orderEntity.setStatus(VMSystem.ORDER_STATUS_INVALID); //无效状态
            orderService.updateById(orderEntity);
        }
    }
}