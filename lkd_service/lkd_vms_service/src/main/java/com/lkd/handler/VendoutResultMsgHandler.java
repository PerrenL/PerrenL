package com.lkd.handler;

import com.google.common.base.Strings;
import com.lkd.business.MsgHandler;
import com.lkd.config.TopicConfig;
import com.lkd.contract.VendoutResultContract;
import com.lkd.emq.Topic;
import com.lkd.service.VendingMachineService;
import com.lkd.utils.JsonUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 出货结果
 */
@Component
@Topic(TopicConfig.VMS_RESULT_TOPIC)
public class VendoutResultMsgHandler implements MsgHandler {

    @Autowired
    private VendingMachineService vmService;

    @Override
    public void process(String jsonMsg) throws IOException {
        var vendoutResultContract = JsonUtil.getByJson(jsonMsg,VendoutResultContract.class);
        if(Strings.isNullOrEmpty(vendoutResultContract.getInnerCode())) return;
        //处理出货逻辑
        vmService.vendoutResult(vendoutResultContract);
    }
}