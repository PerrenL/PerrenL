package com.lkd.http.controller;
import com.lkd.common.VMSystem;
import com.lkd.entity.OrderEntity;
import com.lkd.service.OrderService;
import com.lkd.utils.ConvertUtils;
import com.lkd.vo.PayVO;
import com.lkd.wxpay.WXConfig;
import com.lkd.wxpay.WxPayDTO;
import com.lkd.wxpay.WxPaySDKUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

@RestController
@RequestMapping("/order")
@Slf4j
public class OrderController {

    @Autowired
    private OrderService orderService;


    @Autowired
    private WxPaySDKUtil wxPaySDKUtil;


    /**
     * 微信小程序支付
     * @param payVO
     * @return
     */
    @PostMapping("/weixinPay")
    public String weixinPay(@RequestBody PayVO payVO){
        var orderEntity = orderService.createOrder(payVO);//创建订单
        //封装支付请求对象调用支付
        var wxPayDTO=new WxPayDTO();
        wxPayDTO.setBody(orderEntity.getSkuName());//商品名称
        wxPayDTO.setOutTradeNo(orderEntity.getOrderNo());//订单号
        wxPayDTO.setTotalFee(orderEntity.getAmount().intValue());//金额
        wxPayDTO.setOpenid(orderEntity.getOpenId());//用户id
        return wxPaySDKUtil.requestPay(wxPayDTO );
    }


    /**
     * 微信支付回调接口
     * @param request
     * @return
     */
    @RequestMapping("/payNotify")
    @ResponseBody
    public void payNotify(HttpServletRequest request, HttpServletResponse response){
        log.info("调用了回调方法");
        try {
            //输入流转换为xml字符串
            String notifyResult = ConvertUtils.convertToString( request.getInputStream() );
            String orderSn = wxPaySDKUtil.validPay(notifyResult);
            if(orderSn!=null){
                log.info("修改订单状态和支付状态");

                OrderEntity orderEntity = orderService.getByOrderNo(orderSn);  //查询订单
                if(orderEntity!=null){
                    orderEntity.setStatus(VMSystem.ORDER_STATUS_PAYED);//支付成功
                    orderEntity.setPayStatus(VMSystem.PAY_STATUS_PAYED);//支付成功
                    orderService.updateById(orderEntity);//保存

                    //发货
                    orderService.vendout(orderSn, orderEntity.getSkuId(),orderEntity.getInnerCode());

                    //给微信支付一个成功的响应
                    response.setContentType("text/xml");
                    response.getWriter().write(WXConfig.RESULT);
                }
            }

        }catch (Exception e){
            log.error("支付回调处理失败",e);
        }
    }



}
