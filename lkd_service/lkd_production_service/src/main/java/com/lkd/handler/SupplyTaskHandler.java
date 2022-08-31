package com.lkd.handler;
import com.lkd.business.MsgHandler;
import com.lkd.common.VMSystem;
import com.lkd.config.TopicConfig;
import com.lkd.contract.SupplyContract;
import com.lkd.emq.Topic;
import com.lkd.feign.VMService;
import com.lkd.http.vo.TaskDetailsViewModel;
import com.lkd.http.vo.TaskViewModel;
import com.lkd.service.TaskService;
import com.lkd.utils.JsonUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.stream.Collectors;

@Component
@Topic(TopicConfig.TASK_SUPPLY_TOPIC)
@Slf4j
public class SupplyTaskHandler implements MsgHandler {
    @Autowired
    private VMService vmService;

    @Autowired
    private TaskService taskService;

    @Override
    public void process(String jsonMsg){

        try {
            //1.解析协议内容
            var supplyContract = JsonUtil.getByJson(jsonMsg, SupplyContract.class);
            if(supplyContract==null) return;

            //2.找出被指派人
            var vm = vmService.getVMInfo(supplyContract.getInnerCode());
            var userId = taskService.getLeastUser(vm.getRegionId(), false);
            if(vm==null || userId==0) return;
            //3.创建补货工单

            var taskViewModel=new TaskViewModel();
            taskViewModel.setUserId(userId);
            taskViewModel.setCreateType(0);//创建类型
            taskViewModel.setProductType(VMSystem.TASK_TYPE_SUPPLY);
            taskViewModel.setInnerCode(supplyContract.getInnerCode());
            taskViewModel.setAssignorId(0);//创建人
            taskViewModel.setDesc("自动补货工单");

            taskViewModel.setDetails( supplyContract.getSupplyData().stream().map(c->{
                var taskDetailsViewModel=new TaskDetailsViewModel();
                taskDetailsViewModel.setChannelCode( c.getChannelId() );
                taskDetailsViewModel.setExpectCapacity( c.getCapacity() );
                taskDetailsViewModel.setSkuId(c.getSkuId());
                taskDetailsViewModel.setSkuName(c.getSkuName());
                taskDetailsViewModel.setSkuImage(c.getSkuImage());
                return taskDetailsViewModel;
            } ).collect(Collectors.toList()) );  //补货详情

            taskService.createTask(taskViewModel);
        } catch (Exception e) {
            e.printStackTrace();
            log.error( "创建自动补货工单出错"+e.getMessage() );
        }
    }
}