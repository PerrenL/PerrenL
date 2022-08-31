package com.lkd.http.controller;
import com.google.common.base.Strings;
import com.lkd.config.WXConfig;
import com.lkd.exception.LogicException;
import com.lkd.feign.OrderService;
import com.lkd.utils.ConvertUtils;
import com.lkd.utils.OpenIDUtil;
import com.lkd.vo.PayVO;
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
    private WXConfig wxConfig;

    /**
     * 获取openId
     * @param jsCode
     * @return
     */
    @GetMapping("/openid/{jsCode}")
    public String getOpenid(@PathVariable("jsCode")  String jsCode){
        String openId = OpenIDUtil.getOpenId(wxConfig.getAppId(), wxConfig.getAppSecret(), jsCode);
        log.info("openId:{} ,jsCode:{}",openId,jsCode);
        return openId;
    }

    @Autowired
    private OrderService orderService;

    /**
     * 小程序请求支付
     * @param payVO
     * @return
     */
    @PostMapping("/requestPay")
    public String requestPay(@RequestBody PayVO payVO){
        String responseData = orderService.weixinPay(payVO);
        if(Strings.isNullOrEmpty(responseData)){
            throw new LogicException("微信支付接口调用失败");
        }
        return responseData;
    }





}
