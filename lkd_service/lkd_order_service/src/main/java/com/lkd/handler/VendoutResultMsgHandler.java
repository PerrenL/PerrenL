package com.lkd.handler;

import com.google.common.base.Strings;
import com.lkd.business.MsgHandler;
import com.lkd.common.VMSystem;
import com.lkd.config.TopicConfig;
import com.lkd.contract.VendoutResultContract;
import com.lkd.emq.Topic;
import com.lkd.entity.OrderEntity;
import com.lkd.service.OrderService;
import com.lkd.utils.JsonUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 出货结果
 */
@Component
@Topic(TopicConfig.VMS_RESULT_TOPIC)
@Slf4j
public class VendoutResultMsgHandler implements MsgHandler {

    @Autowired
    private OrderService orderService;

    @Override
    public void process(String jsonMsg) throws IOException {
        var vendoutResultContract = JsonUtil.getByJson(jsonMsg,VendoutResultContract.class);
        if(Strings.isNullOrEmpty(vendoutResultContract.getInnerCode())) return;

        String orderNo = vendoutResultContract.getVendoutData().getOrderNo(); //取出订单号
        OrderEntity orderEntity = orderService.getByOrderNo(orderNo);
        //处理出货逻辑
        if( vendoutResultContract.isSuccess()){ //如果出货成功
            orderEntity.setStatus(VMSystem.ORDER_STATUS_VENDOUT_SUCCESS);
            log.info("出货成功  订单号:{}",orderNo);
        }else{   //出货失败
            orderEntity.setStatus(VMSystem.ORDER_STATUS_VENDOUT_FAIL);
            log.info("出货失败,发起退款 订单号:{}",orderNo);
            //todo: 发起退款
        }
        orderService.updateById( orderEntity );
    }

}