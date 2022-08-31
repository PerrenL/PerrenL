package com.lkd.handler;

import com.google.common.base.Strings;
import com.lkd.business.MsgHandler;
import com.lkd.config.TopicConfig;
import com.lkd.contract.VendoutContract;
import com.lkd.emq.Topic;
import com.lkd.service.VendingMachineService;
import com.lkd.utils.JsonUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 出货
 */
@Component
@Topic(TopicConfig.VMS_VENDOUT_TOPIC)
public class VendoutMsgHandler implements MsgHandler {

    @Autowired
    private VendingMachineService vmService;

    @Override
    public void process(String jsonMsg) throws IOException {
        VendoutContract vendoutContract = JsonUtil.getByJson(jsonMsg,VendoutContract.class);
        if(Strings.isNullOrEmpty(vendoutContract.getInnerCode())) return;
        //处理出货逻辑
        vmService.vendout(vendoutContract);
    }
}