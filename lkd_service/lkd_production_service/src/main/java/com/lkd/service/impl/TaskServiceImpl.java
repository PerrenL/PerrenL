package com.lkd.service.impl;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import com.lkd.common.VMSystem;
import com.lkd.config.TopicConfig;
import com.lkd.contract.SupplyChannel;
import com.lkd.contract.SupplyContract;
import com.lkd.contract.TaskCompleteContract;
import com.lkd.dao.TaskDao;
import com.lkd.emq.MqttProducer;
import com.lkd.entity.TaskDetailsEntity;
import com.lkd.entity.TaskEntity;
import com.lkd.entity.TaskStatusTypeEntity;
import com.lkd.exception.LogicException;
import com.lkd.feign.UserService;
import com.lkd.feign.VMService;
import com.lkd.http.vo.CancelTaskViewModel;
import com.lkd.http.vo.TaskViewModel;
import com.lkd.service.TaskDetailsService;
import com.lkd.service.TaskService;
import com.lkd.service.TaskStatusTypeService;
import com.lkd.vo.Pager;
import com.lkd.vo.UserVO;
import com.lkd.vo.VmVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@Slf4j
public class TaskServiceImpl extends ServiceImpl<TaskDao,TaskEntity> implements TaskService{

    @Autowired
    private TaskStatusTypeService statusTypeService;


    @Override
    public Pager<TaskEntity> search(Long pageIndex, Long pageSize, String innerCode, Integer userId, String taskCode, Integer status, Boolean isRepair, String start, String end) {
        Page<TaskEntity> page = new Page<>(pageIndex,pageSize);
        LambdaQueryWrapper<TaskEntity> qw = new LambdaQueryWrapper<>();
        if(!Strings.isNullOrEmpty(innerCode)){
            qw.eq(TaskEntity::getInnerCode,innerCode);
        }
        if(userId != null && userId > 0){
            qw.eq(TaskEntity::getUserId,userId);
        }
        if(!Strings.isNullOrEmpty(taskCode)){
            qw.like(TaskEntity::getTaskCode,taskCode);
        }
        if(status != null && status > 0){
            qw.eq(TaskEntity::getTaskStatus,status);
        }
        if(isRepair != null){
            if(isRepair){
                qw.ne(TaskEntity::getProductTypeId, VMSystem.TASK_TYPE_SUPPLY);
            }else {
                qw.eq(TaskEntity::getProductTypeId,VMSystem.TASK_TYPE_SUPPLY);
            }
        }
        if(!Strings.isNullOrEmpty(start) && !Strings.isNullOrEmpty(end)){
            qw
                    .ge(TaskEntity::getCreateTime, LocalDate.parse(start, DateTimeFormatter.ISO_LOCAL_DATE))
                    .le(TaskEntity::getCreateTime,LocalDate.parse(end,DateTimeFormatter.ISO_LOCAL_DATE));
        }
        //根据最后更新时间倒序排序
        qw.orderByDesc(TaskEntity::getUpdateTime);

        return Pager.build(this.page(page,qw));
    }



    @Override
    public List<TaskStatusTypeEntity> getAllStatus() {
        QueryWrapper<TaskStatusTypeEntity> qw = new QueryWrapper<>();
        qw.lambda()
                .ge(TaskStatusTypeEntity::getStatusId,VMSystem.TASK_STATUS_CREATE);

        return statusTypeService.list(qw);
    }

    @Autowired
    private RedisTemplate<String,Object> redisTemplate;

    @Autowired
    private TaskDetailsService taskDetailsService;

    @Autowired
    private VMService vmService;

    @Autowired
    private UserService userService;

    @Autowired
    private MqttProducer mqttProducer;



