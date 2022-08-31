package com.lkd.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.lkd.vo.PayVO;
import com.lkd.entity.OrderEntity;
import com.lkd.vo.OrderVO;
import com.lkd.vo.Pager;

import java.util.List;

public interface OrderService extends IService<OrderEntity> {


    /**
     * 通过订单编号获取订单实体
     * @param orderNo
     * @return
     */
    OrderEntity getByOrderNo(String orderNo);


    /**
     * 微信小程序支付创建订单
     * @param payVO
     * @return
     */
    OrderEntity createOrder(PayVO payVO);


    /**
     * 发送出货通知
     * @param orderNo
     * @param skuId
     * @param innerCode
     * @return
     */
    boolean vendout(String  orderNo,Long skuId,String innerCode);





}
