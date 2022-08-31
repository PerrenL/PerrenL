package com.lkd.handler;
import com.lkd.business.MsgHandler;
import com.lkd.common.VMSystem;
import com.lkd.config.TopicConfig;
import com.lkd.contract.VmStatusContract;
import com.lkd.emq.Topic;
import com.lkd.feign.VMService;
import com.lkd.http.vo.TaskViewModel;
import com.lkd.service.TaskService;
import com.lkd.utils.JsonUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
@Topic(TopicConfig.VMS_STATUS_TOPIC)
@Slf4j
public class VMStatusHandler implements MsgHandler {

    @Autowired
    private TaskService taskService;

    @Autowired
    private VMService vmService;

    @Override
    public void process(String jsonMsg) throws IOException {
        log.info("采集到状态数据");
        var vmStatusContract = JsonUtil.getByJson(jsonMsg, VmStatusContract.class);
        if(vmStatusContract == null || vmStatusContract.getStatusInfo() == null || vmStatusContract.getStatusInfo().size() <= 0) return;
        //如果为非正常状态，则创建维修工单
        if(vmStatusContract.getStatusInfo().stream().anyMatch(s->s.isStatus() == false)){
            try {
                //根据售货机编号，查询售货机
                var vmInfo = vmService.getVMInfo(vmStatusContract.getInnerCode());
                if(vmInfo==null){
                    return;
                }
                //查询最少工单量用户
                var userId = taskService.getLeastUser(vmInfo.getRegionId(),true);
                if(userId!=0){
                    //创建工单对象
                    var task = new TaskViewModel();
                    task.setUserId(userId);//执行
                    task.setInnerCode(vmStatusContract.getInnerCode());//售货机编码
                    task.setProductType(VMSystem.TASK_TYPE_REPAIR );//维修工单
                    task.setCreateType(0);//自动工单
                    task.setAssignorId(0);//自动工单
                    task.setDesc(jsonMsg);//将报文进行存储
                    taskService.createTask(task);
                }
            }catch (Exception ex){
                ex.printStackTrace();
                log.error("创建自动维修工单失败，msg is:"+jsonMsg);
            }
        }
    }
}