    @Transactional
    @Override
    public boolean createTask(TaskViewModel taskViewModel) throws LogicException {

        if(hasTask(taskViewModel.getInnerCode(),taskViewModel.getProductType())) {
            throw new LogicException("该机器有未完成的同类型工单");
        }
        //跨服务查询售货机微服务，得到售货机的地址和区域id
        VmVO vm = vmService.getVMInfo(taskViewModel.getInnerCode());
        if(vm==null){
            throw new LogicException("该机器不存在");
        }
        checkCreateTask(vm.getVmStatus(),taskViewModel.getProductType());//验证售货机状态

        //跨服务查询用户微服务，得到用户名
        UserVO user = userService.getUser(taskViewModel.getUserId());
        if(user==null){
            throw new LogicException("该用户不存在");
        }

        //新增工单表记录
        TaskEntity taskEntity = new TaskEntity();
        taskEntity.setTaskCode(generateTaskCode());//工单编号
        BeanUtils.copyProperties(taskViewModel,taskEntity);//复制属性
        taskEntity.setTaskStatus(VMSystem.TASK_STATUS_CREATE);//工单状态
        taskEntity.setProductTypeId(taskViewModel.getProductType());//工单类型

        taskEntity.setAddr(vm.getNodeAddr());//地址
        taskEntity.setRegionId(  vm.getRegionId() );//区域
        taskEntity.setUserName(user.getUserName());//用户名

        this.save(taskEntity);

        //如果是补货工单，向 工单明细表插入记录
        if(taskEntity.getProductTypeId() == VMSystem.TASK_TYPE_SUPPLY){
            taskViewModel.getDetails().forEach(d->{
                TaskDetailsEntity detailsEntity = new TaskDetailsEntity();
                BeanUtils.copyProperties( d,detailsEntity );
                detailsEntity.setTaskId(taskEntity.getTaskId());
                taskDetailsService.save(detailsEntity);
            });
        }
        updateTaskZSet(taskEntity,1);

        return true;
    }

    /**
     * 生成工单编号
     * @return
     */
    private String generateTaskCode(){
        //日期+序号
        String date = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));  //日期字符串
        String key= "lkd.task.code."+date; //redis key
        Object obj = redisTemplate.opsForValue().get(key);
        if(obj==null){
            redisTemplate.opsForValue().set(key,1L, Duration.ofDays(1) );
            return date+"0001";
        }
        return date+  Strings.padStart( redisTemplate.opsForValue().increment(key,1).toString(),4,'0');
    }


    /**
     * 创建工单校验
     * @param vmStatus
     * @param productType
     * @throws LogicException
     */
    private void checkCreateTask(Integer vmStatus,int productType) throws LogicException {
        //如果是投放工单，状态为运营
        if(productType == VMSystem.TASK_TYPE_DEPLOY  && vmStatus == VMSystem.VM_STATUS_RUNNING){
            throw new LogicException("该设备已在运营");
        }

        //如果是补货工单，状态不是运营状态
        if(productType == VMSystem.TASK_TYPE_SUPPLY  && vmStatus != VMSystem.VM_STATUS_RUNNING){
            throw new LogicException("该设备不在运营状态");
        }

        //如果是撤机工单，状态不是运营状态
        if(productType == VMSystem.TASK_TYPE_REVOKE  && vmStatus != VMSystem.VM_STATUS_RUNNING){
            throw new LogicException("该设备不在运营状态");
        }
    }


    /**
     * 同一台设备下是否存在未完成的工单
     * @param innerCode
     * @param productionType
     * @return
     */
    private boolean hasTask(String innerCode,int productionType){
        QueryWrapper<TaskEntity> qw = new QueryWrapper<>();
        qw.lambda()
                .select(TaskEntity::getTaskId)
                .eq(TaskEntity::getInnerCode,innerCode)
                .eq(TaskEntity::getProductTypeId,productionType)
                .le(TaskEntity::getTaskStatus,VMSystem.TASK_STATUS_PROGRESS);
        return this.count(qw) > 0;
    }


    @Override
    public boolean accept(Long id) {
        TaskEntity task = this.getById(id);  //查询工单
        if(task.getTaskStatus()!= VMSystem.TASK_STATUS_CREATE ){
            throw new LogicException("工单状态不是待处理");
        }
        task.setTaskStatus( VMSystem.TASK_STATUS_PROGRESS );//修改工单状态为进行
        return this.updateById(task);
    }


    @Override
    public boolean cancelTask(Long id, CancelTaskViewModel cancelVM) {
        TaskEntity task = this.getById(id);  //查询工单
        if(task.getTaskStatus()== VMSystem.TASK_STATUS_FINISH  || task.getTaskStatus()== VMSystem.TASK_STATUS_CANCEL ){
            throw new LogicException("工单已经结束");
        }
        task.setTaskStatus( VMSystem.TASK_STATUS_CANCEL  );
        task.setDesc(cancelVM.getDesc());
        updateTaskZSet(task,-1); //工单数 -1
        return this.updateById(task);
    }

    @Override
    public boolean completeTask(long id) {
        TaskEntity taskEntity = this.getById(id);
        if(taskEntity.getTaskStatus()== VMSystem.TASK_STATUS_FINISH  || taskEntity.getTaskStatus()== VMSystem.TASK_STATUS_CANCEL ){
            throw new LogicException("工单已经结束");
        }
        taskEntity.setTaskStatus(VMSystem.TASK_STATUS_FINISH);//修改工单状态
        this.updateById(taskEntity);

        //如果是投放工单或撤机工单
        if(taskEntity.getProductTypeId()==VMSystem.TASK_TYPE_DEPLOY
                || taskEntity.getProductTypeId()==VMSystem.TASK_TYPE_REVOKE){
            noticeVMServiceStatus(taskEntity);//运维工单封装与下发
        }
        //如果是补货工单!
        if(taskEntity.getProductTypeId()==VMSystem.TASK_TYPE_SUPPLY){
            noticeVMServiceSupply(taskEntity);
        }
        return true;
    }




    /**
     * 运维工单封装与下发
     * @param taskEntity
     */
    private void noticeVMServiceStatus(TaskEntity taskEntity){
        //向消息队列发送消息，通知售货机更改状态
        //封装协议
        TaskCompleteContract taskCompleteContract=new TaskCompleteContract();
        taskCompleteContract.setInnerCode(taskEntity.getInnerCode());//售货机编号
        taskCompleteContract.setTaskType( taskEntity.getProductTypeId() );//工单类型
        //发送到emq
        try {
            mqttProducer.send( TopicConfig.VMS_COMPLETED_TOPIC,2, taskCompleteContract );
        } catch (Exception e) {
            log.error("发送工单完成协议出错");
            throw new LogicException("发送工单完成协议出错");
        }
    }


    /**
     * 补货协议封装与下发
     * @param taskEntity
     */
    private void noticeVMServiceSupply(TaskEntity taskEntity){

        //协议内容封装
        //1.根据工单id查询工单明细表
        QueryWrapper<TaskDetailsEntity> qw = new QueryWrapper<>();
        qw.lambda()
                .eq(TaskDetailsEntity::getTaskId,taskEntity.getTaskId());
        List<TaskDetailsEntity> details = taskDetailsService.list(qw);
        //2.构建协议内容
        SupplyContract supplyContract = new SupplyContract();
        supplyContract.setInnerCode(taskEntity.getInnerCode());//售货机编号
        List<SupplyChannel> supplyChannels = Lists.newArrayList();//补货数据
        //从工单明细表提取数据加到补货数据中
        details.forEach(d->{
            SupplyChannel channel = new SupplyChannel();
            channel.setChannelId(d.getChannelCode());//货道编号
            channel.setCapacity(d.getExpectCapacity());//补货数量
            supplyChannels.add(channel);
        });
        supplyContract.setSupplyData(supplyChannels);

        //3.下发补货协议
        //发送到emq
        try {
            mqttProducer.send( TopicConfig.VMS_SUPPLY_TOPIC,2, supplyContract );
        } catch (Exception e) {
            log.error("发送工单完成协议出错");
            throw new LogicException("发送工单完成协议出错");
        }
    }


    /**
     * 更新工单量列表
     * @param taskEntity
     * @param score 增加的分值
     */
    private void  updateTaskZSet(TaskEntity taskEntity,int score){
        String roleCode="1003";//运维员
        if(taskEntity.getProductTypeId().intValue()==2){//如果是补货工单
            roleCode="1002";//运营员
        }
        String key= VMSystem.REGION_TASK_KEY_PREF
                + LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"))
                +"."+ taskEntity.getRegionId()+"."+roleCode;
        redisTemplate.opsForZSet().incrementScore(key,taskEntity.getUserId(),score);
    }



    @Override
    public int getLeastUser(Long regionId, Boolean isRepair) {
        String roleCode="1002";
        if(isRepair){ //如果是运维工单
            roleCode="1003";
        }
        String key= VMSystem.REGION_TASK_KEY_PREF
                + LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"))
                +"."+ regionId+"."+roleCode;
        Set<Object> set = redisTemplate.opsForZSet().range(key, 0, 0);
        if(set==null || set.isEmpty()){
           return 0;  //如果没有数据则返回0
        }
        return (Integer)set.stream().collect( Collectors.toList()).get(0) ;
    }



}
